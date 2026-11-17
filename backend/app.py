#!/usr/bin/env python3
"""QuietLedger backend (打扰税 / QuietLedger).

Local-first Android notification capture -> server-side "attention duty invoice".

Single-file HTTP server built only on the Python standard library, backed by
SQLite in WAL mode. Deploy behind a trusted HTTPS reverse proxy.

Routes seen by this process (after Caddy strips ``/quiet-api``):

    GET  /health
    POST /v1/accounts          create account (no auth)
    POST /v1/accounts/invite   issue a one-time device invitation (auth)
    POST /v1/accounts/join     redeem a device invitation
    GET  /v1/tariff            effective tariff table for the account (auth)
    POST /v1/settings          per-account unit seconds / daily exemption (auth)
    POST /v1/events            idempotent event ingest (auth)
    POST /v1/events/purge      delete noise events for the account (auth)
    POST /v1/profile           set account nickname (auth)
    GET  /v1/invoice           daily invoice, server-computed (auth)
    GET  /v1/weekly            7 day report (auth)
    GET  /v1/report            arbitrary-period report, week/month/quarter/year (auth)
    GET  /v1/devices           devices of the account (auth)
    GET  /v1/meta              service counters (auth)
    GET  /v1/app/latest        latest APK manifest (public, in-app update)
    GET  /apk/quietledger.apk  hosted APK download (public)
    POST /v1/receipt-shares    create a share link for a daily invoice (auth)
    GET  /r/<shareId>          shareable HTML receipt (no auth)

Duty rules v2026-11-c (deterministic, replicated offline by the Android client):
  unit = 8 s per event by default (account may override to 2..30 s);
  appFactor: chat apps x0.5, office apps x0.4, everything else x1.0;
  night (Asia/Shanghai hour >= 23 or < 7) x1.5;
  notice (title/text hits @所有人/@全体成员/@all/@everyone) x2.0;
  dnd (delivered while the device was in Do Not Disturb, system or in-app) x0.5;
  burst (same pkg, same device, previous event <= 120 s earlier) x0.4 (discount);
  raw = unit * appFactor * night * notice * dnd * burst;
  capped = min(raw, unit * capFactor)   # capFactor = 3.0, single-interruption cap
  per-event duty = max(1, ceil(capped)), rounding once at the very end;
  day = Asia/Shanghai calendar day; exemption 240 s by default (account may
  override to 0..1800 s).  savedSeconds = sum of (base - duty) where base is the
  price without dnd/burst/cap discounts, i.e. *all* discounts, not only bursts.
"""

import contextlib
import hashlib
import html
import ipaddress
import json
import logging
import math
import os
import re
import secrets
import signal
import sqlite3
import sys
import threading
import time
import traceback
from datetime import date as date_cls
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

# --------------------------------------------------------------------------- #
# Configuration / frozen contract constants
# --------------------------------------------------------------------------- #

SERVICE_NAME = "quiet-ledger"
VERSION = "1.4.0"
INVITE_TTL_MS = 10 * 60 * 1000
REQUIRE_HTTPS = os.environ.get("QUIETLEDGER_REQUIRE_HTTPS") == "1"
JOIN_CODE_RE = re.compile(r"^QLI-[A-Za-z0-9_-]{32}$")
SHARE_ID_RE = re.compile(r"^r_[A-Za-z0-9_-]{32}$")

# APK hosting for in-app updates.  ``latest.json`` is read on every request so
# a new build can be published without restarting the service.
APK_DIR = Path(os.environ.get("APK_DIR", str(Path(__file__).resolve().parent / "apk")))
APK_FILE_NAME = "quietledger.apk"
APK_MANIFEST_NAME = "latest.json"
APK_CONTENT_TYPE = "application/vnd.android.package-archive"
APP_FALLBACK_VERSION_CODE = 1
APP_FALLBACK_VERSION_NAME = "1.0.0"

TARIFF_VERSION = "2026-11-d"
CONVERSATION_FACTORS = {"work": 1.0, "family": 0.25, "friends": 0.25,
                        "casual": 0.1, "free": 0.0}
TARIFF_UPDATED_AT = "2026-11-15T00:00:00Z"
CURRENCY = "SEC"
UNIT_SECONDS = 8
UNIT_SECONDS_MIN = 2
UNIT_SECONDS_MAX = 30
NIGHT_MULTIPLIER = 1.5
NIGHT_START_HOUR = 23
NIGHT_END_HOUR = 7
# Burst is a *discount* since v2026-11-b: the 2nd and later event of the same
# app within BURST_WINDOW_SEC is charged at 40% of its normal duty.
BURST_MULTIPLIER = 0.4
BURST_WINDOW_SEC = 120
# v2026-11-c: a notification that arrives while the phone is in Do Not Disturb
# (system zen mode or the in-app "silence" toggle) is worth half the duty...
DND_MULTIPLIER = 0.5
# ...a group broadcast (@all / @everyone style) is worth twice, because it is
# one interruption aimed at everyone and usually wakes the whole group up...
NOTICE_MULTIPLIER = 2.0
# ...and one event can never cost more than CAP_FACTOR * unit, whatever
# combination of night/notice/burst hits it.  With today's tables the worst
# case is exactly unit * 3.0 (night 1.5 * notice 2.0), so the cap is a guard
# rail rather than an everyday discount; the client applies the same constant.
CAP_FACTOR = 3.0
EXEMPTION_PER_DAY = 240
EXEMPTION_MIN = 0
EXEMPTION_MAX = 1800

# App price weights by package-name prefix (longest prefix wins, default 1.0).
# Copied verbatim by app/src/main/java/dev/quietledger/app/core/Model.kt and
# documented in docs/TAX-RULES.md -- keep the three in sync.
APP_MULTIPLIER_DEFAULT = 1.0
APP_MULTIPLIERS = {
    # Chat / IM: one interruption, not one message.
    "com.tencent.mm": 0.5,        # WeChat
    "com.tencent.mobileqq": 0.5,  # QQ
    "com.tencent.tim": 0.5,       # TIM
    # Office / collaboration.
    "com.tencent.wework": 0.4,    # WeCom
    "com.alibaba.android.rimet": 0.4,  # DingTalk
    "com.ss.android.lark": 0.4,   # Feishu / Lark
    "com.larksuite.suite": 0.4,   # Lark suite
    "com.microsoft.teams": 0.4,   # Microsoft Teams
    "com.slack": 0.4,             # Slack
}

# Duty rounding is "ceil once at the end".  A tiny epsilon keeps IEEE-754
# artifacts (e.g. 25 * 0.4 == 10.000000000000000888) from adding a phantom
# second; the Android client uses the exact same constant.
CEIL_EPSILON = 1e-9

MAX_EVENTS_PER_REQUEST = 500
MAX_BODY_BYTES = 8 * 1024 * 1024
MAX_LINES = 40
MAX_TITLE_CHARS = 200
EVENT_TS_WINDOW_DAYS = 400

# Asia/Shanghai = UTC+8, no DST.  Day boundaries and "night" hours use this.
TZ = timezone(timedelta(hours=8))

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CATEGORIES = {"msg", "call", "promo", "system"}

# GET /v1/report: aggregation periods and contract limits.
REPORT_KINDS = ("week", "month", "quarter", "year")
REPORT_WEEK_MAX_DAYS = 30
REPORT_TOP_APPS = 5

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("DATA_DIR", str(BASE_DIR / "data")))
DB_PATH = Path(os.environ.get("DB_PATH", str(DATA_DIR / "quietledger.db")))
HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8791"))
SCHEMA_PATH = BASE_DIR / "schema.sql"

log = logging.getLogger(SERVICE_NAME)

# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #


def now_ms() -> int:
    return int(time.time() * 1000)


def iso_from_ms(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000.0, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def today_local() -> str:
    return datetime.now(TZ).date().isoformat()


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def as_str(value, max_len: int) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        value = str(value)
    return value.strip()[:max_len]


def as_int(value, default: int = 0) -> int:
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return default
    return default


def as_bool(value, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return default


def day_bounds(date_str: str) -> tuple:
    """Return [start_ms, end_ms) of an Asia/Shanghai calendar day."""
    d = date_cls.fromisoformat(date_str)
    start_local = datetime(d.year, d.month, d.day, tzinfo=TZ)
    start_ms = int(start_local.timestamp() * 1000)
    end_ms = int((start_local + timedelta(days=1)).timestamp() * 1000)
    return start_ms, end_ms


def app_factor(pkg: str) -> float:
    """Package -> price weight.  Longest matching prefix wins, default 1.0."""
    best_prefix = ""
    best_factor = APP_MULTIPLIER_DEFAULT
    for prefix, factor in APP_MULTIPLIERS.items():
        if pkg.startswith(prefix) and len(prefix) > len(best_prefix):
            best_prefix = prefix
            best_factor = factor
    return best_factor


def duty_ceil(value: float) -> int:
    """ceil() once, at the end, with at least 1 second per noticed event."""
    return max(1, int(math.ceil(value - CEIL_EPSILON)))


def account_rules(conn, account_id: int) -> dict:
    """Effective unit/exemption for an account (NULL columns mean defaults)."""
    unit = UNIT_SECONDS
    exemption = EXEMPTION_PER_DAY
    row = conn.execute(
        "SELECT unit_seconds, exemption_per_day FROM accounts WHERE id = ?",
        (account_id,),
    ).fetchone()
    if row is not None:
        if row["unit_seconds"] is not None:
            unit = int(row["unit_seconds"])
        if row["exemption_per_day"] is not None:
            exemption = int(row["exemption_per_day"])
    return {"unitSeconds": unit, "exemptionPerDay": exemption}


# --------------------------------------------------------------------------- #
# Database
# --------------------------------------------------------------------------- #


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout = 15000")
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


@contextlib.contextmanager
def db():
    conn = connect()
    try:
        yield conn
    finally:
        conn.close()


def init_db() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    schema = SCHEMA_PATH.read_text(encoding="utf-8")
    conn = sqlite3.connect(str(DB_PATH), timeout=15, isolation_level=None)
    try:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA synchronous = NORMAL")
        conn.executescript(schema)
        # Lightweight migrations for databases created before a column existed.
        account_cols = {row[1] for row in conn.execute("PRAGMA table_info(accounts)")}
        if "nickname" not in account_cols:
            conn.execute(
                "ALTER TABLE accounts ADD COLUMN nickname TEXT NOT NULL DEFAULT ''"
            )
        # v2026-11-b: per-account tax rules.  NULL keeps the global defaults,
        # so every pre-existing account keeps working after the upgrade.
        if "unit_seconds" not in account_cols:
            conn.execute("ALTER TABLE accounts ADD COLUMN unit_seconds INTEGER NULL")
        if "exemption_per_day" not in account_cols:
            conn.execute(
                "ALTER TABLE accounts ADD COLUMN exemption_per_day INTEGER NULL"
            )
        # v2026-11-c: per-event dnd / notice flags (booleans only, never text).
        # Older rows default to 0 so they are priced exactly like before.
        event_cols = {row[1] for row in conn.execute("PRAGMA table_info(events)")}
        if "dnd" not in event_cols:
            conn.execute("ALTER TABLE events ADD COLUMN dnd INTEGER NOT NULL DEFAULT 0")
        if "notice" not in event_cols:
            conn.execute(
                "ALTER TABLE events ADD COLUMN notice INTEGER NOT NULL DEFAULT 0"
            )
        if "conversation_class" not in event_cols:
            conn.execute(
                "ALTER TABLE events ADD COLUMN conversation_class TEXT NOT NULL DEFAULT ''"
            )
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# Duty computation (single source of truth for invoice / weekly / receipt)
# --------------------------------------------------------------------------- #


def fetch_day_events(conn, account_id: int, start_ms: int, end_ms: int, device_id):
    sql = (
        "SELECT id, device_id, pkg, app, ts, priority, category, dnd, notice, conversation_class "
        "FROM events WHERE account_id = ? AND ts >= ? AND ts < ?"
    )
    params = [account_id, start_ms, end_ms]
    if device_id and device_id != "all":
        sql += " AND device_id = ?"
        params.append(device_id)
    sql += " ORDER BY ts ASC, event_id ASC"
    return conn.execute(sql, params).fetchall()


def burst_flags(events) -> dict:
    """Map event row id -> bool; a prior event of the same device/app/class within 120 s.

    v2026-11-b: bursts are judged inside one Asia/Shanghai calendar day only.
    There is deliberately no look-back into the previous day, so the first
    event of a day is never a burst.  The Android client's ``Duty.compute``
    only sees that day's events, and this keeps the two calculations
    bit-identical right after midnight.
    """
    prev = {}
    flags = {}
    for ev in events:
        kind = ev["conversation_class"]
        if kind == "free":
            continue
        key = (ev["device_id"], ev["pkg"], kind)
        previous_ts = prev.get(key)
        flags[ev["id"]] = (
            previous_ts is not None
            and 0 <= ev["ts"] - previous_ts <= BURST_WINDOW_SEC * 1000
        )
        prev[key] = ev["ts"]
    return flags


def build_day(conn, account_id: int, date_str: str, device_id=None) -> dict:
    """Compute the deterministic duty summary for one Asia/Shanghai day.

    Returns a dict with lines / totals / stats / per-app aggregates.  No state
    is written, so invoice, weekly report and receipt always agree.  The
    account's effective unit/exemption is resolved here, and this function is
    the *only* place notifications are priced -- no second formula anywhere.
    """
    rules = account_rules(conn, account_id)
    unit = rules["unitSeconds"]
    exemption = rules["exemptionPerDay"]
    start_ms, end_ms = day_bounds(date_str)
    rows = fetch_day_events(conn, account_id, start_ms, end_ms, device_id)
    flags = burst_flags(rows)

    line_map = {}
    app_map = {}
    app_pkg = {}
    by_hour = [0] * 24
    night_events = 0
    burst_count = 0
    dnd_events = 0
    notice_events = 0
    saved_seconds = 0
    devices = set()
    total_duty = 0

    for ev in rows:
        local = datetime.fromtimestamp(ev["ts"] / 1000.0, TZ)
        hour = local.hour
        is_night = hour >= NIGHT_START_HOUR or hour < NIGHT_END_HOUR
        is_burst = flags.get(ev["id"], False)
        is_dnd = bool(ev["dnd"])
        is_notice = bool(ev["notice"])

        # v2026-11-c: apply every multiplier first, cap the raw seconds at
        # unit * CAP_FACTOR, then round exactly once:
        #   mult = app * night * notice * dnd * burst
        #   duty = max(1, ceil(min(unit * mult, unit * capFactor)))
        kind = ev["conversation_class"]
        multiplier = CONVERSATION_FACTORS.get(kind, app_factor(ev["pkg"]))
        base_multiplier = multiplier
        if is_night:
            multiplier *= NIGHT_MULTIPLIER
            base_multiplier *= NIGHT_MULTIPLIER
        if is_notice:
            multiplier *= NOTICE_MULTIPLIER
            base_multiplier *= NOTICE_MULTIPLIER
        if is_dnd:
            multiplier *= DND_MULTIPLIER
        if is_burst:
            multiplier *= BURST_MULTIPLIER
        raw = unit * multiplier
        duty = 0 if kind == "free" else duty_ceil(min(raw, unit * CAP_FACTOR))
        # savedSeconds is now *every* discount combined (dnd + burst + cap):
        # base = price with only night/notice applied, duty = what was charged.
        base_duty = 0 if kind == "free" else duty_ceil(unit * base_multiplier)
        saved = max(0, base_duty - duty)
        saved_seconds += saved

        by_hour[hour] += 1
        devices.add(ev["device_id"])
        total_duty += duty
        if is_night:
            night_events += 1
        if is_burst:
            burst_count += 1
        if is_dnd:
            dnd_events += 1
        if is_notice:
            notice_events += 1

        key = (ev["app"], ev["pkg"])
        line = line_map.get(key)
        if line is None:
            line = {"app": ev["app"], "pkg": ev["pkg"], "count": 0,
                    "duty": 0, "night": 0, "burst": 0, "dnd": 0, "notice": 0}
            line_map[key] = line
        line["count"] += 1
        line["duty"] += duty
        if is_night:
            line["night"] += 1
        if is_burst:
            line["burst"] += 1
        if is_dnd:
            line["dnd"] += 1
        if is_notice:
            line["notice"] += 1

        app = app_map.get(ev["app"])
        if app is None:
            app = {"count": 0, "duty": 0}
            app_map[ev["app"]] = app
        app["count"] += 1
        app["duty"] += duty
        # 同一应用名可能来自多个包：记第一个非空包名即可（报表行只需要能取图标/下钻）。
        if ev["pkg"] and ev["app"] not in app_pkg:
            app_pkg[ev["app"]] = ev["pkg"]

    def line_note(line: dict) -> str:
        # 固定顺序：夜间、连发、免打扰、群发；只拼非零项。
        notes = []
        if line["night"]:
            notes.append(f"夜间 {line['night']} 条")
        if line["burst"]:
            notes.append(f"连发 {line['burst']} 条")
        if line["dnd"]:
            notes.append(f"免打扰 {line['dnd']} 条")
        if line["notice"]:
            notes.append(f"群发 {line['notice']} 条")
        return " · ".join(notes)

    ordered = sorted(line_map.values(), key=lambda ln: (-ln["duty"], ln["app"], ln["pkg"]))
    lines = []
    for line in ordered[:MAX_LINES]:
        lines.append({
            "app": line["app"],
            "pkg": line["pkg"],
            "count": line["count"],
            "duty": line["duty"],
            "night": line["night"],
            "burst": line["burst"],
            "dnd": line["dnd"],
            "notice": line["notice"],
            "note": line_note(line),
        })
    if len(ordered) > MAX_LINES:
        rest = ordered[MAX_LINES:]
        lines.append({
            "app": "其他",
            "pkg": "",
            "count": sum(ln["count"] for ln in rest),
            "duty": sum(ln["duty"] for ln in rest),
            "night": sum(ln["night"] for ln in rest),
            "burst": sum(ln["burst"] for ln in rest),
            "dnd": sum(ln["dnd"] for ln in rest),
            "notice": sum(ln["notice"] for ln in rest),
            "note": f"合并 {len(rest)} 项",
        })

    event_count = len(rows)
    return {
        "date": date_str,
        "lines": lines,
        "totalDuty": total_duty,
        "exemption": exemption,
        "unitSeconds": unit,
        "savedSeconds": saved_seconds,
        "eventCount": event_count,
        "nightRatio": round(night_events / event_count, 4) if event_count else 0.0,
        "bursts": burst_count,
        "nightEvents": night_events,
        "dndEvents": dnd_events,
        "noticeEvents": notice_events,
        "byHour": by_hour,
        "devices": sorted(devices),
        "appMap": app_map,
        # v1.9.0: app name -> package, so report topApps can carry pkg (icons + drill-down).
        "appPkg": app_pkg,
    }


def get_or_create_invoice(conn, account_id: int, account_code: str, date_str: str) -> dict:
    """Stable invoice id per account+date; persisted so share links never change."""
    row = conn.execute(
        "SELECT invoice_id, created_at FROM invoices WHERE account_id = ? AND date = ?",
        (account_id, date_str),
    ).fetchone()
    if row is not None:
        return {"invoiceId": row["invoice_id"], "issuedAt": iso_from_ms(row["created_at"])}

    ymd = date_str.replace("-", "")
    digest = hashlib.sha256(f"{account_code}|{date_str}".encode("utf-8")).hexdigest().upper()
    created_at = now_ms()
    for offset in range(0, len(digest) - 3):
        candidate = f"INV-{ymd}-{digest[offset:offset + 4]}"
        conn.execute(
            "INSERT OR IGNORE INTO invoices (invoice_id, account_id, date, created_at) "
            "VALUES (?, ?, ?, ?)",
            (candidate, account_id, date_str, created_at),
        )
        row = conn.execute(
            "SELECT invoice_id, created_at FROM invoices WHERE account_id = ? AND date = ?",
            (account_id, date_str),
        ).fetchone()
        if row is not None:
            return {"invoiceId": row["invoice_id"], "issuedAt": iso_from_ms(row["created_at"])}
    raise RuntimeError("could not allocate a unique invoice id")


def invoice_payload(conn, account: dict, date_str: str, device_id=None) -> dict:
    day = build_day(conn, account["id"], date_str, device_id)
    invoice = get_or_create_invoice(
        conn, account["id"], account["account_code"], date_str
    )
    nick_row = conn.execute(
        "SELECT nickname FROM accounts WHERE id = ?", (account["id"],)
    ).fetchone()
    nickname = (nick_row["nickname"] if nick_row else "") or ""
    total = day["totalDuty"]
    exemption = day["exemption"]
    balance = exemption - total
    return {
        "invoiceId": invoice["invoiceId"],
        "accountCode": account["account_code"],
        "nickname": nickname,
        "date": date_str,
        "issuedAt": invoice["issuedAt"],
        "tariffVersion": TARIFF_VERSION,
        "lines": day["lines"],
        "totalDuty": total,
        "exemption": exemption,
        "balance": balance,
        "status": "paid" if balance >= 0 else "owed",
        "stats": {
            "events": day["eventCount"],
            "nightRatio": day["nightRatio"],
            "bursts": day["bursts"],
            "savedSeconds": day["savedSeconds"],
            "dndEvents": day["dndEvents"],
            "noticeEvents": day["noticeEvents"],
            "byHour": day["byHour"],
            "devices": day["devices"],
        },
    }


def tariff_payload(conn, account_id: int) -> dict:
    """Effective tariff for one account; field names stay backward compatible.

    ``unitSeconds`` / ``exemptionPerDay`` are the account's effective values
    (override or global default).  New in v2026-11-c: ``dndMultiplier``,
    ``noticeMultiplier`` and ``capFactor``; the client caches them so its local
    estimate stays bit-identical to the server.
    """
    rules = account_rules(conn, account_id)
    return {
        "version": TARIFF_VERSION,
        "currency": CURRENCY,
        "unitSeconds": rules["unitSeconds"],
        "nightMultiplier": NIGHT_MULTIPLIER,
        "nightStartHour": NIGHT_START_HOUR,
        "nightEndHour": NIGHT_END_HOUR,
        "burstMultiplier": BURST_MULTIPLIER,
        "burstWindowSec": BURST_WINDOW_SEC,
        "dndMultiplier": DND_MULTIPLIER,
        "noticeMultiplier": NOTICE_MULTIPLIER,
        "capFactor": CAP_FACTOR,
        "exemptionPerDay": rules["exemptionPerDay"],
        "appMultipliers": dict(APP_MULTIPLIERS),
        "appMultiplierDefault": APP_MULTIPLIER_DEFAULT,
        "updatedAt": TARIFF_UPDATED_AT,
    }


def valid_date(date_str: str) -> bool:
    if not DATE_RE.match(date_str or ""):
        return False
    try:
        date_cls.fromisoformat(date_str)
        return True
    except ValueError:
        return False


def month_end(day: date_cls) -> date_cls:
    """Last calendar day of ``day``'s month."""
    if day.month == 12:
        return date_cls(day.year, 12, 31)
    return date_cls(day.year, day.month + 1, 1) - timedelta(days=1)


def report_period(kind: str, end: date_cls, days: int) -> tuple:
    """Resolve the (start, end) window of one GET /v1/report period.

    ``month`` / ``quarter`` / ``year`` snap the start to the natural calendar
    boundary of the requested ``end`` day and never pad the end into the future.
    The caller has already validated ``kind`` / ``days`` / ``end``.
    """
    if kind == "week":
        return end - timedelta(days=days - 1), end
    if kind == "month":
        return end.replace(day=1), end
    if kind == "quarter":
        return date_cls(end.year, ((end.month - 1) // 3) * 3 + 1, 1), end
    if kind == "year":
        return date_cls(end.year, 1, 1), end
    raise ApiError(400, "bad_request", "kind must be week, month, quarter or year")


def report_buckets(kind: str, start: date_cls, end: date_cls) -> list:
    """Ordered bucket plan ``(label, bucket_start, bucket_end)`` for a period.

    ``week`` / ``month`` bucket per calendar day (``MM-dd`` labels), clipped to
    the requested ``end`` (no future days are padded in).  ``quarter`` / ``year``
    bucket per *natural month* (``YYYY-MM`` labels), clipped to ``end`` as well:
    a quarter ending 2026-11-10 has an October bucket and a November bucket
    ending on the 10th, never a zero December bar in the client chart.  Empty
    days/months before ``end`` are emitted too, so the chart keeps its shape.
    """
    plan = []
    if kind in ("week", "month"):
        cursor = start
        while cursor <= end:
            plan.append((cursor.strftime("%m-%d"), cursor, cursor))
            cursor += timedelta(days=1)
        return plan
    cursor = start
    while cursor <= end:
        bucket_end = min(month_end(cursor), end)
        plan.append((cursor.strftime("%Y-%m"), cursor, bucket_end))
        cursor = bucket_end + timedelta(days=1)
    return plan


# --------------------------------------------------------------------------- #
# HTML receipt
# --------------------------------------------------------------------------- #

RECEIPT_NOTE = "本收据由静默税局生成"

RECEIPT_CSS = """
:root{color-scheme:light;--paper:#f1ebde;--light:#f8f4ea;--ink:#1b1815;
      --muted:#5c5446;--line:#c3b8a1;--green:#2f5b4b;--red:#b3392c;
      --mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
*{box-sizing:border-box}
body{margin:0;padding:24px 14px 40px;background:var(--paper);color:var(--ink);
     font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",
     "Hiragino Sans GB","Microsoft YaHei",sans-serif;line-height:1.5}
.receipt{max-width:560px;margin:0 auto;background:var(--light);border:1px solid var(--ink);
         padding:clamp(12px,4vw,22px);overflow-wrap:anywhere}
.brand{display:flex;flex-wrap:wrap;align-items:baseline;justify-content:space-between;gap:4px 12px;
       border-bottom:1px solid var(--ink);padding-bottom:12px;margin-bottom:14px}
.brand h1{font-size:20px;margin:0;letter-spacing:.06em}
.brand .tag{font:11px var(--mono);letter-spacing:.12em;color:var(--muted)}
.meta{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 14px;font-size:12px;
      color:var(--muted);margin-bottom:16px}
.meta b{color:var(--ink);font:600 13px var(--mono)}
table{width:100%;border-collapse:collapse;font-size:13.5px}
th,td{padding:7px 4px;text-align:left;border-bottom:1px dashed var(--line)}
th{font-size:12px;color:var(--muted);font-weight:500;white-space:nowrap}
.num,.sum .row span:last-child{font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
td.num,th.num{text-align:right}
td:first-child,td:last-child{word-break:break-word}
tr.other td{color:var(--muted)}
.sum{margin-top:14px;padding-top:12px;border-top:1px solid var(--ink);font-size:14px}
.sum .row{display:flex;justify-content:space-between;gap:8px;padding:3px 0}
.sum .total{font-size:18px;font-weight:700}
.sum .negative{color:var(--red)}
.sum .positive{color:var(--green)}
.status{display:inline-block;margin-top:12px;padding:3px 10px;border:1px solid currentColor;
        font-size:12.5px;font-weight:600}
.status.paid{color:var(--green)}
.status.owed{color:var(--red)}
.slogan{margin:0 0 14px;font-size:13px;color:var(--muted)}
.cta{display:block;margin-top:16px;padding:12px 14px;border:1px solid var(--ink);
     background:var(--paper);color:var(--ink);text-decoration:none}
.cta:hover{background:var(--light)}
.cta-k{display:block;font:10px var(--mono);letter-spacing:.14em;color:var(--muted)}
.cta-t{display:block;margin-top:4px;font-size:13.5px;font-weight:600;line-height:1.5}
footer{margin-top:18px;padding-top:12px;border-top:1px dashed var(--line);
       font-size:11.5px;color:var(--muted);text-align:center}
footer time{font-family:var(--mono)}
"""

RECEIPT_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="referrer" content="no-referrer">
<meta name="description" content="打扰税 QuietLedger：把被打扰的时间，记成一张税单。">
<meta property="og:type" content="website">
<meta property="og:title" content="打扰税 · 注意力税单">
<meta property="og:description" content="一天被通知偷走多少秒？打扰税把每条打扰折成秒数，每天出一张税单。">
<meta property="og:url" content="https://ql.130923.xyz">
<title>打扰税收据 · {invoice_id}</title>
<style>{css}</style>
</head>
<body>
<main class="receipt">
  <div class="brand">
    <h1>打扰税 · 注意力日报</h1>
    <span class="tag">QuietLedger</span>
  </div>
  <p class="slogan">把被打扰的时间，记成一张税单。</p>
  <div class="meta">
    <div>票据编号<br><b>{invoice_id}</b></div>
    {account_meta}
    <div>税期 · 北京时间<br><b>{date}</b></div>
    <div>开票时间<br><b><time datetime="{issued_iso}">{issued_at}</time></b></div>
    <div>计价版本<br><b>{tariff_version}</b></div>
    <div>状态<br><b>{status_text}</b></div>
  </div>
  <table>
    <thead>
      <tr><th scope="col">应用</th><th scope="col" class="num">条数</th><th scope="col" class="num">税秒</th><th scope="col">备注</th></tr>
    </thead>
    <tbody>
{rows}
    </tbody>
  </table>
  <div class="sum">
    <div class="row"><span>应缴合计</span><span class="total">{total_duty} 秒</span></div>
    {saved_row}
    <div class="row"><span>每日免税额</span><span>{exemption} 秒</span></div>
    <div class="row"><span>{balance_label}</span><span class="{balance_class}">{balance_abs} 秒</span></div>
  </div>
  <div class="status {status_class}">{status_label}</div>
  <a class="cta" href="https://ql.130923.xyz" target="_blank" rel="noopener">
    <span class="cta-k">打扰税 · QUIETLEDGER</span>
    <span class="cta-t">了解打扰税 · 把被打扰的时间记成一张税单 →</span>
  </a>
  <footer>{note}<br><time datetime="{generated_iso}">{generated_at}</time> 北京时间</footer>
</main>
</body>
</html>
"""


def render_receipt(payload: dict) -> str:
    rows = []
    for line in payload["lines"]:
        css = ' class="other"' if line["app"] == "其他" else ""
        rows.append(
            "<tr{}><td>{}</td><td class=\"num\">{}</td>"
            "<td class=\"num\">{}</td><td>{}</td></tr>".format(
                css,
                html.escape(line["app"]),
                line["count"],
                line["duty"],
                html.escape(line.get("note", "")),
            )
        )
    paid = payload["status"] == "paid"
    nickname = (payload.get("nickname") or "").strip()
    account_meta = (
        "<div>纳税人<br><b>" + html.escape(nickname) + "</b></div>"
        if nickname
        else ""
    )
    balance = payload["balance"]
    balance_class = "positive" if balance > 0 else ("negative" if balance < 0 else "")
    balance_label = "结余" if balance >= 0 else "欠税"
    balance_abs = abs(balance)
    saved = int(payload.get("stats", {}).get("savedSeconds", 0) or 0)
    saved_row = (
        '<div class="row"><span>优惠已减</span>'
        '<span>{} 秒</span></div>'.format(saved)
        if saved > 0
        else ""
    )
    generated_at = iso_now()
    return RECEIPT_HTML.format(
        css=RECEIPT_CSS,
        invoice_id=html.escape(payload["invoiceId"]),
        account_meta=account_meta,
        date=html.escape(payload["date"]),
        issued_iso=html.escape(payload["issuedAt"], quote=True),
        issued_at=html.escape(_fmt_beijing(payload["issuedAt"])),
        tariff_version=html.escape(payload["tariffVersion"]),
        status_text="已结清" if paid else "欠税",
        rows="\n".join(rows),
        total_duty=payload["totalDuty"],
        saved_row=saved_row,
        exemption=payload["exemption"],
        balance_class=balance_class,
        balance_label=balance_label,
        balance_abs=balance_abs,
        status_class="paid" if paid else "owed",
        status_label="已结清 · 应缴在免税额内" if paid else "欠税 · 已超出免税额",
        generated_iso=html.escape(generated_at, quote=True),
        generated_at=html.escape(_fmt_beijing(generated_at)),
        note=RECEIPT_NOTE,
    )


def render_404(receipt_id: str) -> str:
    return (
        "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        "<title>收据不存在 · 打扰税</title><style>"
        "body{margin:0;min-height:100vh;display:flex;align-items:center;"
        "justify-content:center;background:#f3f1ea;color:#1d1c17;"
        "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",\"PingFang SC\","
        "\"Microsoft YaHei\",sans-serif;padding:24px}"
        ".card{max-width:420px;background:#fffdf7;border:1px solid #e3ddcf;"
        "border-radius:14px;padding:28px 24px;text-align:center}"
        "h1{font-size:18px;margin:0 0 8px}p{color:#6b6250;font-size:13.5px;"
        "margin:0;word-break:break-all}</style></head><body><div class=\"card\">"
        "<h1>收据不存在</h1><p>未找到票据 "
        + html.escape(receipt_id)
        + "，请确认分享链接是否完整。</p></div></body></html>"
    )


# --------------------------------------------------------------------------- #
# Public product site (landing page)
#
# One self-contained HTML page: inline CSS, no external assets, no framework.
# The page is served both at ``/`` (https://ql.130923.xyz/) and behind the
# legacy prefix (/quiet-api/).  The proxy may strip
# that prefix before proxying, so the page cannot detect it from self.path:
# every internal link is relative and therefore keeps whichever prefix the
# browser used.
# --------------------------------------------------------------------------- #

SITE_CSS = """
:root{
  --paper:#f1ebde;--light:#f8f4ea;--deep:#e8dfcb;
  --ink:#1b1815;--ink2:#5c5446;--ink3:#6e6455;
  --line:#c3b8a1;--line2:#dbd2be;
  --red:#b3392c;--redbg:#f0dad3;--green:#2f5b4b;--amber:#8a5a00;
  --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,"Liberation Mono",monospace;
  --sans:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
}
*,*::before,*::after{box-sizing:border-box}
html{scroll-behavior:smooth;-webkit-text-size-adjust:100%}
body{margin:0;background:var(--paper);color:var(--ink);font-variant-numeric:tabular-nums;font-family:var(--sans);
  font-size:16px;line-height:1.65;-webkit-font-smoothing:antialiased}
::selection{background:var(--redbg);color:var(--ink)}
a{color:inherit}
:focus-visible{outline:2px solid var(--red);outline-offset:2px}
[id]{scroll-margin-top:84px}
.wrap{width:100%;max-width:1080px;margin:0 auto;padding:0 18px}
@media(min-width:640px){.wrap{padding:0 28px}}
.n{font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
.dim{color:var(--ink3)}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;
  clip:rect(0 0 0 0);white-space:nowrap;border:0}
.skip{position:absolute;left:-9999px;top:0;z-index:50;background:var(--ink);color:var(--light);
  padding:10px 14px;font-size:13px;text-decoration:none}
.skip:focus{left:8px;top:8px}

/* buttons: 0 radius, 0 shadow, 1px border */
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:44px;
  padding:11px 18px;border:1px solid var(--ink);border-radius:0;background:var(--light);
  color:var(--ink);font-family:var(--sans);font-size:14.5px;font-weight:600;line-height:1.2;
  text-decoration:none;cursor:pointer;-webkit-appearance:none;appearance:none;
  transition:background-color .12s ease,color .12s ease,border-color .12s ease}
.btn:hover{background:var(--deep)}
.btn-solid{background:var(--ink);border-color:var(--ink);color:var(--light)}
.btn-solid:hover{background:var(--red);border-color:var(--red);color:#fff}
.btn[aria-disabled=true]{cursor:default;color:var(--ink2);background:var(--deep);border-color:var(--line)}
.btn-ghost{background:transparent}
.btn-ghost:hover{background:var(--deep)}
.btn-sm{min-height:38px;padding:8px 13px;font-size:13px}
.btn .arw{font-family:var(--mono);font-weight:400;font-size:.92em}

/* masthead */
.mast{position:sticky;top:0;z-index:20;background:var(--paper);border-bottom:1px solid var(--ink)}
.mast-in{display:flex;flex-wrap:wrap;align-items:center;gap:10px 16px;padding-top:11px;padding-bottom:11px}
.brand{display:flex;align-items:baseline;gap:8px;text-decoration:none;line-height:1}
.brand b{font-size:19px;font-weight:800;letter-spacing:.06em}
.brand span{font-family:var(--mono);font-size:9.5px;letter-spacing:.2em;color:var(--ink3)}
.mast-nav{order:3;display:flex;gap:16px;width:100%;padding-top:10px;overflow-x:auto;
  border-top:1px dashed var(--line);white-space:nowrap}
.mast-nav a{font-size:12.5px;color:var(--ink2);text-decoration:none;
  border-bottom:1px solid transparent;padding-bottom:1px}
.mast-nav a:hover{color:var(--red);border-bottom-color:var(--red)}
.mast-cta{order:2;margin-left:auto}
@media(min-width:860px){
  .mast-in{flex-wrap:nowrap;padding-top:13px;padding-bottom:13px}
  .mast-nav{order:2;width:auto;padding-top:0;border-top:0;margin-left:auto;gap:22px}
  .mast-cta{order:3;margin-left:0}
}

/* hero */
.hero>*{min-width:0}.hero{display:grid;gap:26px;padding-top:26px;padding-bottom:34px}
.hero-kicker{display:inline-block;margin:0 0 16px;padding:3px 8px;border:1px solid var(--line);
  font-family:var(--mono);font-size:10px;letter-spacing:.14em;color:var(--ink2)}
.hero h1{margin:0 0 16px;font-size:clamp(29px,6.2vw,50px);line-height:1.18;
  font-weight:800;letter-spacing:.005em}
.mark{color:var(--red)}
.hero-sub{margin:0;font-size:15px;line-height:1.75;color:var(--ink2);max-width:32em}
.hero-sub b{color:var(--ink);font-weight:600}
.hero-actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:22px}
.hero-note{margin:12px 0 0;font-family:var(--mono);font-size:12px;line-height:1.6;color:var(--ink2)}
.hero-note a{text-underline-offset:3px}
@media(min-width:900px){
  .hero{grid-template-columns:1.04fr .96fr;gap:46px;padding-top:54px;padding-bottom:58px;align-items:start}
  .hero h1{font-size:50px}
}

/* invoice sample: pure CSS, no images */
.sample{position:relative;min-width:0;margin:0 auto;width:100%;max-width:460px;background:var(--light);
  border:1px solid var(--ink);padding:16px 16px 0;font-size:13px}
.sample-top{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;
  padding-bottom:12px;border-bottom:1px solid var(--ink)}
.sample-kicker{font-family:var(--mono);font-size:9px;letter-spacing:.14em;color:var(--ink3);margin-bottom:3px}
.sample-title{font-size:19px;font-weight:800;line-height:1.25;letter-spacing:.02em}
.sample-badge{font-family:var(--mono);font-size:9.5px;letter-spacing:.12em;color:var(--red);
  border:1px solid var(--red);padding:2px 6px;transform:rotate(2deg);flex:0 0 auto}
.sample-meta{display:grid;grid-template-columns:1fr 1fr;gap:8px 14px;padding:12px 0;
  border-bottom:1px dashed var(--line)}
.sample-meta div{display:flex;flex-direction:column;gap:1px;min-width:0}
.sample-meta span{font-family:var(--mono);font-size:9px;letter-spacing:.08em;color:var(--ink3)}
.sample-meta b{font-family:var(--mono);font-size:11px;font-weight:600;letter-spacing:.01em;
  overflow-wrap:anywhere}
.sample table{width:100%;border-collapse:collapse;font-size:12px}
.sample th{padding:8px 0 4px;font-family:var(--mono);font-size:9px;font-weight:500;
  letter-spacing:.08em;color:var(--ink3);text-align:left;border-bottom:1px solid var(--line2)}
.sample td{padding:6px 0;border-bottom:1px dashed var(--line2);vertical-align:baseline}
.sample .num{text-align:right;font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
.sample .mini{display:block;font-family:var(--mono);font-size:8.5px;color:var(--ink3);letter-spacing:.04em}
.sample-sum{padding:8px 0 0}
.sample-sum .row{display:flex;justify-content:space-between;gap:12px;padding:4px 0;font-size:12.5px}
.sample-sum b{font-family:var(--mono);font-variant-numeric:tabular-nums}
.sample-balance{display:flex;align-items:baseline;justify-content:space-between;gap:12px;
  margin-top:4px;padding:7px 0 2px;border-top:1px solid var(--ink);font-size:13px;font-weight:700}
.sample-balance b{font-family:var(--mono);font-size:21px;font-weight:800;color:var(--green);
  font-variant-numeric:tabular-nums}
.sample-balance.owed b{color:var(--red)}
.sample-caption{margin:0;font-size:10.5px;color:var(--ink3);line-height:1.5}
.sample-foot{position:relative;display:flex;align-items:flex-start;min-height:96px;
  padding:10px 0 8px;border-top:1px dashed var(--line)}
.sample-status{font-family:var(--mono);font-size:9.5px;letter-spacing:.08em;color:var(--green);
  border:1px solid var(--green);padding:2px 7px}
.stamp{position:absolute;right:0;bottom:8px;width:84px;height:84px;display:flex;
  flex-direction:column;align-items:center;justify-content:center;gap:1px;
  border:2px solid rgba(179,57,44,.72);color:rgba(179,57,44,.86);transform:rotate(-8deg);
  font-family:var(--mono);font-size:8px;line-height:1.35;letter-spacing:.1em;text-align:center}
.stamp b{font-size:12.5px;font-weight:700;letter-spacing:.18em}
.stamp i{display:block;width:46px;border-top:1px dashed currentColor;margin:2px 0}
.sample-note{display:flex;flex-wrap:wrap;justify-content:space-between;gap:2px 10px;
  margin:0 -16px;padding:7px 16px;background:var(--deep);border-top:1px solid var(--ink);
  font-family:var(--mono);font-size:8.5px;letter-spacing:.06em;color:var(--ink2)}

/* ledger strip */
.ledger{background:var(--deep);border-top:1px solid var(--ink);border-bottom:1px solid var(--ink)}
.ledger-in{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 22px;
  padding-top:10px;padding-bottom:10px}
.ledger-label{flex:1 1 100%;font-family:var(--mono);font-size:9.5px;
  letter-spacing:.16em;color:var(--ink2)}
.ledger-cell{display:flex;align-items:baseline;gap:5px;min-width:0}
.ledger-cell b{font-size:15px;font-weight:700;line-height:1.3}
.ledger-cell span{font-size:10.5px;color:var(--ink2);white-space:nowrap}
@media(min-width:760px){
  .ledger-in{gap:6px 26px}
  .ledger-label{flex:0 0 auto;margin-right:auto;white-space:nowrap}
  .ledger-cell{padding-left:26px;border-left:1px solid var(--line)}
  .ledger-cell b{font-size:16px}
}

/* section frame */
.sec{padding-top:42px;padding-bottom:42px;border-top:1px solid var(--line)}
@media(min-width:900px){.sec{padding-top:56px;padding-bottom:56px}}
.sec-head{max-width:66ch;margin-bottom:22px}
.kicker{display:block;margin-bottom:8px;font-family:var(--mono);font-size:10px;
  letter-spacing:.16em;color:var(--ink3);text-transform:uppercase}
.sec-head h2{margin:0 0 9px;font-size:clamp(21px,3.4vw,29px);line-height:1.3;
  font-weight:800;letter-spacing:.01em}
.sec-head p{margin:0;font-size:14px;color:var(--ink2)}
.note{margin:14px 0 0;padding:11px 14px;border:1px solid var(--line);background:var(--deep);
  font-size:12.5px;line-height:1.7;color:var(--ink2)}

/* three steps */
.steps{list-style:none;margin:0;padding:0;display:grid;gap:12px}
.step{background:var(--light);border:1px solid var(--line);padding:17px 17px 15px;min-width:0}
.step-n{display:block;margin-bottom:7px;font-family:var(--mono);font-size:10px;
  letter-spacing:.14em;color:var(--red)}
.step h3{margin:0 0 6px;font-size:16px;font-weight:700;line-height:1.4}
.step p{margin:0;font-size:13.5px;color:var(--ink2)}
.step small{display:block;margin-top:10px;padding-top:8px;border-top:1px dashed var(--line2);
  font-family:var(--mono);font-size:9.5px;line-height:1.6;color:var(--ink3);letter-spacing:.02em}
@media(min-width:820px){
  .steps{grid-template-columns:repeat(3,1fr);gap:0}
  .step{border-left:0;padding:20px}
  .step:first-child{border-left:1px solid var(--line)}
}

/* tariff table */
.tariff{width:100%;border-collapse:collapse;border:1px solid var(--line);background:var(--light)}
.tariff th{padding:9px 14px;text-align:left;font-family:var(--mono);font-size:9.5px;
  letter-spacing:.12em;font-weight:500;color:var(--ink3);background:var(--deep);
  border-bottom:1px solid var(--line)}
.tariff th:last-child{text-align:right}
.tariff td{padding:12px 14px;border-bottom:1px dashed var(--line2);vertical-align:top}
.tariff tr:last-child td{border-bottom:0}
.tariff .t-name{font-size:14.5px;font-weight:700}
.tariff .t-name span{display:block;margin-top:2px;font-size:12px;font-weight:400;
  line-height:1.5;color:var(--ink2)}
.tariff .t-val{text-align:right;font-family:var(--mono);font-size:15px;font-weight:700;
  font-variant-numeric:tabular-nums;white-space:nowrap}
.tariff .t-val small{display:block;margin-top:2px;font-family:var(--sans);font-size:10.5px;
  font-weight:400;color:var(--ink3);white-space:normal}

/* feature grid */
.cells{display:grid;grid-template-columns:1fr;background:var(--light);
  border-top:1px solid var(--line);border-left:1px solid var(--line)}
.cell{padding:17px;border-right:1px solid var(--line);border-bottom:1px solid var(--line);min-width:0}
.cell-n{font-family:var(--mono);font-size:10px;letter-spacing:.14em;color:var(--ink3)}
.cell h3{margin:7px 0 5px;font-size:15.5px;font-weight:700;line-height:1.4}
.cell p{margin:0;font-size:13px;line-height:1.65;color:var(--ink2)}
@media(min-width:640px){.cells{grid-template-columns:1fr 1fr}}
@media(min-width:980px){.cells{grid-template-columns:repeat(3,1fr)}}

/* install */
.install{display:grid;gap:24px}
@media(min-width:900px){.install{grid-template-columns:1.08fr .92fr;gap:40px;align-items:start}}
.install-list{list-style:none;margin:0;padding:0;counter-reset:step}
.install-list li{position:relative;margin-bottom:16px;padding:0 0 16px 38px;
  border-bottom:1px dashed var(--line2)}
.install-list li:last-child{margin-bottom:0;padding-bottom:0;border-bottom:0}
.install-list li::before{counter-increment:step;content:counter(step,decimal-leading-zero);
  position:absolute;left:0;top:1px;padding:1px 4px;border:1px solid var(--red);
  font-family:var(--mono);font-size:11px;font-weight:700;line-height:1.4;color:var(--red)}
.install-list h3{margin:0 0 4px;font-size:15.5px;font-weight:700;line-height:1.45}
.install-list p{margin:0;font-size:13.5px;color:var(--ink2)}
.spec{background:var(--light);border:1px solid var(--ink);padding:17px}
@media(min-width:900px){.spec{position:sticky;top:92px}}
.spec-head{padding-bottom:10px;border-bottom:1px solid var(--ink);font-family:var(--mono);
  font-size:9.5px;letter-spacing:.16em;color:var(--ink3)}
.spec dl{margin:0}
.spec .row{display:flex;justify-content:space-between;gap:14px;padding:8px 0;
  border-bottom:1px dashed var(--line2);font-size:12.5px}
.spec dt{flex:0 0 auto;color:var(--ink2)}
.spec dd{margin:0;text-align:right;font-family:var(--mono);font-variant-numeric:tabular-nums;
  font-weight:600;overflow-wrap:anywhere}
.spec-notes{padding:11px 0 0}
.spec-notes h3{margin:0 0 4px;font-family:var(--mono);font-size:9.5px;font-weight:500;
  letter-spacing:.14em;color:var(--ink3)}
.spec-notes p{margin:0;font-size:12.5px;line-height:1.65;color:var(--ink2)}
.sha{margin-top:12px;padding-top:10px;border-top:1px solid var(--line2)}
.sha summary{cursor:pointer;list-style:none;font-size:12px;color:var(--ink2)}
.sha summary::-webkit-details-marker{display:none}
.sha summary::before{content:"+ ";font-family:var(--mono);color:var(--red)}
.sha[open] summary::before{content:"\\2212 "}
.sha code{display:block;margin-top:8px;padding:8px;border:1px dashed var(--line);
  background:var(--paper);font-family:var(--mono);font-size:10px;line-height:1.6;
  color:var(--ink2);overflow-wrap:anywhere}
.copy{margin-top:7px;padding:4px 9px;border:1px solid var(--ink);background:var(--paper);
  font-family:var(--mono);font-size:10px;cursor:pointer;border-radius:0}
.copy:hover{background:var(--ink);color:var(--light)}
.spec-btn{margin-top:14px;width:100%}

/* privacy */
.privacy-grid{display:grid;gap:14px}
.privacy-col{background:var(--light);border:1px solid var(--line);padding:16px}
.privacy-col h3{margin:0 0 9px;font-family:var(--mono);font-size:10px;font-weight:500;
  letter-spacing:.14em;color:var(--ink3)}
.privacy-col ul{margin:0;padding-left:17px;font-size:13px;line-height:1.75;color:var(--ink2)}
.privacy-col li+li{margin-top:5px}
.privacy-col li::marker{color:var(--red)}
@media(min-width:760px){
  .privacy-grid{grid-template-columns:1fr 1fr;gap:0;border:1px solid var(--line)}
  .privacy-col{border:0;padding:20px}
  .privacy-col+.privacy-col{border-left:1px solid var(--line)}
}
.prose{max-width:74ch;margin:14px 0 0;font-size:13.5px;line-height:1.75;color:var(--ink2)}
.prose code{font-family:var(--mono);font-size:11.5px;padding:1px 4px;border:1px solid var(--line2);
  background:var(--light);overflow-wrap:anywhere}

/* faq */
.faq{border-top:1px solid var(--line);margin-top:2px}
.faq details{border-bottom:1px solid var(--line)}
.faq summary{display:flex;align-items:baseline;gap:11px;padding:14px 0;cursor:pointer;
  list-style:none;font-size:14.5px;font-weight:600;line-height:1.55}
.faq summary::-webkit-details-marker{display:none}
.faq summary::before{content:"+";flex:0 0 auto;width:13px;font-family:var(--mono);
  font-size:15px;color:var(--red);line-height:1.2}
.faq details[open] summary::before{content:"\\2212"}
.faq summary:hover{color:var(--red)}
.faq .a{max-width:72ch;padding:0 0 16px 24px;font-size:13px;line-height:1.8;color:var(--ink2)}
.faq .a p{margin:0 0 8px}
.faq .a p:last-child{margin-bottom:0}
.faq .a code{font-family:var(--mono);font-size:11.5px;padding:1px 4px;
  border:1px solid var(--line2);background:var(--light);overflow-wrap:anywhere}

/* footer */
.foot{background:var(--deep);border-top:1px solid var(--ink)}
.foot-in{display:grid;gap:10px;padding-top:18px;padding-bottom:22px}
.foot-main{display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 0;font-size:11.5px;
  line-height:1.7;color:var(--ink2)}
.foot-main>span+span::before{content:"·";margin:0 9px;color:var(--ink3)}
.foot-brand{font-size:13px;font-weight:700;color:var(--ink);letter-spacing:.02em}
.foot-joke{color:var(--red)}
.foot-links{display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 15px;
  font-family:var(--mono);font-size:10.5px;color:var(--ink2)}
.foot-links a{text-decoration:none;border-bottom:1px solid var(--line);padding-bottom:1px}
.foot-links a:hover{color:var(--red);border-bottom-color:var(--red)}
@media(prefers-reduced-motion:reduce){
  html{scroll-behavior:auto}
  .btn{transition:none}
}
"""

SITE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#f1ebde">
<meta name="color-scheme" content="light">
<meta name="description" content="打扰税 QuietLedger：用通知使用权把每天的打扰折算成注意力秒数，按税率表结算成一张纸质税单。Android 8.0 以上，本机记账，北京时间每日结算。">
<title>打扰税 · QuietLedger — 把被打扰的时间，记成一张税单</title>
<style>@@CSS@@</style>
</head>
<body>
<a class="skip" href="#main">跳到正文</a>

<header class="mast" id="top">
  <div class="wrap mast-in">
    <a class="brand" href="#top" aria-label="打扰税首页"><b>打扰税</b><span>QUIETLEDGER</span></a>
    <a class="btn btn-ghost btn-sm mast-cta" href="#install">安装指南</a>
    <nav class="mast-nav" aria-label="页面导航">
      <a href="#how">怎么用</a>
      <a href="#features">功能</a>
      <a href="#data">隐私</a>
      <a href="#faq">常见问题</a>
    </nav>
  </div>
</header>

<main id="main">

<section class="wrap hero">
  <div class="hero-copy">
    <p class="hero-kicker">ANDROID · 注意力账本</p>
    <h1>一天的打扰，<br>一张<span class="mark">注意力税单</span>。</h1>
    <p class="hero-sub">把每天收到的通知折成<b>注意力秒数</b>，看清哪个应用最常打断你，还有多少免税额。</p>
    <div class="hero-actions">
      @@HERO_DOWNLOAD@@
      <a class="btn btn-ghost" href="#receipt-sample">看税单样张</a>
    </div>
    <p class="hero-note">Android 8.0 以上@@APK_RELEASE@@ · <a href="#data">隐私与数据</a></p>
  </div>

  <figure class="sample" id="receipt-sample" aria-label="税单样张">
    <div class="sample-top">
      <div>
        <div class="sample-kicker">ATTENTION DUTY INVOICE · 样张</div>
        <div class="sample-title">注意力税单 · 日报</div>
      </div>
      <span class="sample-badge">SAMPLE</span>
    </div>
    <div class="sample-meta">
      <div><span>票据编号</span><b>INV-@@SAMPLE_DATE_COMPACT@@-4Q7M</b></div>
      <div><span>税期</span><b>@@SAMPLE_DATE@@</b></div>
      <div><span>结算时区</span><b>北京时间 UTC+8</b></div>
      <div><span>计价版本</span><b>@@TARIFF_VERSION@@</b></div>
    </div>
    <table>
      <caption class="sr-only">税单样张明细</caption>
      <thead><tr><th scope="col">税目</th><th scope="col" class="num">条数</th><th scope="col" class="num">税秒</th></tr></thead>
      <tbody>
        @@SAMPLE_ROWS@@
      </tbody>
    </table>
    <div class="sample-sum">
      <div class="row"><span>应缴合计</span><b class="num">@@SAMPLE_DUTY@@ 秒</b></div>
      <div class="row"><span>优惠已减</span><b class="num">@@SAMPLE_SAVED@@ 秒</b></div>
      <div class="row"><span>每日免税额</span><b class="num">@@EXEMPTION@@ 秒</b></div>
    </div>
    <div class="sample-balance @@SAMPLE_TONE@@"><span>@@SAMPLE_BALANCE_LABEL@@</span><b class="num">@@SAMPLE_BALANCE@@ 秒</b></div>
    <p class="sample-caption">税目与应缴均为折后金额。</p>
    <div class="sample-foot">
      <span class="sample-status">状态 · @@SAMPLE_STATUS@@</span>
      <span class="stamp" aria-hidden="true"><b>@@SAMPLE_STATUS@@</b><i></i><span>@@SAMPLE_DATE@@</span><span>QUIETLEDGER</span></span>
    </div>
    <div class="sample-note"><span>本单不作报销凭证</span><span>SEC · QUIETLEDGER</span></div>
  </figure>
</section>

@@LEDGER@@

<section class="wrap sec" id="how">
  <div class="sec-head">
    <span class="kicker">01 / HOW IT WORKS</span>
    <h2>从一条通知到一张税单</h2>
    <p>通知监听连接后，打开其他应用时也能记账。</p>
  </div>
  <ol class="steps">
    <li class="step">
      <span class="step-n">STEP 01</span>
      <h3>开启通知使用权</h3>
      <p>在系统设置里允许打扰税读取通知。开启后自动记账，不用手动打卡。</p>
      <small>设置 → 通知使用权 → 打扰税 → 允许</small>
    </li>
    <li class="step">
      <span class="step-n">STEP 02</span>
      <h3>每天自动结算</h3>
      <p>按北京时间分日，联网后同步结算。默认每天 @@EXEMPTION@@ 秒免税额，超出记欠税。单价和免税额可在「设置 → 计税规则」调整。</p>
      <small>免税额每天重置，不累计、不结转</small>
    </li>
    <li class="step">
      <span class="step-n">STEP 03</span>
      <h3>看票据和逐日报表</h3>
      <p>首页看今天的应缴与结余，报表可以看 7 天 / 30 天 / 月 / 季 / 年，点日期看当天明细。</p>
      <small>收据链接可以直接发给朋友</small>
    </li>
  </ol>
</section>

<section class="wrap sec" id="tariff">
  <div class="sec-head">
    <span class="kicker">02 / TARIFF @@TARIFF_VERSION@@</span>
    <h2>税率表：把规则写在明面上</h2>
    <p>每条通知单独计价：先乘会话或应用权重，再算夜间、群发、勿扰与连发，封顶后向上取整到秒，最后用当天免税额抵扣。</p>
  </div>
  <table class="tariff">
    <thead><tr><th scope="col">规则</th><th scope="col">取值</th></tr></thead>
    <tbody>
      <tr><td class="t-name">单价<span>每条通知的基础税秒（可改 2–30）</span></td><td class="t-val">@@UNIT_SECONDS@@ 秒<small>每条</small></td></tr>
      <tr><td class="t-name">夜间加成<span>@@NIGHT_START@@:00–次日 @@NIGHT_END_HH@@ 之间到达</span></td><td class="t-val">×@@NIGHT_MULT@@<small>按北京时间</small></td></tr>
      <tr><td class="t-name">群发加成<span>@所有人、@全体成员、@all、@everyone</span></td><td class="t-val">×@@NOTICE_MULT@@<small>一条提醒惊动所有人</small></td></tr>
      <tr><td class="t-name">勿扰折扣<span>到达时手机处于免打扰，或用过「静默 1 小时」</span></td><td class="t-val">×@@DND_MULT@@<small>没打断，减半</small></td></tr>
      <tr><td class="t-name">连发折扣<span>同一应用、同一类别 @@BURST_WINDOW@@ 秒内又来一条</span></td><td class="t-val">×@@BURST_MULT@@<small>从第二条起，是折扣不是加价</small></td></tr>
      <tr><td class="t-name">会话分类<span>工作 ×1；家人、朋友 ×0.25；闲聊群 ×0.1；不计打扰 0 秒</span></td><td class="t-val">自选<small>用会话权重替代应用权重</small></td></tr>
      <tr><td class="t-name">单条封顶<span>无论命中多少条加成，一条最多这么多</span></td><td class="t-val">×@@CAP_FACTOR@@<small>单价的三倍</small></td></tr>
      <tr><td class="t-name">聊天应用<span>微信、QQ、TIM</span></td><td class="t-val">×@@CHAT_MULT@@<small>一次打断，不是一条消息</small></td></tr>
      <tr><td class="t-name">办公应用<span>企业微信、钉钉、飞书、Teams、Slack</span></td><td class="t-val">×@@OFFICE_MULT@@<small>按包名权重</small></td></tr>
      <tr><td class="t-name">每日免税额<span>每天 0 点重置，不结转（可改 0–1800）</span></td><td class="t-val">@@EXEMPTION@@ 秒<small>先抵扣，再算欠税</small></td></tr>
      <tr><td class="t-name">结算时区<span>跨时区也按这个时间结</span></td><td class="t-val">北京时间<small>UTC+8 · 每日 00:00</small></td></tr>
    </tbody>
  </table>
  <p class="note">所有系数一次乘完，只在最后向上取整一次。举例：普通应用夜间连发 @@UNIT_SECONDS@@ × @@NIGHT_MULT@@ × @@BURST_MULT@@ = 4.8 → @@COMBINED_DUTY@@ 秒；微信夜间连发 @@UNIT_SECONDS@@ × @@CHAT_MULT@@ × @@NIGHT_MULT@@ × @@BURST_MULT@@ = 2.4 → @@WECHAT_COMBINED@@ 秒；群里 @所有人 @@UNIT_SECONDS@@ × @@NOTICE_MULT@@ → @@NOTICE_DUTY@@ 秒；免打扰中到达 @@UNIT_SECONDS@@ × @@DND_MULT@@ → @@DND_DUTY@@ 秒；单条最多 @@UNIT_SECONDS@@ × @@CAP_FACTOR@@ → @@CAP_DUTY@@ 秒。所有金额都以「秒」计，没有充值，没有内购；单价和免税额可以按账户自定义，所有设备共用一套，改完当天就按新规则结算。</p>
</section>

<section class="wrap sec" id="features">
  <div class="sec-head">
    <span class="kicker">03 / WHAT YOU GET</span>
    <h2>你会拿到的六样东西</h2>
    <p>没有积分，没有排行榜，只有账和票。</p>
  </div>
  <div class="cells">
    <div class="cell"><span class="cell-n">01</span><h3>通知计税</h3><p>每条通知按应用权重、夜间加成、群发加成、勿扰折扣与连发折扣折成税秒；系统噪音直接跳过。</p></div>
    <div class="cell"><span class="cell-n">02</span><h3>每日税单</h3><p>一天一张，列清每个应用多少条、多少秒、应缴多少。</p></div>
    <div class="cell"><span class="cell-n">03</span><h3>周报按天下钻</h3><p>一周的趋势和每天的税额，点某一天能看当天明细。</p></div>
    <div class="cell"><span class="cell-n">04</span><h3>桌面组件</h3><p>把今天的税额和优惠省下的秒数贴在桌面上，抬手就能看见，不用先开 App。</p></div>
    <div class="cell"><span class="cell-n">05</span><h3>静默 1 小时磁贴</h3><p>下拉快捷设置点一下，一小时不响；静默期间照常记账。</p></div>
    <div class="cell"><span class="cell-n">06</span><h3>公开收据</h3><p>把某天的税单生成网页链接，发给谁都能看，不用登录。</p></div>
  </div>
</section>

<section class="wrap sec" id="install">
  <div class="sec-head">
    <span class="kicker">04 / INSTALL</span>
    <h2>安装与授权</h2>
    <p>记账需要通知使用权。日报和一键静默各有独立授权。</p>
  </div>
  <div class="install">
    <ol class="install-list">
      <li>
        <h3>下载 APK</h3>
        <p>从本页下载 quietledger.apk，打开下载完成的文件。</p>
      </li>
      <li>
        <h3>允许安装未知应用</h3>
        <p>系统拦截时，按提示允许当前浏览器或文件管理器安装应用。</p>
      </li>
      <li>
        <h3>开启通知使用权</h3>
        <p>打开打扰税，按引导授予通知使用权。返回 App，看到「监听已连接」就可以记账。</p>
      </li>
      <li>
        <h3>允许通知（可选）</h3>
        <p>想在通知栏收到日报，就开启通知权限。Android 13 以上会单独询问。</p>
      </li>
      <li>
        <h3>加电池白名单（建议）</h3>
        <p>监听反复断开时，打开「设置 → 通知与打扰 → 后台常驻」，按设备提示允许后台运行或自启动。</p>
      </li>
      <li>
        <h3>核对第一笔账</h3>
        <p>收到一条通知后，打开「明细」。找不到记录时，点右上角「核对」查看监听状态和近期跳过原因。</p>
      </li>
    </ol>
    <aside class="spec" aria-label="安装包信息">
      <div class="spec-head">PACKAGE / 安装包</div>
      <dl>
        <div class="row"><dt>可下载版本</dt><dd>@@APK_VERSION@@</dd></div>
        <div class="row"><dt>APK 大小</dt><dd>@@APK_SIZE@@</dd></div>
        <div class="row"><dt>发布时间</dt><dd>@@APK_PUBLISHED@@</dd></div>
        <div class="row"><dt>最低系统</dt><dd>Android 8.0+</dd></div>
      </dl>
      <div class="spec-notes">
        <h3>更新说明</h3>
        <p>@@APK_NOTES@@</p>
      </div>
      @@APK_SHA_BLOCK@@
      @@INSTALL_DOWNLOAD@@
    </aside>
  </div>
</section>

<section class="wrap sec" id="data">
  <div class="sec-head">
    <span class="kicker">05 / DATA &amp; PRIVACY</span>
    <h2>数据在哪，谁能看见</h2>
    <p>通知先记在手机，联网后自动同步结算。默认只上送应用、时间、条数。</p>
  </div>
  <div class="privacy-grid">
    <div class="privacy-col">
      <h3>会同步的记录</h3>
      <ul>
        <li>通知来自哪个应用：包名与应用名</li>
        <li>到达时间，以及分类、优先级、是否常驻和可清除</li>
        <li>是否处于免打扰、是否群发提醒（只上传「是/否」）</li>
        <li>会话计税类别；联系人、群名和会话规则留在本机。开启上送通知原文时，通知原文仍可能包含名称</li>
        <li>通知标题与正文默认留在本机，开启「上送通知原文」后才同步</li>
      </ul>
    </div>
    <div class="privacy-col">
      <h3>设备与第三方</h3>
      <ul>
        <li>通知核对的近期跳过样本留在本机</li>
        <li>图片和附件不读取、不保存</li>
        <li>应用未接入第三方统计或广告服务</li>
      </ul>
    </div>
  </div>
  <p class="prose">官网安装包通过 HTTPS 同步。联网时从明细删除记录，App 会请求同步删除并重算账单。在「设置 → 同步」关掉云端同步后，记账、票据、报表和日报仍能在本机使用。</p>
  <p class="prose">持链接者都可打开公开收据，看到应用名、条数、税秒和你填写的昵称。公开票面省略账户码和通知内容。</p>
</section>

<section class="wrap sec" id="faq">
  <div class="sec-head">
    <span class="kicker">06 / FAQ</span>
    <h2>常见问题</h2>
    <p>先看这几条，大部分「不记账」都能自己解决。</p>
  </div>
  <div class="faq">
    <details>
      <summary>为什么一条通知都没记上？</summary>
      <div class="a">
        <p>打开「明细 → 核对」，先看监听状态。未授权就去开启通知使用权，已断开就点「修复」。已连接时，可以查看最近被跳过的通知和原因。</p>
      </div>
    </details>
    <details>
      <summary>有些通知被跳过了，是怎么回事？</summary>
      <div class="a">
        <p>系统包、常驻通知、分组摘要、静默通道、进度条这类噪音不计税；同一个应用在 120 秒内刷屏的，只留第一条；同一条内容在 60 秒内重复，也不会重复计费。</p>
        <p>打开「明细 → 核对」可看近期样本及跳过原因。</p>
      </div>
    </details>
    <details>
      <summary>换手机或重装，怎么接回原来的账户？</summary>
      <div class="a">
        <p>在原设备打开「设置 → 账户与设备」，生成并复制邀请码，再到新设备输入。邀请码 10 分钟内可用一次。加入后，两台设备共用账户税则与结算后的账单。</p>
      </div>
    </details>
    <details>
      <summary>数据存在哪里？</summary>
      <div class="a">
        <p>通知先保存在本机。联网结算时，同步过的记录与票据保存在打扰税的账本里；通知标题与正文默认不上送。<a href="#data">查看同步字段</a>。</p>
        <p>不想联网也能用：「设置 → 同步」关掉云端同步后，记账、票据、周报和日报都只留在本机。</p>
      </div>
    </details>
    <details>
      <summary>收据链接谁能看？</summary>
      <div class="a">
        <p>公开收据无需登录。拿到链接的人都能查看应用汇总、税额与已填写的昵称。</p>
      </div>
    </details>
    <details>
      <summary>提示「监听已断开」怎么办？</summary>
      <div class="a">
        <p>点提示里的「修复」→「立即重连」，App 会向系统重新申请绑定；失败会按 15/30/60/180 秒退避重试，不用一直盯着。刚启动或刚重连的 20 秒内不会误报断开。</p>
        <p>弹层里还有四步：立即重连、加入电池白名单、允许自启动、重新开一次通知使用权。前两步通常就能救回来。</p>
      </div>
    </details>
    <details>
      <summary>税率能自己改吗？</summary>
      <div class="a">
        <p>能。设置 → 计税规则：单价 2–30 秒、每日免税额 0–1800 秒，加减后「应用并结算」。同一个账户的所有设备共用一套税则。</p>
        <p>离线时先按本机设置估算，联网后自动结算；改回默认也是一次设置。</p>
      </div>
    </details>
    <details>
      <summary>免打扰和群里 @所有人 怎么算？</summary>
      <div class="a">
        <p>通知到达时手机处于免打扰（系统免打扰，或你刚点过「静默 1 小时」），这条按半价计：没打断你，少收一半。</p>
        <p>@所有人、@全体成员、@all、@everyone 这类群发提醒按两倍计：一条提醒惊动所有人，不该和私聊同价。单条最多按单价的三倍封顶，加成不会无限叠。</p>
      </div>
    </details>
    <details>
      <summary>为什么微信、钉钉算得便宜？</summary>
      <div class="a">
        <p>聊天应用按 0.5、办公应用按 0.4 的权重计单价：它们的一次提醒往往只是「有人在群里说话」，不是一次独立打断。</p>
        <p>连发也不再叠加罚款：同一个应用 120 秒内又来一条，第二条起按 0.4 折扣计费。票据里的「优惠已减 N 秒」是连发、免打扰和封顶省下的全部秒数。</p>
      </div>
    </details>
  </div>
</section>

</main>

<footer class="foot">
  <div class="wrap foot-in">
    <div class="foot-main">
      <span class="foot-brand">打扰税 · QUIETLEDGER</span>
      <span>服务 v@@SERVICE_VERSION@@</span>
      <span>北京时间 @@BEIJING_NOW@@</span>
      <span class="foot-joke">本单不作报销凭证</span>
    </div>
    <div class="foot-links">
      <a href="@@SITE_PREFIX@@health">服务状态</a>
      <a href="@@SITE_PREFIX@@v1/app/latest">版本清单</a>
      <span>税率表 @@TARIFF_VERSION@@</span>
    </div>
  </div>
</footer>

<script>
(function () {
  var nodes = document.querySelectorAll("[data-copy]");
  for (var i = 0; i < nodes.length; i++) {
    nodes[i].addEventListener("click", function () {
      var self = this;
      var text = self.getAttribute("data-copy") || "";
      if (!text) { return; }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          self.textContent = "已复制";
          setTimeout(function () { self.textContent = "复制"; }, 1600);
        }, function () { self.textContent = "复制失败"; });
      } else {
        self.textContent = "请手动选择";
      }
    });
  }
})();
</script>
</body>
</html>
"""


def _fmt_bytes(size: int) -> str:
    """Bytes -> short human readable label; empty string when unknown."""
    if not size or size <= 0:
        return ""
    if size >= 1024 * 1024:
        return "%.1f MB" % (size / (1024.0 * 1024.0))
    if size >= 1024:
        return "%.0f KB" % (size / 1024.0)
    return "%d B" % size


def _fmt_beijing(iso_text: str) -> str:
    """ISO-8601 instant -> 'YYYY-MM-DD HH:MM' in Asia/Shanghai; raw text if unparsable."""
    raw = (iso_text or "").strip()
    if not raw:
        return ""
    try:
        moment = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return raw
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(TZ).strftime("%Y-%m-%d %H:%M")


def _beijing_now_text() -> str:
    return datetime.now(TZ).strftime("%Y-%m-%d %H:%M")


def _esc_multiline(text: str) -> str:
    escaped = html.escape(text, quote=True).replace("\r\n", "\n")
    return escaped.replace("\n", "<br>")


def _site_stats():
    """Aggregate counters for the landing page; None when the query fails."""
    try:
        with db() as conn:
            row = conn.execute(
                "SELECT (SELECT COUNT(*) FROM accounts), "
                "(SELECT COUNT(*) FROM events), "
                "(SELECT COUNT(*) FROM invoices)"
            ).fetchone()
        if row is None:
            return None
        return {
            "accounts": int(row[0] or 0),
            "events": int(row[1] or 0),
            "invoices": int(row[2] or 0),
        }
    except (sqlite3.Error, OSError):
        return None


def _render_ledger(stats) -> str:
    """Optional 'ledger now' strip.  Hidden entirely when stats are unavailable."""
    if not stats:
        return ""
    cells = (
        ("账户", stats["accounts"]),
        ("已记账条数", stats["events"]),
        ("已开票数", stats["invoices"]),
    )
    body = "".join(
        '<div class="ledger-cell"><b class="n">%s</b><span>%s</span></div>'
        % ("{:,}".format(value), label)
        for label, value in cells
    )
    return (
        '<section class="ledger" aria-label="此刻的账本"><div class="wrap ledger-in">'
        '<span class="ledger-label">此刻的账本 / LEDGER NOW</span>' + body +
        "</div></section>"
    )


def _sample_invoice(chat_multiplier: float, office_multiplier: float) -> dict:
    """Landing-page sample rows, priced with the v2026-11-c formula.

    Rows are aggregated per tax item; the site only needs to be consistent with
    the real rule, so each item carries one effective multiplier (app weight,
    night, notice or dnd) and an optional burst count.
    """
    items = (
        ("即时通讯", "微信 ×%g · 6 条连发" % chat_multiplier, 12, 6, chat_multiplier),
        ("办公协作", "应用权重 ×%g" % office_multiplier, 8, 0, office_multiplier),
        ("群聊提醒", "群发 ×%g" % NOTICE_MULTIPLIER, 1, 0, NOTICE_MULTIPLIER),
        ("电商推送", "勿扰中 ×%g" % DND_MULTIPLIER, 2, 0, DND_MULTIPLIER),
        ("夜间消息", "夜间 ×%g" % NIGHT_MULTIPLIER, 2, 0, NIGHT_MULTIPLIER),
    )
    rows = []
    total_duty = 0
    saved_seconds = 0
    for label, note, count, bursts, multiplier in items:
        full = duty_ceil(UNIT_SECONDS * multiplier)
        discounted = duty_ceil(UNIT_SECONDS * multiplier * BURST_MULTIPLIER)
        duty = (count - bursts) * full + bursts * discounted
        total_duty += duty
        saved_seconds += bursts * (full - discounted)
        note_html = '<span class="mini">' + html.escape(note) + "</span>" if note else ""
        rows.append(
            f'<tr><td>{html.escape(label)}{note_html}</td>'
            f'<td class="num">{count}</td><td class="num">{duty}</td></tr>'
        )
    balance = EXEMPTION_PER_DAY - total_duty
    owed = balance < 0
    return {
        "SAMPLE_ROWS": "\n".join(rows),
        "SAMPLE_DUTY": str(total_duty),
        "SAMPLE_SAVED": str(saved_seconds),
        "SAMPLE_BALANCE": str(abs(balance)),
        "SAMPLE_BALANCE_LABEL": "欠税" if owed else "结余",
        "SAMPLE_STATUS": "欠税" if owed else "已结清",
        "SAMPLE_TONE": "owed" if owed else "",
    }


def _render_home(manifest: dict, stats, prefix: str = "") -> str:
    """Render the public landing page from the live manifest + counters."""
    version = html.escape(str(manifest.get("versionName") or APP_FALLBACK_VERSION_NAME))
    size_text = _fmt_bytes(int(manifest.get("apkSize") or 0))
    size_label = html.escape(size_text or "大小以实际下载为准")
    published = html.escape(_fmt_beijing(str(manifest.get("publishedAt") or "")) or "未知")
    notes = str(manifest.get("notes") or "").strip()
    notes_html = (
        _esc_multiline(notes)
        if notes
        else '<span class="dim">本次更新没有附说明。</span>'
    )
    sha = str(manifest.get("sha256") or "").strip()
    available = bool(size_text and sha)
    if available:
        sha_safe = html.escape(sha, quote=True)
        sha_block = (
            '<details class="sha"><summary>SHA-256 校验值</summary>'
            "<code>" + sha_safe + "</code>"
            '<button class="copy" type="button" data-copy="' + sha_safe + '">复制</button>'
            "</details>"
        )
    else:
        sha_block = ""

    def download_button(extra_class: str = "") -> str:
        if not available:
            return f'<span class="btn{extra_class}" aria-disabled="true">安装包暂不可用</span>'
        path = prefix + "apk/" + APK_FILE_NAME
        return (
            f'<a class="btn btn-solid{extra_class}" href="{path}">'
            '下载 APK <span class="arw" aria-hidden="true">&#8595;</span></a>'
        )

    yesterday = datetime.now(TZ) - timedelta(days=1)
    chat_multiplier = APP_MULTIPLIERS["com.tencent.mm"]
    office_multiplier = APP_MULTIPLIERS["com.tencent.wework"]
    combined_duty = duty_ceil(UNIT_SECONDS * NIGHT_MULTIPLIER * BURST_MULTIPLIER)
    wechat_combined = duty_ceil(
        UNIT_SECONDS * chat_multiplier * NIGHT_MULTIPLIER * BURST_MULTIPLIER
    )
    notice_duty = duty_ceil(UNIT_SECONDS * NOTICE_MULTIPLIER)
    dnd_duty = duty_ceil(UNIT_SECONDS * DND_MULTIPLIER)
    cap_duty = duty_ceil(UNIT_SECONDS * CAP_FACTOR)

    values = {
        "CSS": SITE_CSS,
        "APK_VERSION": "v" + version if available else "暂不可用",
        "APK_SIZE": size_label if available else "—",
        "APK_RELEASE": " · v" + version + " · " + html.escape(size_text) if available else "",
        "APK_PUBLISHED": published if available else "—",
        "HERO_DOWNLOAD": download_button(),
        "INSTALL_DOWNLOAD": download_button(" spec-btn"),
        "SITE_PREFIX": prefix,
        "APK_NOTES": notes_html,
        "APK_SHA_BLOCK": sha_block,
        "TARIFF_VERSION": html.escape(TARIFF_VERSION),
        "UNIT_SECONDS": html.escape(str(UNIT_SECONDS)),
        "UNIT_MIN": html.escape(str(UNIT_SECONDS_MIN)),
        "UNIT_MAX": html.escape(str(UNIT_SECONDS_MAX)),
        "NIGHT_MULT": html.escape("%g" % NIGHT_MULTIPLIER),
        "NIGHT_START": html.escape(str(NIGHT_START_HOUR)),
        "NIGHT_END": html.escape(str(NIGHT_END_HOUR)),
        "NIGHT_END_HH": html.escape("%02d:00" % NIGHT_END_HOUR),
        "BURST_MULT": html.escape("%g" % BURST_MULTIPLIER),
        "BURST_WINDOW": html.escape(str(BURST_WINDOW_SEC)),
        "DND_MULT": html.escape("%g" % DND_MULTIPLIER),
        "NOTICE_MULT": html.escape("%g" % NOTICE_MULTIPLIER),
        "CAP_FACTOR": html.escape("%g" % CAP_FACTOR),
        "CHAT_MULT": html.escape("%g" % chat_multiplier),
        "OFFICE_MULT": html.escape("%g" % office_multiplier),
        "EXEMPTION": html.escape(str(EXEMPTION_PER_DAY)),
        "EXEMPTION_MIN": html.escape(str(EXEMPTION_MIN)),
        "EXEMPTION_MAX": html.escape(str(EXEMPTION_MAX)),
        "COMBINED_DUTY": html.escape(str(combined_duty)),
        "WECHAT_COMBINED": html.escape(str(wechat_combined)),
        "NOTICE_DUTY": html.escape(str(notice_duty)),
        "DND_DUTY": html.escape(str(dnd_duty)),
        "CAP_DUTY": html.escape(str(cap_duty)),
        "SERVICE_VERSION": html.escape(VERSION),
        "BEIJING_NOW": html.escape(_beijing_now_text()),
        "SAMPLE_DATE": yesterday.strftime("%Y-%m-%d"),
        "SAMPLE_DATE_COMPACT": yesterday.strftime("%Y%m%d"),
        "LEDGER": _render_ledger(stats),
        **_sample_invoice(chat_multiplier, office_multiplier),
    }
    page = SITE_HTML.replace("@@CSS@@", SITE_CSS)
    for key, value in values.items():
        if key == "CSS":
            continue
        page = page.replace("@@" + key + "@@", value)
    return page


# --------------------------------------------------------------------------- #
# HTTP API
# --------------------------------------------------------------------------- #


class AccountRateLimit:
    """Bound account requests per address and limit total work."""

    def __init__(self, per_address: int, total_limit: int):
        self.per_address = per_address
        self.total_limit = total_limit
        self.minute = -1
        self.total = 0
        self.by_address = {}
        self.lock = threading.Lock()

    def check(self, address: str):
        with self.lock:
            minute = int(time.monotonic() // 60)
            if minute != self.minute:
                self.minute = minute
                self.total = 0
                self.by_address.clear()
            attempts = self.by_address.get(address, 0)
            if attempts >= self.per_address or self.total >= self.total_limit:
                raise ApiError(429, "rate_limited", "尝试过多，请一分钟后再试")
            self.by_address[address] = attempts + 1
            self.total += 1


SIGNUP_LIMIT = AccountRateLimit(20, 120)
JOIN_LIMIT = AccountRateLimit(20, 120)


class ApiHandler(BaseHTTPRequestHandler):
    server_version = f"{SERVICE_NAME}/{VERSION}"
    protocol_version = "HTTP/1.1"

    # -- plumbing -----------------------------------------------------------

    def log_message(self, fmt, *args):  # noqa: A003 - stdlib signature
        message = re.sub(r"(/r/)[^\s?\"]+", r"\1[redacted]", fmt % args)
        sys.stdout.write("%s %s\n" % (self.log_date_time_string(), message))
        sys.stdout.flush()

    def _send(self, status: int, body: bytes, content_type: str):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Device-Id")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_json(self, status: int, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")

    def send_html(self, status: int, text: str):
        self._send(status, text.encode("utf-8"), "text/html; charset=utf-8")

    def send_file(self, file_path: Path, content_type: str):
        try:
            size = file_path.stat().st_size
        except OSError:
            raise ApiError(404, "not_found", "file not found")
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if self.command == "HEAD":
            return
        with file_path.open("rb") as fh:
            while True:
                chunk = fh.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def send_error_json(self, status: int, code: str, message: str):
        self.send_json(status, {"error": code, "message": message})

    def read_json_body(self) -> dict:
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length or 0)
        except ValueError:
            # Keep-alive cannot be trusted after a malformed length.
            self.close_connection = True
            raise ApiError(400, "bad_request", "invalid Content-Length")
        if length <= 0:
            raise ApiError(400, "bad_request", "request body required")
        if length > MAX_BODY_BYTES:
            self.close_connection = True
            raise ApiError(413, "payload_too_large", "request body too large")
        raw = self.rfile.read(length)
        self._body_consumed = True
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ApiError(400, "bad_request", "invalid JSON body")
        if not isinstance(data, dict):
            raise ApiError(400, "bad_request", "JSON object required")
        return data

    def _drain_request_body(self):
        """Consume an unread request body so HTTP/1.1 keep-alive stays in sync.

        Auth / routing errors are raised before the handler reads the body.  The
        unread bytes would otherwise be parsed as the *next* request line on the
        same connection -- Caddy pools upstream connections, so one unauthenticated
        POST used to poison the next request (observed as
        ``501 Unsupported method ('{"unitSeconds":...}POST')``).
        """
        if getattr(self, "_body_consumed", False):
            return
        self._body_consumed = True
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self.close_connection = True
            return
        if length <= 0:
            return
        if length > MAX_BODY_BYTES:
            self.close_connection = True
            return
        try:
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(remaining, 65536))
                if not chunk:
                    break
                remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def header_device_id(self) -> str:
        return as_str(self.headers.get("X-Device-Id"), 128)

    def account_request_address(self) -> str:
        peer = self.client_address[0]
        if REQUIRE_HTTPS and peer in ("127.0.0.1", "::1"):
            forwarded = self.headers.get("X-Forwarded-For", "").rsplit(",", 1)[-1].strip()
            try:
                address = ipaddress.ip_address(forwarded)
                if address.version == 6:
                    return str(ipaddress.ip_network((address, 64), strict=False))
                return str(address)
            except ValueError:
                pass
        return peer

    def authenticate(self) -> dict:
        header = self.headers.get("Authorization") or ""
        if not header.startswith("Bearer "):
            raise ApiError(401, "unauthorized", "missing bearer token")
        token = header[len("Bearer "):].strip()
        if len(token) < 16:
            raise ApiError(401, "unauthorized", "invalid token")
        token_hash = sha256_hex(token)
        with db() as conn:
            row = conn.execute(
                "SELECT a.id, a.account_code, a.tariff_version "
                "FROM tokens t JOIN accounts a ON a.id = t.account_id "
                "WHERE t.token_hash = ?",
                (token_hash,),
            ).fetchone()
            if row is None:
                raise ApiError(401, "unauthorized", "invalid token")
            conn.execute(
                "UPDATE tokens SET last_used_at = ? WHERE token_hash = ?",
                (now_ms(), token_hash),
            )
            device_id = self.header_device_id()
            if device_id:
                conn.execute(
                    "UPDATE devices SET last_seen_at = ? "
                    "WHERE account_id = ? AND device_id = ?",
                    (now_ms(), row["id"], device_id),
                )
        return {"id": row["id"], "account_code": row["account_code"],
                "tariff_version": row["tariff_version"]}

    # -- dispatch -----------------------------------------------------------

    def do_OPTIONS(self):  # noqa: N802 - stdlib naming
        self._send(204, b"", "text/plain; charset=utf-8")

    def do_GET(self):  # noqa: N802
        self._dispatch("GET")

    def do_POST(self):  # noqa: N802
        self._dispatch("POST")

    def _dispatch(self, method: str):
        self._body_consumed = False
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            query = parse_qs(parsed.query)
            # Tolerate a proxy that does not strip the public prefix.
            if path == "/quiet-api":
                path = "/"
            elif path.startswith("/quiet-api/"):
                path = path[len("/quiet-api"):]
            if len(path) > 1 and path.endswith("/"):
                path = path.rstrip("/")

            public_health = method == "GET" and path in ("/", "/health")
            if REQUIRE_HTTPS and not public_health:
                proxy_is_local = self.client_address[0] in ("127.0.0.1", "::1")
                if not proxy_is_local or self.headers.get("X-Forwarded-Proto") != "https":
                    self.close_connection = True
                    return self.send_error_json(403, "https_required", "use HTTPS")

            if method == "GET":
                self._route_get(path, query)
            elif method == "POST":
                self._route_post(path, query)
            else:
                raise ApiError(405, "method_not_allowed", "method not allowed")
        except ApiError as exc:
            self._drain_request_body()
            self.send_error_json(exc.status, exc.code, exc.message)
        except (BrokenPipeError, ConnectionResetError):
            log.warning("client disconnected")
        except Exception:  # noqa: BLE001 - top-level guard, never leak 500 HTML
            log.error("unhandled error: %s", traceback.format_exc())
            self._drain_request_body()
            try:
                self.send_error_json(500, "internal_error", "internal server error")
            except (BrokenPipeError, ConnectionResetError):
                pass

    def _route_get(self, path: str, query: dict):
        if path in ("", "/"):
            return self.handle_root()
        if path == "/health":
            return self.handle_health()
        if path == "/v1/tariff":
            account = self.authenticate()
            return self.handle_tariff(account)
        if path == "/v1/invoice":
            account = self.authenticate()
            return self.handle_invoice(account, query)
        if path == "/v1/weekly":
            account = self.authenticate()
            return self.handle_weekly(account, query)
        if path == "/v1/report":
            account = self.authenticate()
            return self.handle_report(account, query)
        if path == "/v1/devices":
            account = self.authenticate()
            return self.handle_devices(account)
        if path == "/v1/profile":
            raise ApiError(405, "method_not_allowed", "use POST /v1/profile")
        if path == "/v1/settings":
            raise ApiError(405, "method_not_allowed", "use POST /v1/settings")
        if path == "/v1/meta":
            account = self.authenticate()
            return self.handle_meta(account)
        if path.startswith("/r/"):
            return self.handle_receipt(path[len("/r/"):])
        if path == "/v1/app/latest":
            return self.handle_app_latest()
        if path.startswith("/apk/"):
            return self.handle_apk_download(path[len("/apk/"):])
        raise ApiError(404, "not_found", "route not found")

    def _route_post(self, path: str, query: dict):
        if path == "/v1/accounts":
            return self.handle_create_account()
        if path == "/v1/accounts/join":
            return self.handle_join_account()
        if path == "/v1/accounts/invite":
            return self.handle_issue_invite(self.authenticate())
        if path == "/v1/receipt-shares":
            return self.handle_share_receipt(self.authenticate())
        if path == "/v1/report":
            raise ApiError(405, "method_not_allowed", "use GET /v1/report")
        if path == "/v1/events":
            account = self.authenticate()
            return self.handle_events(account)
        if path == "/v1/events/purge":
            account = self.authenticate()
            return self.handle_purge_events(account)
        if path == "/v1/profile":
            account = self.authenticate()
            return self.handle_profile(account)
        if path == "/v1/settings":
            account = self.authenticate()
            return self.handle_settings(account)
        raise ApiError(404, "not_found", "route not found")

    # -- endpoints ----------------------------------------------------------

    def handle_health(self):
        db_status = "ok"
        try:
            with db() as conn:
                conn.execute("SELECT 1").fetchone()
        except sqlite3.Error:
            db_status = "error"
        self.send_json(200, {
            "status": "ok",
            "service": SERVICE_NAME,
            "version": VERSION,
            "time": iso_now(),
            "db": db_status,
        })

    def handle_root(self):
        """公开首页：产品落地页（单页内联 CSS，离线可看）。"""
        prefix = "/quiet-api/" if urlparse(self.path).path == "/quiet-api" else ""
        self.send_html(
            200,
            _render_home(self._apk_manifest(), _site_stats(), prefix),
        )

    # -- app update / APK hosting -------------------------------------------

    def _apk_manifest(self) -> dict:
        manifest = {}
        try:
            raw = (APK_DIR / APK_MANIFEST_NAME).read_text(encoding="utf-8")
            data = json.loads(raw)
            if isinstance(data, dict):
                manifest = data
        except (OSError, ValueError):
            manifest = {}
        apk_path = APK_DIR / APK_FILE_NAME
        size = 0
        digest = ""
        try:
            size = apk_path.stat().st_size
            sha = hashlib.sha256()
            with apk_path.open("rb") as fh:
                for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                    sha.update(chunk)
            digest = sha.hexdigest()
        except OSError:
            pass
        return {
            "versionCode": int(manifest.get("versionCode") or APP_FALLBACK_VERSION_CODE),
            "versionName": str(manifest.get("versionName") or APP_FALLBACK_VERSION_NAME),
            "minSdk": int(manifest.get("minSdk") or 26),
            "apkPath": "/apk/" + APK_FILE_NAME,
            "apkSize": size,
            "sha256": digest,
            "notes": str(manifest.get("notes") or ""),
            "publishedAt": str(manifest.get("publishedAt") or iso_now()),
            "serverVersion": VERSION,
        }

    def handle_app_latest(self):
        """公开的版本清单：APK 从 /apk/quietledger.apk 下载。"""
        self.send_json(200, self._apk_manifest())

    def handle_apk_download(self, name: str):
        safe = os.path.basename(name or "")
        if safe != APK_FILE_NAME:
            raise ApiError(404, "not_found", "apk not found")
        self.send_file(APK_DIR / safe, APK_CONTENT_TYPE)

    def _create_token(self, conn, account_id: int) -> str:
        token = secrets.token_hex(32)
        conn.execute(
            "INSERT INTO tokens (token_hash, account_id, created_at, last_used_at) "
            "VALUES (?, ?, ?, ?)",
            (sha256_hex(token), account_id, now_ms(), now_ms()),
        )
        return token

    def _new_account_code(self, conn) -> str:
        alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        for _ in range(64):
            code = "QL-" + "".join(secrets.choice(alphabet) for _ in range(4))
            exists = conn.execute(
                "SELECT 1 FROM accounts WHERE account_code = ?", (code,)
            ).fetchone()
            if exists is None:
                return code
        raise RuntimeError("could not allocate account code")

    def _device_values(self, body: dict):
        device_id = as_str(body.get("deviceId"), 128) or self.header_device_id()
        if not device_id:
            raise ApiError(400, "bad_request", "deviceId required")
        model = as_str(body.get("model"), 128)
        app_version = as_str(body.get("appVersion"), 64)
        return device_id, model, app_version

    def handle_create_account(self):
        SIGNUP_LIMIT.check(self.account_request_address())
        body = self.read_json_body()
        device_id, model, app_version = self._device_values(body)
        with db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                code = self._new_account_code(conn)
                cur = conn.execute(
                    "INSERT INTO accounts (account_code, tariff_version, created_at) "
                    "VALUES (?, ?, ?)",
                    (code, TARIFF_VERSION, now_ms()),
                )
                account_id = cur.lastrowid
                token = self._create_token(conn, account_id)
                conn.execute(
                    "INSERT INTO devices (account_id, device_id, model, app_version, "
                    "created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (account_id, device_id, model, app_version, now_ms(), now_ms()),
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
        self.send_json(200, {
            "accountCode": code,
            "token": token,
            "deviceId": device_id,
            "tariffVersion": TARIFF_VERSION,
            "serverTime": iso_now(),
        })

    def handle_issue_invite(self, account: dict):
        self.read_json_body()
        invite = "QLI-" + secrets.token_urlsafe(24)
        now = now_ms()
        with db() as conn:
            conn.execute(
                "DELETE FROM account_invites WHERE expires_at <= ?", (now,)
            )
            conn.execute(
                "INSERT INTO account_invites (account_id, invite_hash, expires_at) "
                "VALUES (?, ?, ?) ON CONFLICT(account_id) DO UPDATE SET "
                "invite_hash = excluded.invite_hash, expires_at = excluded.expires_at",
                (account["id"], sha256_hex(invite), now + INVITE_TTL_MS),
            )
        self.send_json(200, {"inviteCode": invite, "expiresInSeconds": INVITE_TTL_MS // 1000})

    def handle_join_account(self):
        JOIN_LIMIT.check(self.account_request_address())
        body = self.read_json_body()
        invite = body.get("inviteCode")
        invalid_invite = ApiError(404, "invalid_invite", "邀请码无效或已过期")
        if not isinstance(invite, str) or not JOIN_CODE_RE.fullmatch(invite.strip()):
            raise invalid_invite
        invite_hash = sha256_hex(invite.strip())
        device_id, model, app_version = self._device_values(body)
        with db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                row = conn.execute(
                    "SELECT a.id, a.account_code FROM account_invites i "
                    "JOIN accounts a ON a.id = i.account_id "
                    "WHERE i.invite_hash = ? AND i.expires_at > ?",
                    (invite_hash, now_ms()),
                ).fetchone()
                if row is None:
                    raise invalid_invite
                conn.execute("DELETE FROM account_invites WHERE account_id = ?", (row["id"],))
                token = self._create_token(conn, row["id"])
                conn.execute(
                    "INSERT INTO devices (account_id, device_id, model, app_version, "
                    "created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(account_id, device_id) DO UPDATE SET "
                    "model = excluded.model, app_version = excluded.app_version, "
                    "last_seen_at = excluded.last_seen_at",
                    (row["id"], device_id, model, app_version, now_ms(), now_ms()),
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
        self.send_json(200, {
            "accountCode": row["account_code"],
            "token": token,
            "deviceId": device_id,
            "tariffVersion": TARIFF_VERSION,
            "serverTime": iso_now(),
        })

    def handle_tariff(self, account: dict):
        with db() as conn:
            payload = tariff_payload(conn, account["id"])
        self.send_json(200, payload)

    def handle_settings(self, account: dict):
        """Per-account tax rules: POST /v1/settings (auth).

        body: {"unitSeconds": 8, "exemptionPerDay": 240}
        Only the fields present are validated and updated; the response is the
        same structure as GET /v1/tariff, so the App can cache it directly.
        """
        body = self.read_json_body()
        has_unit = "unitSeconds" in body
        has_exemption = "exemptionPerDay" in body
        if not has_unit and not has_exemption:
            raise ApiError(
                400, "bad_request", "unitSeconds or exemptionPerDay required"
            )
        with db() as conn:
            current = account_rules(conn, account["id"])
            unit = current["unitSeconds"]
            exemption = current["exemptionPerDay"]
            if has_unit:
                unit = self._strict_int(body.get("unitSeconds"))
                if unit is None or not (UNIT_SECONDS_MIN <= unit <= UNIT_SECONDS_MAX):
                    raise ApiError(
                        400, "bad_request",
                        f"unitSeconds must be an integer between "
                        f"{UNIT_SECONDS_MIN} and {UNIT_SECONDS_MAX}",
                    )
            if has_exemption:
                exemption = self._strict_int(body.get("exemptionPerDay"))
                if exemption is None or not (EXEMPTION_MIN <= exemption <= EXEMPTION_MAX):
                    raise ApiError(
                        400, "bad_request",
                        f"exemptionPerDay must be an integer between "
                        f"{EXEMPTION_MIN} and {EXEMPTION_MAX}",
                    )
            conn.execute(
                "UPDATE accounts SET unit_seconds = ?, exemption_per_day = ? "
                "WHERE id = ?",
                (unit, exemption, account["id"]),
            )
            payload = tariff_payload(conn, account["id"])
        self.send_json(200, payload)

    @staticmethod
    def _strict_int(value):
        """JSON integer only (reject bool/float/string); None when not an int."""
        if isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value
        if isinstance(value, float) and value.is_integer():
            return int(value)
        return None

    def _validate_event(self, raw, now: int):
        """Return a normalized event dict, or None when the event is invalid."""
        if not isinstance(raw, dict):
            return None
        event_id = raw.get("id")
        pkg = raw.get("pkg")
        app = raw.get("app")
        ts = raw.get("ts")
        if not isinstance(event_id, str) or not event_id.strip() or len(event_id) > 128:
            return None
        pkg = as_str(pkg, 200)
        app = as_str(app, 100)
        if not pkg or not app:
            return None
        if isinstance(ts, bool) or not isinstance(ts, (int, float)):
            return None
        ts = int(ts)
        if abs(ts - now) > EVENT_TS_WINDOW_DAYS * 86400 * 1000:
            return None
        title = as_str(raw.get("title"), MAX_TITLE_CHARS)
        category = raw.get("category")
        if not isinstance(category, str) or category not in CATEGORIES:
            category = "system"
        kind = raw.get("conversationClass")
        if not isinstance(kind, str) or kind not in CONVERSATION_FACTORS:
            kind = ""
        return {
            "id": event_id.strip(),
            "pkg": pkg,
            "app": app,
            "title": title,
            "ts": ts,
            "priority": as_int(raw.get("priority"), 0),
            "category": category,
            "ongoing": 1 if as_bool(raw.get("ongoing"), False) else 0,
            "clearable": 1 if as_bool(raw.get("clearable"), True) else 0,
            # v2026-11-c: booleans only.  Missing fields (older clients) => False,
            # which prices exactly like v2026-11-b.
            "dnd": 1 if as_bool(raw.get("dnd"), False) else 0,
            "notice": 1 if as_bool(raw.get("notice"), False) else 0,
            "conversation_class": kind,
        }

    def handle_events(self, account: dict):
        body = self.read_json_body()
        device_id = as_str(body.get("deviceId"), 128) or self.header_device_id()
        if not device_id:
            raise ApiError(400, "bad_request", "deviceId required")
        raw_events = body.get("events", [])
        if raw_events is None:
            raw_events = []
        if not isinstance(raw_events, list):
            raise ApiError(400, "bad_request", "events must be an array")
        if len(raw_events) > MAX_EVENTS_PER_REQUEST:
            raise ApiError(400, "too_many_events",
                           f"at most {MAX_EVENTS_PER_REQUEST} events per request")

        now = now_ms()
        received_at = now
        valid = []
        invalid = 0
        for raw in raw_events:
            event = self._validate_event(raw, now)
            if event is None:
                invalid += 1
            else:
                valid.append(event)

        stored = 0
        with db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                conn.execute(
                    "INSERT INTO devices (account_id, device_id, model, app_version, "
                    "created_at, last_seen_at) VALUES (?, ?, '', '', ?, ?) "
                    "ON CONFLICT(account_id, device_id) DO UPDATE SET "
                    "last_seen_at = excluded.last_seen_at",
                    (account["id"], device_id, received_at, received_at),
                )
                seen = set()
                for event in valid:
                    if event["id"] in seen:
                        continue
                    seen.add(event["id"])
                    cur = conn.execute(
                        "INSERT OR IGNORE INTO events (account_id, device_id, event_id, "
                        "pkg, app, title, ts, priority, category, ongoing, clearable, "
                        "dnd, notice, conversation_class, received_at) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (account["id"], device_id, event["id"], event["pkg"],
                         event["app"], event["title"], event["ts"],
                         event["priority"], event["category"], event["ongoing"],
                         event["clearable"], event["dnd"], event["notice"], event["conversation_class"],
                         received_at),
                    )
                    if cur.rowcount == 1:
                        stored += 1
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise

        accepted = len(valid)
        payload = {
            "accepted": accepted,
            "duplicated": accepted - stored,
            "stored": stored,
            "serverTime": iso_now(),
        }
        if invalid:
            payload["invalid"] = invalid
        self.send_json(200, payload)

    def handle_purge_events(self, account: dict):
        """删除账户里被判定为系统噪音/重复的历史事件，票面实时重算。

        body: {"packages": ["com.android.systemui", ...],
               "titleContains": ["正在运行", ...],
               "beforeTs": <epoch ms，可选>}
        至少给一个过滤条件，否则拒绝，避免误删全部账单。
        """
        body = self.read_json_body()
        packages = body.get("packages") or []
        titles = body.get("titleContains") or []
        event_ids = body.get("eventIds") or []
        before_ts = body.get("beforeTs")
        if not isinstance(packages, list) or not isinstance(titles, list) \
                or not isinstance(event_ids, list):
            raise ApiError(400, "bad_request", "packages/titleContains/eventIds must be arrays")
        packages = [as_str(p, 200) for p in packages if as_str(p, 200)]
        titles = [as_str(t, 100) for t in titles if as_str(t, 100)]
        event_ids = [as_str(e, 200) for e in event_ids if as_str(e, 200)]
        if len(packages) > 200 or len(titles) > 50 or len(event_ids) > 500:
            raise ApiError(400, "bad_request", "too many purge filters")

        clauses = ["account_id = ?"]
        params = [account["id"]]
        or_parts = []
        if packages:
            or_parts.append("pkg IN (%s)" % ",".join("?" * len(packages)))
            params.extend(packages)
        for needle in titles:
            or_parts.append("title LIKE ?")
            params.append("%" + needle + "%")
        if event_ids:
            or_parts.append("event_id IN (%s)" % ",".join("?" * len(event_ids)))
            params.extend(event_ids)
        if before_ts is not None:
            try:
                before_ts = int(before_ts)
            except (TypeError, ValueError):
                raise ApiError(400, "bad_request", "beforeTs must be epoch ms")
        if not or_parts and before_ts is None:
            raise ApiError(400, "bad_request", "at least one purge filter required")
        if or_parts:
            clauses.append("(" + " OR ".join(or_parts) + ")")
        if before_ts is not None:
            clauses.append("ts < ?")
            params.append(before_ts)

        with db() as conn:
            cur = conn.execute(
                "DELETE FROM events WHERE " + " AND ".join(clauses), params
            )
            deleted = cur.rowcount
        self.send_json(200, {
            "deleted": deleted,
            "accountCode": account["account_code"],
            "serverTime": iso_now(),
        })

    def handle_profile(self, account: dict):
        """设置账户昵称；会显示在分享收据上。"""
        body = self.read_json_body()
        nickname = as_str(body.get("nickname"), 24).strip()
        if not nickname:
            raise ApiError(400, "bad_request", "nickname required")
        with db() as conn:
            conn.execute(
                "UPDATE accounts SET nickname = ? WHERE id = ?",
                (nickname, account["id"]),
            )
        self.send_json(200, {
            "nickname": nickname,
            "accountCode": account["account_code"],
            "serverTime": iso_now(),
        })

    def handle_invoice(self, account: dict, query: dict):
        date_str = (query.get("date") or [""])[0].strip() or today_local()
        if not valid_date(date_str):
            raise ApiError(400, "bad_request", "date must be YYYY-MM-DD")
        device_id = (query.get("deviceId") or [""])[0].strip() or "all"
        if len(device_id) > 128:
            raise ApiError(400, "bad_request", "deviceId too long")
        with db() as conn:
            payload = invoice_payload(conn, account, date_str, device_id)
        self.send_json(200, payload)

    def handle_weekly(self, account: dict, query: dict):
        end_str = (query.get("end") or [""])[0].strip() or today_local()
        if not valid_date(end_str):
            raise ApiError(400, "bad_request", "end must be YYYY-MM-DD")
        days_raw = (query.get("days") or ["7"])[0].strip()
        try:
            day_count = int(days_raw)
        except ValueError:
            raise ApiError(400, "bad_request", "days must be an integer")
        if day_count < 1 or day_count > 30:
            raise ApiError(400, "bad_request", "days must be between 1 and 30")
        end = date_cls.fromisoformat(end_str)
        start = end - timedelta(days=day_count - 1)
        total_duty = 0
        total_events = 0
        night_events = 0
        app_map = {}
        app_pkg = {}
        days = []
        with db() as conn:
            for offset in range(day_count):
                day = build_day(conn, account["id"], (start + timedelta(days=offset)).isoformat())
                days.append({
                    "date": day["date"],
                    "events": day["eventCount"],
                    "duty": day["totalDuty"],
                })
                total_duty += day["totalDuty"]
                total_events += day["eventCount"]
                night_events += day["nightEvents"]
                for app_name, stats in day["appMap"].items():
                    entry = app_map.get(app_name)
                    if entry is None:
                        entry = {"count": 0, "duty": 0}
                        app_map[app_name] = entry
                    entry["count"] += stats["count"]
                    entry["duty"] += stats["duty"]
                for app_name, pkg in day["appPkg"].items():
                    app_pkg.setdefault(app_name, pkg)
        top_apps = [
            {"app": name, "pkg": app_pkg.get(name, ""),
             "count": stats["count"], "duty": stats["duty"]}
            for name, stats in sorted(
                app_map.items(), key=lambda kv: (-kv[1]["count"], -kv[1]["duty"], kv[0])
            )[:10]
        ]
        self.send_json(200, {
            "start": start.isoformat(),
            "end": end.isoformat(),
            "dayCount": day_count,
            "totalDuty": total_duty,
            "totalEvents": total_events,
            "days": days,
            "topApps": top_apps,
            "nightRatio": round(night_events / total_events, 4) if total_events else 0.0,
            "tariffVersion": TARIFF_VERSION,
        })

    def handle_report(self, account: dict, query: dict):
        """任意周期汇总：GET /v1/report?kind=week|month|quarter|year&days=N&end=...

        ``week`` / ``month`` 按天分桶，``quarter`` / ``year`` 按自然月分桶（都只到
        ``end`` 为止，不补未来日期）；``days`` 只用于 week 窗口（1..30），
        month/quarter/year 是自然周期，忽略它。金额全部由 ``build_day()`` 逐日汇总，
        不在这里写第二套计价公式；``prev`` 给出等长的上一周期，供 App 显示「较上期」。
        """
        kind = (query.get("kind") or [""])[0].strip().lower() or "month"
        if kind not in REPORT_KINDS:
            raise ApiError(400, "bad_request",
                           "kind must be week, month, quarter or year")
        end_str = (query.get("end") or [""])[0].strip() or today_local()
        if not valid_date(end_str):
            raise ApiError(400, "bad_request", "end must be YYYY-MM-DD")
        days_raw = (query.get("days") or ["7"])[0].strip()
        try:
            days = int(days_raw)
        except ValueError:
            raise ApiError(400, "bad_request", "days must be an integer")
        if kind == "week" and (days < 1 or days > REPORT_WEEK_MAX_DAYS):
            raise ApiError(400, "bad_request",
                           f"days must be between 1 and {REPORT_WEEK_MAX_DAYS}")

        end = date_cls.fromisoformat(end_str)
        start, end = report_period(kind, end, days)
        day_count = (end - start).days + 1
        by_day = kind in ("week", "month")

        plans = report_buckets(kind, start, end)
        buckets = []
        month_index = {}
        for index, (label, bucket_start, bucket_end) in enumerate(plans):
            buckets.append({
                "label": label,
                "start": bucket_start.isoformat(),
                "end": bucket_end.isoformat(),
                "duty": 0,
                "events": 0,
            })
            month_index[label] = index

        total_duty = 0
        total_events = 0
        saved_seconds = 0
        night_events = 0
        app_map = {}
        app_pkg = {}
        # prev = the equally long period immediately before start.
        prev_end = start - timedelta(days=1)
        prev_start = prev_end - timedelta(days=day_count - 1)
        prev_duty = 0
        prev_events = 0

        with db() as conn:
            for offset in range(day_count):
                day_str = (start + timedelta(days=offset)).isoformat()
                day = build_day(conn, account["id"], day_str)
                bucket = buckets[offset] if by_day else buckets[month_index[day_str[:7]]]
                bucket["duty"] += day["totalDuty"]
                bucket["events"] += day["eventCount"]
                total_duty += day["totalDuty"]
                total_events += day["eventCount"]
                saved_seconds += day["savedSeconds"]
                night_events += day["nightEvents"]
                for app_name, stats in day["appMap"].items():
                    entry = app_map.get(app_name)
                    if entry is None:
                        entry = {"count": 0, "duty": 0}
                        app_map[app_name] = entry
                    entry["count"] += stats["count"]
                    entry["duty"] += stats["duty"]
                for app_name, pkg in day["appPkg"].items():
                    app_pkg.setdefault(app_name, pkg)
            for offset in range(day_count):
                prev_day = build_day(
                    conn, account["id"],
                    (prev_start + timedelta(days=offset)).isoformat(),
                )
                prev_duty += prev_day["totalDuty"]
                prev_events += prev_day["eventCount"]

        top_apps = [
            {"app": name, "pkg": app_pkg.get(name, ""),
             "count": stats["count"], "duty": stats["duty"]}
            for name, stats in sorted(
                app_map.items(),
                key=lambda kv: (-kv[1]["duty"], -kv[1]["count"], kv[0]),
            )[:REPORT_TOP_APPS]
        ]
        self.send_json(200, {
            "kind": kind,
            "start": start.isoformat(),
            "end": end.isoformat(),
            "dayCount": day_count,
            "totalDuty": total_duty,
            "totalEvents": total_events,
            "savedSeconds": saved_seconds,
            "nightRatio": round(night_events / total_events, 4) if total_events else 0.0,
            "buckets": buckets,
            "topApps": top_apps,
            "prev": {
                "start": prev_start.isoformat(),
                "end": prev_end.isoformat(),
                "totalDuty": prev_duty,
                "totalEvents": prev_events,
            },
            "tariffVersion": TARIFF_VERSION,
            "generatedAt": iso_now(),
        })

    def handle_devices(self, account: dict):
        with db() as conn:
            rows = conn.execute(
                "SELECT d.device_id, d.model, d.created_at, d.last_seen_at, "
                "  (SELECT COUNT(*) FROM events e WHERE e.account_id = d.account_id "
                "     AND e.device_id = d.device_id) AS event_count "
                "FROM devices d WHERE d.account_id = ? "
                "ORDER BY d.created_at ASC, d.device_id ASC",
                (account["id"],),
            ).fetchall()
        devices = [{
            "deviceId": row["device_id"],
            "model": row["model"] or "",
            "lastSeenAt": iso_from_ms(row["last_seen_at"] or row["created_at"]),
            "eventCount": row["event_count"],
        } for row in rows]
        self.send_json(200, {"devices": devices})

    def handle_meta(self, account: dict):
        with db() as conn:
            accounts = conn.execute("SELECT COUNT(*) FROM accounts").fetchone()[0]
            events = conn.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            invoices = conn.execute("SELECT COUNT(*) FROM invoices").fetchone()[0]
        self.send_json(200, {
            "serverTime": iso_now(),
            "accounts": accounts,
            "events": events,
            "invoices": invoices,
        })

    def handle_share_receipt(self, account: dict):
        date_str = self.read_json_body().get("date")
        if not isinstance(date_str, str) or not valid_date(date_str):
            raise ApiError(400, "bad_request", "date must be YYYY-MM-DD")
        with db() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                invoice_id = get_or_create_invoice(
                    conn, account["id"], account["account_code"], date_str
                )["invoiceId"]
                conn.execute(
                    "INSERT INTO receipt_shares (invoice_id, share_id) VALUES (?, ?) "
                    "ON CONFLICT(invoice_id) DO NOTHING",
                    (invoice_id, "r_" + secrets.token_urlsafe(24)),
                )
                share_id = conn.execute(
                    "SELECT share_id FROM receipt_shares WHERE invoice_id = ?", (invoice_id,)
                ).fetchone()["share_id"]
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
        self.send_json(200, {"shareId": share_id})

    def handle_receipt(self, share_id: str):
        if not SHARE_ID_RE.fullmatch(share_id):
            self.send_html(404, render_404(""))
            return
        with db() as conn:
            row = conn.execute(
                "SELECT i.account_id, i.date, a.account_code "
                "FROM receipt_shares s JOIN invoices i ON i.invoice_id = s.invoice_id "
                "JOIN accounts a ON a.id = i.account_id WHERE s.share_id = ?",
                (share_id,),
            ).fetchone()
            if row is None:
                self.send_html(404, render_404(""))
                return
            account = {"id": row["account_id"], "account_code": row["account_code"]}
            payload = invoice_payload(conn, account, row["date"], "all")
        self.send_html(200, render_receipt(payload))


# --------------------------------------------------------------------------- #
# Entrypoint
# --------------------------------------------------------------------------- #


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stdout,
    )
    init_db()
    server = Server((HOST, PORT), ApiHandler)
    log.info("listening on %s:%s, db=%s", HOST, PORT, DB_PATH)

    def _shutdown(signum, _frame):
        log.info("received signal %s, shutting down", signum)
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT, _shutdown)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
        log.info("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
