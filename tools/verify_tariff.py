#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/verify_tariff.py — 独立复算 QuietLedger 税率 v2026-11-c（不 import backend/app.py）。

用途
----
这是一个**独立参考实现**：只按 docs/TAX-RULES.md §2 的模型重写一遍计价器，
不 import 服务端代码、不依赖 Android 代码，用来交叉验证实现者是否写对。

计价模型（§2 原文，v2026-11-c）：

    mult = appFactor(pkg)
    if night:  mult *= nightMultiplier       # 1.5
    if notice: mult *= noticeMultiplier      # 2.0（群发 @所有人/@全体成员/@all/@everyone）
    if dnd:    mult *= dndMultiplier         # 0.5（投递时系统免打扰或本 App 静默）
    if burst:  mult *= burstMultiplier       # 0.4（同设备同应用 120 秒内第二条件起）
    raw    = unitSeconds * mult
    capped = min(raw, unitSeconds * capFactor)    # capFactor = 3.0
    duty   = max(1, ceil(capped))                 # 只在最后取整一次，至少 1 秒

    baseMult = appFactor × (night?1.5) × (notice?2.0)
    base  = max(1, ceil(unitSeconds * baseMult))
    saved = max(0, base - duty)              # 每条事件的优惠合计（静默/连发/封顶）
    savedSeconds = Σ saved                   # v2026-11-c：全部优惠合计，不再只算连发
    balance = exemptionPerDay − Σduty

  * appFactor：按包名前缀匹配，默认 1.0（前缀表见 APP_MULTIPLIERS；多义时取最长前缀）；
  * nightFactor：北京时间 hour >= 23 或 hour < 7 为 1.5，否则 1.0；
  * notice 标记是投递时算好的布尔值（App 侧 NotifRules.isBroadcastNotice），参考实现直接读；
  * burstFactor：同一设备、同一包名上一条间隔 <= 120 秒时为 0.4（第二条件起），否则 1.0；
    **连发只在 Asia/Shanghai 自然日内判断，每天 0 点重置，跨日不算连发**（§2）；
  * 封顶：当前系数组合最大值恰为 unit×3.0（夜间 1.5 × 群发 2.0），是护栏；
  * ceil 在乘完所有系数之后只做一次；向上取整后至少 1 秒；
  * 结算日 = Asia/Shanghai 自然日。

两个模式
--------
1) 默认纯手算模式（无需服务端）：

       python tools/verify_tariff.py

   断言 v2026-11-c fixture（默认 unit=8 → 81 秒 / saved 55 / balance 159；
   自定义 unit=4/exemption=120 → 41 秒 / saved 27 / balance 79；封顶账户
   unit=30、夜间+群发 → 90 秒 = unit×3.0，连发后 126/54）、ceil 边界、
   前缀表、夜间边界、连发窗口、勿扰折扣、群发加成、封顶、最低 1 秒、
   savedSeconds=全部优惠合计、免税额边界、浮点/精确算数一致性。

2) 端到端模式（后端代码就绪后）：

       python tools/verify_tariff.py --base http://127.0.0.1:8791

   通过 urllib 建账户 → 灌 fixture → GET /v1/tariff、GET /v1/invoice
   逐字段比对 → POST /v1/settings 自定义后复算 → 恢复默认复核 → 封顶账户复核；
   附加跨设备税则共享、非法设置校验、120 秒窗口边界、夜间小时边界、跨日不算连发
   等端到端检查。

本文件末尾（--help 的 EPILOG）与 tools/AUDIT-CHECKLIST.md 给出人工审计清单。

退出码：0 全部通过；1 有断言失败；2 环境/参数错误（--base 连不上等）。
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from fractions import Fraction

try:  # Windows 控制台默认不是 UTF-8，尽量让中文输出不乱码
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老环境无 reconfigure
    pass

# --------------------------------------------------------------------------- #
# 冻结常量（与 docs/TAX-RULES.md §2/§4 一致，独立抄写，不 import 服务端）
# --------------------------------------------------------------------------- #

TARIFF_VERSION = "2026-11-d"
CONVERSATION_FACTORS = {"work": 1, "family": 0.25, "friends": 0.25, "casual": 0.1, "free": 0}
TARIFF_UPDATED_AT = "2026-11-15T00:00:00Z"
DEFAULT_UNIT_SECONDS = 8
DEFAULT_EXEMPTION_PER_DAY = 240
DEFAULT_NIGHT_MULTIPLIER = 1.5
NIGHT_START_HOUR = 23
NIGHT_END_HOUR = 7
DEFAULT_BURST_MULTIPLIER = 0.4
BURST_WINDOW_SEC = 120
# v2026-11-c 新增三个全局系数
DEFAULT_DND_MULTIPLIER = 0.5      # 投递时免打扰 → 折扣
DEFAULT_NOTICE_MULTIPLIER = 2.0   # 群发提醒 → 加倍
DEFAULT_CAP_FACTOR = 3.0          # 单条封顶：单条税秒 <= unit × 3.0
APP_MULTIPLIER_DEFAULT = 1.0

APP_MULTIPLIERS = {
    # 聊天类：0.5
    "com.tencent.mm": 0.5,
    "com.tencent.mobileqq": 0.5,
    "com.tencent.tim": 0.5,
    # 办公类：0.4
    "com.tencent.wework": 0.4,
    "com.alibaba.android.rimet": 0.4,
    "com.ss.android.lark": 0.4,
    "com.larksuite.suite": 0.4,
    "com.microsoft.teams": 0.4,
    "com.slack": 0.4,
}

SERVER_VERSION = "1.4.1"
TZ = timezone(timedelta(hours=8))  # Asia/Shanghai（UTC+8，无夏令时）

# 测试用包名
PKG_A = "com.verify.a"
PKG_B = "com.verify.b"
PKG_MM = "com.tencent.mm"
PKG_WEWORK = "com.tencent.wework"
PKG_DND = "com.verify.dnd"
PKG_NOTICE = "com.verify.notice"
PKG_MIX = "com.verify.mix"
PKG_CAP = "com.verify.cap"

# 纯手算模式使用的固定日期（避免运行时钟影响）
FIXED_DAY = "2026-11-09"


# --------------------------------------------------------------------------- #
# 数据模型
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class Tariff:
    """账户生效税则；字段与 GET /v1/tariff 返回/Model.kt Tariff 对应。"""

    unit_seconds: int = DEFAULT_UNIT_SECONDS
    exemption_per_day: int = DEFAULT_EXEMPTION_PER_DAY
    night_multiplier: float = DEFAULT_NIGHT_MULTIPLIER
    night_start_hour: int = NIGHT_START_HOUR
    night_end_hour: int = NIGHT_END_HOUR
    burst_multiplier: float = DEFAULT_BURST_MULTIPLIER
    burst_window_sec: int = BURST_WINDOW_SEC
    dnd_multiplier: float = DEFAULT_DND_MULTIPLIER
    notice_multiplier: float = DEFAULT_NOTICE_MULTIPLIER
    cap_factor: float = DEFAULT_CAP_FACTOR
    app_multipliers: dict = field(default_factory=lambda: dict(APP_MULTIPLIERS))
    app_multiplier_default: float = APP_MULTIPLIER_DEFAULT
    version: str = TARIFF_VERSION
    updated_at: str = TARIFF_UPDATED_AT


@dataclass
class Event:
    """一条通知事件；ts 为 epoch 毫秒（Asia/Shanghai 解释）。

    v2026-11-c：``dnd`` / ``notice`` 是投递时算好的布尔标记，
    只表示是否命中规则，不带任何通知原文。
    """

    event_id: str
    pkg: str
    app: str
    ts: int
    device_id: str = "verify-device-1"
    title: str = ""
    category: str = "msg"
    dnd: bool = False
    notice: bool = False
    conversation_class: str = ""

    def as_api(self) -> dict:
        return {
            "id": self.event_id,
            "pkg": self.pkg,
            "app": self.app,
            "title": self.title,
            "ts": self.ts,
            "priority": 0,
            "category": self.category,
            "ongoing": False,
            "clearable": True,
            "dnd": self.dnd,
            "notice": self.notice,
            "conversationClass": self.conversation_class,
        }


@dataclass
class Marked:
    """一条事件的计算结果（对齐 Model.kt 的 MarkedEvent）。"""

    event_id: str
    pkg: str
    app: str
    ts: int
    device_id: str
    night: bool
    burst: bool
    dnd: bool
    notice: bool
    duty: int
    saved: int
    mult_float: float
    duty_float: int
    saved_float: int


# --------------------------------------------------------------------------- #
# 独立计价实现
# --------------------------------------------------------------------------- #


def _q(value) -> Fraction:
    """把 int/float 税率因子转成精确有理数；float 按十进制字面值（0.4 -> 2/5）。"""
    if isinstance(value, bool):
        raise TypeError("bool is not a tariff factor")
    if isinstance(value, int):
        return Fraction(value)
    return Fraction(str(value))


def _shanghai(ts_ms: int) -> datetime:
    return datetime.fromtimestamp(ts_ms / 1000.0, TZ)


def day_bounds(day_str: str) -> tuple:
    """[start_ms, end_ms) of an Asia/Shanghai calendar day."""
    d = datetime.fromisoformat(day_str).replace(tzinfo=TZ)
    return int(d.timestamp() * 1000), int((d + timedelta(days=1)).timestamp() * 1000)


def ts_at(day_str: str, hour: int, minute: int, second: int = 0) -> int:
    d = datetime.fromisoformat(day_str).replace(tzinfo=TZ)
    return int((d + timedelta(hours=hour, minutes=minute, seconds=second)).timestamp() * 1000)


def is_night(ts_ms: int, tariff: Tariff = None) -> bool:
    tariff = tariff or Tariff()
    hour = _shanghai(ts_ms).hour
    return hour >= tariff.night_start_hour or hour < tariff.night_end_hour


def app_factor(pkg: str, tariff: Tariff = None) -> float:
    """前缀匹配，默认 1.0；多个前缀命中时取最长前缀（表内无重叠，见审计清单）。"""
    tariff = tariff or Tariff()
    best_len = -1
    best = tariff.app_multiplier_default
    for prefix, mult in tariff.app_multipliers.items():
        if pkg.startswith(prefix) and len(prefix) > best_len:
            best_len = len(prefix)
            best = mult
    return float(best)


def mark(events, tariff: Tariff = None) -> list:
    """按 ts 升序标记每条事件的 night/burst/dnd/notice/duty/saved（与服务端逐位一致）。

    v2026-11-c 公式（顺序固定，只在最后取整一次）：

        mult = app × (night?1.5) × (notice?2.0) × (dnd?0.5) × (burst?0.4)
        duty = max(1, ceil(min(unit × mult, unit × capFactor)))
        base = max(1, ceil(unit × app × (night?1.5) × (notice?2.0)))
        saved = max(0, base − duty)
    """
    tariff = tariff or Tariff()
    ordered = sorted(events, key=lambda e: (e.ts, e.event_id))
    prev = {}
    marks = []
    for ev in ordered:
        # §2：连发只在 Asia/Shanghai 自然日内判断，每天 0 点重置（跨日不算连发）。
        # key 带本地日期，等价于在自然日边界清空上一事件状态。
        local_day = _shanghai(ev.ts).date().isoformat()
        kind = ev.conversation_class if ev.conversation_class in CONVERSATION_FACTORS else ""
        key = (local_day, ev.device_id, ev.pkg, kind)
        prev_ts = prev.get(key)
        burst = prev_ts is not None and 0 <= ev.ts - prev_ts <= tariff.burst_window_sec * 1000
        if kind != "free":
            prev[key] = ev.ts

        night = is_night(ev.ts, tariff)
        dnd = bool(ev.dnd)
        notice = bool(ev.notice)
        af = CONVERSATION_FACTORS.get(kind, app_factor(ev.pkg, tariff))
        nf = tariff.night_multiplier if night else 1.0
        dm = tariff.dnd_multiplier if dnd else 1.0
        nm = tariff.notice_multiplier if notice else 1.0
        bf = tariff.burst_multiplier if burst else 1.0

        # ---- 精确有理数路径（参考真相） ---------------------------------
        exact = _q(tariff.unit_seconds) * _q(af)
        base_exact = exact
        if night:
            exact *= _q(nf)
            base_exact *= _q(nf)
        if notice:
            exact *= _q(nm)
            base_exact *= _q(nm)
        if dnd:
            exact *= _q(dm)
        if burst:
            exact *= _q(bf)
        raw_exact = exact
        capped_exact = min(raw_exact, _q(tariff.unit_seconds) * _q(tariff.cap_factor))
        minimum = 0 if kind == "free" else 1
        duty = max(minimum, math.ceil(capped_exact))
        base = max(minimum, math.ceil(base_exact))
        saved = max(0, base - duty)

        # ---- 浮点路径（与服务端相同的 1e-9 容差） ----------------------
        mult_float = tariff.unit_seconds * af * nf * nm * dm * bf
        capped_float = min(mult_float, tariff.unit_seconds * tariff.cap_factor)
        duty_float = max(minimum, math.ceil(capped_float - 1e-9))
        base_float = tariff.unit_seconds * af * nf * nm
        saved_float = max(0, max(minimum, math.ceil(base_float - 1e-9)) - duty_float)

        marks.append(Marked(
            event_id=ev.event_id,
            pkg=ev.pkg,
            app=ev.app,
            ts=ev.ts,
            device_id=ev.device_id,
            night=night,
            burst=burst,
            dnd=dnd,
            notice=notice,
            duty=duty,
            saved=saved,
            mult_float=mult_float,
            duty_float=duty_float,
            saved_float=saved_float,
        ))
    return marks


def line_note(line: dict) -> str:
    """行 note，顺序固定：夜间、连发、免打扰、群发；只拼非零项（§2.3）。"""
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


def compute(events, tariff: Tariff = None, day: str = None) -> dict:
    """汇总一天的账：duty / savedSeconds / balance / lines / stats。

    ``day`` 给定时只在汇总层面截取该日事件；连发判定由 ``mark()`` 按
    Asia/Shanghai 自然日自动重置，传入跨日事件也不会把前一天算成连发。
    """
    tariff = tariff or Tariff()
    marks = mark(events, tariff)
    if day is not None:
        start_ms, end_ms = day_bounds(day)
        marks = [m for m in marks if start_ms <= m.ts < end_ms]

    total_duty = sum(m.duty for m in marks)
    saved = sum(m.saved for m in marks)
    night_events = sum(1 for m in marks if m.night)
    bursts = sum(1 for m in marks if m.burst)
    dnd_events = sum(1 for m in marks if m.dnd)
    notice_events = sum(1 for m in marks if m.notice)
    event_count = len(marks)

    by_hour = [0] * 24
    for m in marks:
        by_hour[_shanghai(m.ts).hour] += 1

    lines = {}
    for m in marks:
        key = (m.app, m.pkg)
        line = lines.get(key)
        if line is None:
            line = {"app": m.app, "pkg": m.pkg, "count": 0, "duty": 0,
                    "night": 0, "burst": 0, "dnd": 0, "notice": 0, "saved": 0}
            lines[key] = line
        line["count"] += 1
        line["duty"] += m.duty
        line["night"] += 1 if m.night else 0
        line["burst"] += 1 if m.burst else 0
        line["dnd"] += 1 if m.dnd else 0
        line["notice"] += 1 if m.notice else 0
        line["saved"] += m.saved

    sorted_lines = sorted(lines.values(), key=lambda ln: (-ln["duty"], ln["app"], ln["pkg"]))
    for ln in sorted_lines:
        ln["note"] = line_note(ln)
    balance = tariff.exemption_per_day - total_duty
    return {
        "totalDuty": total_duty,
        "savedSeconds": saved,
        "eventCount": event_count,
        "nightEvents": night_events,
        "nightRatio": round(night_events / event_count, 4) if event_count else 0.0,
        "bursts": bursts,
        "dndEvents": dnd_events,
        "noticeEvents": notice_events,
        "balance": balance,
        "status": "paid" if total_duty <= tariff.exemption_per_day else "owed",
        "exemption": tariff.exemption_per_day,
        "byHour": by_hour,
        "devices": sorted({m.device_id for m in marks}),
        "lines": sorted_lines,
        "marks": marks,
    }


# --------------------------------------------------------------------------- #
# §4 fixture 构造
# --------------------------------------------------------------------------- #


def build_conversation_fixture(day: str, device: str = "verify-conversation") -> list:
    cases = ["work", "friends", "free", "casual", "family", "work", "unknown", ""]
    return [
        Event(f"class-{i}", "com.tencent.mm", "WeChat", ts_at(day, 10, 0, i),
              device_id=device, dnd=kind == "free", notice=kind == "free",
              conversation_class=kind)
        for i, kind in enumerate(cases)
    ]


def build_main_fixture(day: str, device: str = "verify-device-1") -> list:
    """v2026-11-c 主 fixture（与 backend/verify.sh §6 同一组事件，北京时间）。

    com.verify.a      4 条，间隔 60 秒           → 8 + 3×4 = 20，saved 12
    com.verify.dnd    2 条，10:05/10:06，免打扰   → 4 + 2 = 6，saved 10
    com.verify.notice 1 条，10:07，群发 @所有人   → 16，saved 0
    com.tencent.mm    3 条，第 2 条连发           → 4 + 2 + 4 = 10，saved 2
    com.verify.b      1 条，23:30 夜间            → 12，saved 0
    com.verify.mix    2 条，23:50/23:51 夜间+群发+免打扰（第 2 条还连发）
                                                  → 12 + 5 = 17，saved 31
    合计 81 秒 / saved 55 / events 13。
    """
    base = ts_at(day, 10, 0)
    events = []
    for idx, offset in enumerate((0, 60, 120, 180), start=1):
        events.append(Event(f"verify-a{idx}", PKG_A, "验证应用A", base + offset * 1000, device))
    for idx, offset in enumerate((300, 360), start=1):
        events.append(Event(f"verify-dnd{idx}", PKG_DND, "验证勿扰", base + offset * 1000,
                            device, dnd=True))
    events.append(Event("verify-notice1", PKG_NOTICE, "验证群发", base + 420 * 1000,
                        device, notice=True))
    for idx, offset in enumerate((600, 660, 1260), start=1):
        events.append(Event(f"verify-mm{idx}", PKG_MM, "微信", base + offset * 1000, device))
    events.append(Event("verify-b1", PKG_B, "验证应用B", ts_at(day, 23, 30), device))
    events.append(Event("verify-mix1", PKG_MIX, "验证混合", ts_at(day, 23, 50), device,
                        dnd=True, notice=True))
    events.append(Event("verify-mix2", PKG_MIX, "验证混合", ts_at(day, 23, 51), device,
                        dnd=True, notice=True))
    return events


def build_cap_fixture(day: str, device: str = "verify-cap-dev") -> list:
    """封顶 fixture：unit=30、夜间 + 群发，两条（第二条连发）。

    #1: 30×1.5×2.0 = 90 = min(raw, 30×3.0) → 90 秒（base 90，saved 0）
    #2: 30×1.5×2.0×0.4 = 36 ≤ 90      → 36 秒（base 90，saved 54）
    合计 126 秒 / saved 54。
    """
    return [
        Event("verify-cap1", PKG_CAP, "验证封顶", ts_at(day, 23, 10), device, notice=True),
        Event("verify-cap2", PKG_CAP, "验证封顶", ts_at(day, 23, 11), device, notice=True),
    ]


def build_edge_fixture(day: str):
    """连发/夜间/多设备边界 fixture，返回 (events, device_a, device_b)。

    - com.edge.burst（设备 A）：10:00:00、10:02:00（正好 120 秒，连发）、10:04:01（121 秒，不连发）
    - com.edge.n1..n4（设备 A，不同包名避免互相连发）：
      22:59:59（白天）、23:00:00（夜间）、06:59:59（夜间）、07:00:00（白天）
    - com.edge.other（设备 B）：11:00:00、11:01:00（设备 B 内连发）
    """
    dev_a = "verify-edge-a"
    dev_b = "verify-edge-b"
    events = [
        Event("edge-b1", "com.edge.burst", "边界应用", ts_at(day, 10, 0, 0), dev_a),
        Event("edge-b2", "com.edge.burst", "边界应用", ts_at(day, 10, 2, 0), dev_a),
        Event("edge-b3", "com.edge.burst", "边界应用", ts_at(day, 10, 4, 1), dev_a),
        Event("edge-n1", "com.edge.n1", "夜间应用1", ts_at(day, 22, 59, 59), dev_a),
        Event("edge-n2", "com.edge.n2", "夜间应用2", ts_at(day, 23, 0, 0), dev_a),
        Event("edge-n3", "com.edge.n3", "夜间应用3", ts_at(day, 6, 59, 59), dev_a),
        Event("edge-n4", "com.edge.n4", "夜间应用4", ts_at(day, 7, 0, 0), dev_a),
        Event("edge-other1", "com.edge.other", "设备B应用", ts_at(day, 11, 0, 0), dev_b),
        Event("edge-other2", "com.edge.other", "设备B应用", ts_at(day, 11, 1, 0), dev_b),
    ]
    return events, dev_a, dev_b


def build_cross_day_fixture(day: str, device: str = "verify-cross-dev"):
    """跨日重置 fixture：前一天 23:59:30 + 当天 00:00:30（间隔 60 秒，同一设备/包名）。

    §2：连发只在 Asia/Shanghai 自然日内判断，每天 0 点重置，跨日不算连发。
    因此当天事件不是连发：duty = ceil(8×1.0×1.5) = 12（夜间），saved = 0，bursts = 0。
    """
    prev_day = (datetime.fromisoformat(day) - timedelta(days=1)).date().isoformat()
    return [
        Event("cross-prev", "com.edge.cross", "跨日应用", ts_at(prev_day, 23, 59, 30), device),
        Event("cross-cur", "com.edge.cross", "跨日应用", ts_at(day, 0, 0, 30), device),
    ]


# --------------------------------------------------------------------------- #
# 检查结果记录
# --------------------------------------------------------------------------- #


class Reporter:
    def __init__(self, verbose: bool = False):
        self.verbose = verbose
        self.checks = 0
        self.fails = []   # (name, detail)
        self.warns = []   # (name, detail)

    def section(self, title: str):
        print(f"\n[{title}]")

    def ok(self, name: str):
        self.checks += 1
        print(f"  PASS  {name}")

    def fail(self, name: str, detail: str = ""):
        self.checks += 1
        self.fails.append((name, detail))
        print(f"  FAIL  {name}")
        if detail:
            for line in detail.splitlines():
                print(f"        {line}")

    def warn(self, name: str, detail: str = ""):
        self.warns.append((name, detail))
        print(f"  WARN  {name}")
        if detail:
            for line in detail.splitlines():
                print(f"        {line}")

    def check(self, name: str, cond: bool, detail: str = ""):
        if cond:
            self.ok(name)
        else:
            self.fail(name, detail or "condition is false")

    def eq(self, name: str, actual, expected):
        self.check(name, actual == expected, f"expected {expected!r}, got {actual!r}")

    def close(self, name: str, actual, expected, tol: float = 1e-9):
        try:
            cond = abs(float(actual) - float(expected)) <= tol
        except (TypeError, ValueError):
            cond = False
        self.check(name, cond, f"expected {expected!r}, got {actual!r}")

    def summary(self) -> int:
        passed = self.checks - len(self.fails)
        print(f"\n===== 结果：{passed}/{self.checks} 通过，"
              f"{len(self.fails)} 失败，{len(self.warns)} 提示 =====")
        for name, detail in self.fails:
            print(f"  FAIL: {name}" + (f" — {detail}" if detail else ""))
        for name, detail in self.warns:
            print(f"  WARN: {name}" + (f" — {detail}" if detail else ""))
        return 0 if not self.fails else 1


# --------------------------------------------------------------------------- #
# 纯手算自测
# --------------------------------------------------------------------------- #


def _case_mark(unit: int, pkg: str, night: bool, burst: bool,
               dnd: bool = False, notice: bool = False, day: str = FIXED_DAY) -> Marked:
    """用真实 mark() 路径造一条（或一对）事件，返回目标事件的计算结果。"""
    tariff = Tariff(unit_seconds=unit)
    base = ts_at(day, 23, 30) if night else ts_at(day, 10, 0)
    if burst:
        events = [
            Event("case-1", pkg, "CaseApp", base, dnd=dnd, notice=notice),
            Event("case-2", pkg, "CaseApp", base + 60 * 1000, dnd=dnd, notice=notice),
        ]
        return mark(events, tariff)[1]
    return mark([Event("case-1", pkg, "CaseApp", base, dnd=dnd, notice=notice)], tariff)[0]


def run_self_test(reporter: Reporter):
    # ---- 1. 常量与默认税则 ------------------------------------------------
    reporter.section("1. 规则常量与默认税则（§2/§6）")
    reporter.eq("TARIFF_VERSION", TARIFF_VERSION, "2026-11-d")
    reporter.eq("TARIFF_UPDATED_AT", TARIFF_UPDATED_AT, "2026-11-15T00:00:00Z")
    reporter.eq("默认单价 unitSeconds", DEFAULT_UNIT_SECONDS, 8)
    reporter.eq("默认免税额 exemptionPerDay", DEFAULT_EXEMPTION_PER_DAY, 240)
    reporter.eq("夜间系数 nightMultiplier", DEFAULT_NIGHT_MULTIPLIER, 1.5)
    reporter.eq("夜间起点 nightStartHour", NIGHT_START_HOUR, 23)
    reporter.eq("夜间终点 nightEndHour", NIGHT_END_HOUR, 7)
    reporter.eq("连发系数 burstMultiplier（折扣而非加成）", DEFAULT_BURST_MULTIPLIER, 0.4)
    reporter.eq("连发窗口 burstWindowSec", BURST_WINDOW_SEC, 120)
    reporter.eq("免打扰系数 dndMultiplier（v2026-11-c）", DEFAULT_DND_MULTIPLIER, 0.5)
    reporter.eq("群发系数 noticeMultiplier（v2026-11-c）", DEFAULT_NOTICE_MULTIPLIER, 2.0)
    reporter.eq("单条封顶 capFactor（v2026-11-c）", DEFAULT_CAP_FACTOR, 3.0)
    reporter.eq("默认 appMultiplier", APP_MULTIPLIER_DEFAULT, 1.0)
    reporter.eq("服务端 VERSION", SERVER_VERSION, "1.4.1")
    reporter.eq("前缀表条目数", len(APP_MULTIPLIERS), 9)
    expected_table = {
        "com.tencent.mm": 0.5,
        "com.tencent.mobileqq": 0.5,
        "com.tencent.tim": 0.5,
        "com.tencent.wework": 0.4,
        "com.alibaba.android.rimet": 0.4,
        "com.ss.android.lark": 0.4,
        "com.larksuite.suite": 0.4,
        "com.microsoft.teams": 0.4,
        "com.slack": 0.4,
    }
    for pkg, mult in expected_table.items():
        reporter.eq(f"前缀表 {pkg}", APP_MULTIPLIERS[pkg], mult)

    # ---- 2. appFactor 前缀匹配 -------------------------------------------
    reporter.section("2. appFactor 前缀匹配（默认 1.0）")
    cases = [
        ("com.tencent.mm", 0.5),
        ("com.tencent.mm.revoke", 0.5),
        ("com.tencent.mobileqq", 0.5),
        ("com.tencent.mobileqq.plugin", 0.5),
        ("com.tencent.tim", 0.5),
        ("com.tencent.wework", 0.4),
        ("com.tencent.wework.plugin", 0.4),
        ("com.alibaba.android.rimet", 0.4),
        ("com.ss.android.lark", 0.4),
        ("com.larksuite.suite", 0.4),
        ("com.microsoft.teams", 0.4),
        ("com.slack", 0.4),
        ("com.slack.workspace", 0.4),
        ("com.tencent.m", 1.0),        # 比表内前缀短，不匹配
        ("com.verify.a", 1.0),         # 未知包名
        ("com.tencent.mmx", 0.5),      # 前缀匹配语义：以 com.tencent.mm 开头
        ("", 1.0),                     # 防御性：空包名走默认
    ]
    for pkg, expected in cases:
        reporter.eq(f"appFactor({pkg or '<empty>'})", app_factor(pkg), expected)
    # 表内无重叠；若自定义表出现重叠，参考实现取最长前缀（审计清单里有说明）
    synthetic = Tariff(app_multipliers={"com.a": 0.5, "com.a.b": 0.3})
    reporter.eq("自定义重叠前缀取最长", app_factor("com.a.b.c", synthetic), 0.3)
    # 旧版 tariff 没有 appMultipliers 字段时的兼容
    legacy = Tariff(app_multipliers={}, app_multiplier_default=1.0)
    reporter.eq("旧版 tariff（空表）appFactor 默认 1.0", app_factor("com.tencent.mm", legacy), 1.0)

    # ---- 3. 夜间判定（北京时间边界） -------------------------------------
    reporter.section("3. 夜间判定（hour >= 23 或 < 7）")
    night_cases = [
        ((22, 59, 59), False),
        ((23, 0, 0), True),
        ((23, 30, 0), True),
        ((0, 0, 0), True),
        ((6, 59, 59), True),
        ((7, 0, 0), False),
        ((12, 0, 0), False),
    ]
    for (h, m, s), expected in night_cases:
        ts = ts_at(FIXED_DAY, h, m, s)
        reporter.eq(f"is_night({h:02d}:{m:02d}:{s:02d})", is_night(ts), expected)
    # 夜间 duty：08:00 → 8；23:00 → 12
    reporter.eq("白天首条 duty", _case_mark(8, PKG_A, False, False).duty, 8)
    reporter.eq("夜间首条 duty", _case_mark(8, PKG_A, True, False).duty, 12)

    # ---- 4. ceil 只做一次 + 最低 1 秒 ------------------------------------
    reporter.section("4. ceil 顺序与最低 1 秒（乘完所有系数后只取整一次）")
    ceil_cases = [
        # (unit, pkg, night, burst, 期望 duty, 说明)
        (8, PKG_A, False, False, 8, "普通白天首条 8"),
        (8, PKG_A, False, True, 4, "8×0.4=3.2→4"),
        (8, PKG_A, True, False, 12, "8×1.5=12"),
        (8, PKG_A, True, True, 5, "8×1.5×0.4=4.8→5"),
        (8, PKG_MM, False, False, 4, "8×0.5=4"),
        (8, PKG_MM, False, True, 2, "8×0.5×0.4=1.6→2（ceil 边界）"),
        (8, PKG_MM, True, False, 6, "8×0.5×1.5=6"),
        (8, PKG_MM, True, True, 3, "8×0.5×1.5×0.4=2.4→3（ceil 边界）"),
        (8, PKG_WEWORK, False, False, 4, "8×0.4=3.2→4（ceil 边界）"),
        (8, PKG_WEWORK, True, True, 2, "8×0.4×1.5×0.4=1.92→2"),
        (4, PKG_A, False, True, 2, "unit=4：4×0.4=1.6→2"),
        (4, PKG_WEWORK, False, True, 1, "unit=4：4×0.4×0.4=0.64→1（最低 1 秒）"),
        (2, PKG_WEWORK, False, True, 1, "unit=2：2×0.4×0.4=0.32→1（最低 1 秒）"),
        (2, PKG_WEWORK, False, False, 1, "unit=2：2×0.4=0.8→1（最低 1 秒）"),
        (2, PKG_A, False, False, 2, "unit=2：最低价就是 2"),
        (30, PKG_WEWORK, True, True, 8, "unit=30：30×0.4×1.5×0.4=7.2→8"),
        (5, PKG_WEWORK, False, False, 2, "整数边界 5×0.4=2.0→2，不得因浮点变 3"),
        (10, PKG_MM, False, True, 2, "整数边界 10×0.5×0.4=2.0→2"),
        (15, PKG_MM, False, True, 3, "整数边界 15×0.5×0.4=3.0→3"),
        (30, PKG_A, False, True, 12, "30×0.4=12.0→12"),
    ]
    for unit, pkg, night, burst, expected, note in ceil_cases:
        marked = _case_mark(unit, pkg, night, burst)
        reporter.eq(f"duty unit={unit} {pkg} night={night} burst={burst}（{note}）",
                    marked.duty, expected)
    # ceil 边界的精确数学检查（1.6→2、2.4→3），防止有人用 round/int 截断
    reporter.eq("ceil(8×0.5×0.4) 精确值", math.ceil(_q(8) * _q(0.5) * _q(0.4)), 2)
    reporter.eq("ceil(8×0.5×1.5×0.4) 精确值", math.ceil(_q(8) * _q(0.5) * _q(1.5) * _q(0.4)), 3)
    reporter.eq("ceil(1.6) 不得向下取整为 1", math.ceil(_q(8) * _q(0.5) * _q(0.4)), 2)
    reporter.eq("ceil(2.4) 不得向下取整为 2", math.ceil(_q(8) * _q(0.5) * _q(1.5) * _q(0.4)), 3)
    # 夜间连发 saved：ceil(8×0.5×1.5)=6 − 3 = 3
    reporter.eq("夜间连发 savedSeconds", _case_mark(8, PKG_MM, True, True).saved, 3)
    # 第 1 条（未连发）不产生 saved
    reporter.eq("首条不产生 savedSeconds", _case_mark(8, PKG_MM, True, False).saved, 0)

    # ---- 4b. v2026-11-c：免打扰 / 群发 / 封顶 ---------------------------
    reporter.section("4b. v2026-11-c 新系数（免打扰 ×0.5、群发 ×2.0、封顶 ×3.0）")

    def case(unit, pkg, night=False, burst=False, dnd=False, notice=False) -> Marked:
        return _case_mark(unit, pkg, night, burst, dnd=dnd, notice=notice)

    # 免打扰：没打断，减半；saved = base(全价) − duty
    m = case(8, PKG_A, dnd=True)
    reporter.eq("免打扰首条 duty（8×0.5=4）", m.duty, 4)
    reporter.eq("免打扰首条 saved（base 8 − 4）", m.saved, 4)
    reporter.eq("免打扰首条 dnd 标记", m.dnd, True)
    reporter.eq("免打扰首条 notice 标记", m.notice, False)
    reporter.eq("微信+免打扰 duty（8×0.5×0.5=2）", case(8, PKG_MM, dnd=True).duty, 2)
    reporter.eq("微信+免打扰 saved", case(8, PKG_MM, dnd=True).saved, 2)
    reporter.eq("微信+免打扰+连发 duty（8×0.5×0.5×0.4=0.8→1）",
                case(8, PKG_MM, burst=True, dnd=True).duty, 1)
    reporter.eq("微信+免打扰+连发 saved（base 4 − 1）",
                case(8, PKG_MM, burst=True, dnd=True).saved, 3)

    # 群发：一条提醒惊动所有人 → 加倍；saved 仍按 base 算，所以单条群发 saved 0
    m = case(8, PKG_A, notice=True)
    reporter.eq("群发首条 duty（8×2.0=16）", m.duty, 16)
    reporter.eq("群发首条 saved（base 16 → 0）", m.saved, 0)
    reporter.eq("群发首条 notice 标记", m.notice, True)
    reporter.eq("微信+群发 duty（8×0.5×2.0=8）", case(8, PKG_MM, notice=True).duty, 8)
    reporter.eq("夜间+群发 duty（8×1.5×2.0=24）", case(8, PKG_A, night=True, notice=True).duty, 24)

    # 混合（§2.4 最后一行）：夜间 + 群发 + 免打扰 + 连发
    m = case(8, PKG_A, night=True, burst=True, dnd=True, notice=True)
    reporter.eq("夜间+群发+免打扰+连发 duty（8×1.5×2×0.5×0.4=4.8→5）", m.duty, 5)
    reporter.eq("夜间+群发+免打扰+连发 saved（base 24 − 5）", m.saved, 19)
    m = case(8, PKG_A, night=True, dnd=True, notice=True)
    reporter.eq("夜间+群发+免打扰 duty（8×1.5×2×0.5=12）", m.duty, 12)
    reporter.eq("夜间+群发+免打扰 saved（base 24 − 12）", m.saved, 12)

    # 封顶：单条 <= unit × 3.0。unit=30 夜间+群发 = 90 = 上限（当前系数最大值）。
    m = case(30, PKG_A, night=True, notice=True)
    reporter.eq("unit=30 夜间+群发 duty = 90（= unit×3.0 封顶）", m.duty, 90)
    reporter.eq("unit=30 夜间+群发 saved（base 90 → 0）", m.saved, 0)
    m = case(30, PKG_A, night=True, burst=True, notice=True)
    reporter.eq("unit=30 夜间+群发+连发 duty（90×0.4=36，未触顶）", m.duty, 36)
    reporter.eq("unit=30 夜间+群发+连发 saved（base 90 − 36）", m.saved, 54)
    # 封顶函数本身：raw 超过上限时被截断
    reporter.eq("cap 截断：min(100, 8×3.0)=24",
                min(100.0, DEFAULT_UNIT_SECONDS * DEFAULT_CAP_FACTOR), 24)
    reporter.check("capFactor 使单条 duty 不超过 unit×capFactor",
                   all(case(unit, PKG_A, night=True, notice=True).duty
                       <= unit * DEFAULT_CAP_FACTOR for unit in range(2, 31)))
    # 默认标记：没有 dnd/notice 的旧客户端事件按 False 计
    legacy_mark = mark([Event("legacy", PKG_A, "A", ts_at(FIXED_DAY, 10, 0))], Tariff())[0]
    reporter.eq("旧客户端事件 dnd 默认 False", legacy_mark.dnd, False)
    reporter.eq("旧客户端事件 notice 默认 False", legacy_mark.notice, False)
    reporter.eq("旧客户端事件 duty 按 v2026-11-b 结果（8）", legacy_mark.duty, 8)
    classes = mark(build_conversation_fixture(FIXED_DAY))
    reporter.eq("同一微信应用内分类分别计价", [m.duty for m in classes], [8, 2, 0, 1, 2, 4, 4, 2])
    reporter.eq("连发不跨类别且 0 秒不触发连发", [m.burst for m in classes],
                [False, False, False, False, False, True, False, True])

    # ---- 5. 连发窗口 / 设备与包名隔离 -----------------------------------
    reporter.section("5. 连发窗口（≤120 秒，第二条件起；自然日内重置）与隔离维度")
    tariff = Tariff()
    day = FIXED_DAY
    base = ts_at(day, 10, 0)
    pair = lambda gap_s, pkg=PKG_A, dev="d1", dev2=None: mark([  # noqa: E731
        Event("p1", pkg, "A", base, dev),
        Event("p2", pkg, "A", base + gap_s * 1000, dev2 or dev),
    ], tariff)
    marks = pair(120)
    reporter.eq("间隔正好 120 秒：第 2 条连发", marks[1].burst, True)
    reporter.eq("间隔 120 秒连发 duty（8×0.4=3.2→4）", marks[1].duty, 4)
    reporter.eq("间隔正好 120 秒：saved=8−4=4", marks[1].saved, 4)
    reporter.eq("间隔 121 秒：第 2 条不连发", pair(121)[1].burst, False)
    reporter.eq("间隔 121 秒 duty=8", pair(121)[1].duty, 8)
    reporter.eq("间隔 0 秒（同 ts）按连发处理", pair(0)[1].burst, True)
    reporter.eq("第 1 条永不连发", pair(60)[0].burst, False)
    # 同一包名跨设备不连发
    marks = mark([
        Event("p1", PKG_A, "A", base, "d1"),
        Event("p2", PKG_A, "A", base + 60 * 1000, "d2"),
    ], tariff)
    reporter.eq("不同设备不连发", marks[1].burst, False)
    reporter.eq("不同设备 duty=8", marks[1].duty, 8)
    # 不同包名不连发
    marks = mark([
        Event("p1", PKG_A, "A", base, "d1"),
        Event("p2", PKG_B, "B", base + 60 * 1000, "d1"),
    ], tariff)
    reporter.eq("不同包名不连发", marks[1].burst, False)
    # 连发只看相邻两条（中间隔开就重置）
    marks = mark([
        Event("p1", PKG_A, "A", base, "d1"),
        Event("p2", PKG_A, "A", base + 60 * 1000, "d1"),
        Event("p3", PKG_A, "A", base + 60 * 1000 + 121 * 1000, "d1"),
        Event("p4", PKG_A, "A", base + 60 * 1000 + 121 * 1000 + 60 * 1000, "d1"),
    ], tariff)
    reporter.eq("第 2 条连发", marks[1].burst, True)
    reporter.eq("中间超窗后第 3 条不连发", marks[2].burst, False)
    reporter.eq("第 4 条重新与第 3 条连发", marks[3].burst, True)
    # 输入乱序也要按 ts 排序
    shuffled = list(reversed(build_main_fixture(day)))
    ordered = mark(build_main_fixture(day), tariff)
    shuffled_marks = mark(shuffled, tariff)
    reporter.eq("乱序输入按 ts 排序后 duty 一致",
                [(m.event_id, m.duty, m.burst) for m in shuffled_marks],
                [(m.event_id, m.duty, m.burst) for m in ordered])
    # §2：连发只在 Asia/Shanghai 自然日内判断，每天 0 点重置，跨日不算连发
    prev_day = (datetime.fromisoformat(day) - timedelta(days=1)).date().isoformat()
    cross_events = [
        Event("x-prev", PKG_A, "A", ts_at(prev_day, 23, 59, 30), "d1"),
        Event("x-cur", PKG_A, "A", ts_at(day, 0, 0, 30), "d1"),
    ]
    cross_marks = mark(cross_events, tariff)
    reporter.eq("跨日 60 秒内同一应用：当天事件不连发", cross_marks[1].burst, False)
    reporter.eq("跨日重置后当天事件 duty（夜间 8×1.5=12）", cross_marks[1].duty, 12)
    reporter.eq("跨日重置后 saved=0", cross_marks[1].saved, 0)
    cross_ref = compute(cross_events, tariff, day=day)
    reporter.eq("跨日 fixture 当天 totalDuty", cross_ref["totalDuty"], 12)
    reporter.eq("跨日 fixture 当天 bursts", cross_ref["bursts"], 0)
    reporter.eq("跨日 fixture 当天 savedSeconds", cross_ref["savedSeconds"], 0)
    # 但同一自然日内（0 点后）仍然连发
    same_day_marks = mark([
        Event("y-1", PKG_A, "A", ts_at(day, 0, 0, 30), "d1"),
        Event("y-2", PKG_A, "A", ts_at(day, 0, 1, 30), "d1"),
    ], tariff)
    reporter.eq("同一天内 0 点后 60 秒仍算连发", same_day_marks[1].burst, True)

    # ---- 6. savedSeconds 定义（v2026-11-c：全部优惠合计） ---------------
    reporter.section("6. savedSeconds = Σ( base − duty )，base 只含应用/夜间/群发（全部优惠）")
    fixture = build_main_fixture(FIXED_DAY, "verify-device-1")
    default_tariff = Tariff()
    ref = compute(fixture, default_tariff, day=FIXED_DAY)
    saved_by_id = {m.event_id: m.saved for m in ref["marks"]}
    reporter.eq("默认 fixture savedSeconds 合计", ref["savedSeconds"], 55)
    reporter.eq("verify-a 连发 3 条各 saved 4（base 8）",
                [saved_by_id[f"verify-a{i}"] for i in (2, 3, 4)], [4, 4, 4])
    reporter.eq("微信连发 1 条 saved 2", saved_by_id["verify-mm2"], 2)
    reporter.eq("夜间非连发 saved 0", saved_by_id["verify-b1"], 0)
    reporter.eq("免打扰非连发 saved 4（base 8 − 4）", saved_by_id["verify-dnd1"], 4)
    reporter.eq("免打扰+连发 saved 6（base 8 − 2）", saved_by_id["verify-dnd2"], 6)
    reporter.eq("群发单条 saved 0（base 16 − 16）", saved_by_id["verify-notice1"], 0)
    reporter.eq("夜间+群发+免打扰 saved 12（base 24 − 12）", saved_by_id["verify-mix1"], 12)
    reporter.eq("夜间+群发+免打扰+连发 saved 19（base 24 − 5）", saved_by_id["verify-mix2"], 19)
    reporter.eq("savedSeconds = 逐条 saved 之和",
                sum(saved_by_id.values()), ref["savedSeconds"])
    reporter.eq("无折扣事件 saved 必为 0（夜间/群发加成不算优惠）",
                all(m.saved == 0 for m in ref["marks"] if not m.dnd and not m.burst), True)
    reporter.eq("有折扣事件 saved 必 > 0（免打扰/连发）",
                all(m.saved > 0 for m in ref["marks"] if m.dnd or m.burst), True)
    reporter.eq("连发事件数", sum(1 for m in ref["marks"] if m.burst), 6)
    reporter.eq("免打扰事件数", sum(1 for m in ref["marks"] if m.dnd), 4)
    reporter.eq("群发事件数", sum(1 for m in ref["marks"] if m.notice), 3)

    # ---- 7. v2026-11-c 默认 fixture -------------------------------------
    reporter.section("7. v2026-11-c 默认 fixture：81 秒 / saved 55 / balance 159")
    duty_by_id = {m.event_id: m.duty for m in ref["marks"]}
    reporter.eq("verify-a 逐条 duty", [duty_by_id[f"verify-a{i}"] for i in (1, 2, 3, 4)],
                [8, 4, 4, 4])
    reporter.eq("免打扰 逐条 duty", [duty_by_id[f"verify-dnd{i}"] for i in (1, 2)], [4, 2])
    reporter.eq("群发 duty", duty_by_id["verify-notice1"], 16)
    reporter.eq("微信 逐条 duty", [duty_by_id[f"verify-mm{i}"] for i in (1, 2, 3)], [4, 2, 4])
    reporter.eq("夜间 逐条 duty", duty_by_id["verify-b1"], 12)
    reporter.eq("混合 逐条 duty",
                [duty_by_id["verify-mix1"], duty_by_id["verify-mix2"]], [12, 5])
    reporter.eq("总 duty", ref["totalDuty"], 81)
    reporter.eq("savedSeconds", ref["savedSeconds"], 55)
    reporter.eq("balance", ref["balance"], 159)
    reporter.eq("status", ref["status"], "paid")
    reporter.eq("events", ref["eventCount"], 13)
    reporter.eq("bursts", ref["bursts"], 6)
    reporter.eq("dndEvents", ref["dndEvents"], 4)
    reporter.eq("noticeEvents", ref["noticeEvents"], 3)
    reporter.eq("nightEvents", ref["nightEvents"], 3)
    reporter.eq("nightRatio", ref["nightRatio"], 0.2308)
    reporter.eq("byHour[10]", ref["byHour"][10], 10)
    reporter.eq("byHour[23]", ref["byHour"][23], 3)
    app_duty = {ln["pkg"]: ln["duty"] for ln in ref["lines"]}
    reporter.eq("verify-a 合计 duty", app_duty.get(PKG_A), 20)
    reporter.eq("免打扰 合计 duty", app_duty.get(PKG_DND), 6)
    reporter.eq("群发 合计 duty", app_duty.get(PKG_NOTICE), 16)
    reporter.eq("微信 合计 duty", app_duty.get(PKG_MM), 10)
    reporter.eq("夜间应用 合计 duty", app_duty.get(PKG_B), 12)
    reporter.eq("混合 合计 duty", app_duty.get(PKG_MIX), 17)

    # ---- 8. 自定义税则 fixture ------------------------------------------
    reporter.section("8. 自定义税则 fixture（unit=4 / exemption=120 → 41 秒 / saved 27 / balance 79）")
    custom_tariff = Tariff(unit_seconds=4, exemption_per_day=120)
    custom = compute(fixture, custom_tariff, day=FIXED_DAY)
    custom_duty = {m.event_id: m.duty for m in custom["marks"]}
    reporter.eq("verify-a 逐条 duty", [custom_duty[f"verify-a{i}"] for i in (1, 2, 3, 4)],
                [4, 2, 2, 2])
    reporter.eq("免打扰 逐条 duty", [custom_duty[f"verify-dnd{i}"] for i in (1, 2)], [2, 1])
    reporter.eq("群发 duty", custom_duty["verify-notice1"], 8)
    reporter.eq("微信 逐条 duty", [custom_duty[f"verify-mm{i}"] for i in (1, 2, 3)], [2, 1, 2])
    reporter.eq("夜间 逐条 duty", custom_duty["verify-b1"], 6)
    reporter.eq("混合 逐条 duty",
                [custom_duty["verify-mix1"], custom_duty["verify-mix2"]], [6, 3])
    reporter.eq("自定义总 duty", custom["totalDuty"], 41)
    reporter.eq("自定义 savedSeconds", custom["savedSeconds"], 27)
    reporter.eq("自定义 balance", custom["balance"], 79)
    reporter.eq("自定义 status", custom["status"], "paid")
    reporter.eq("自定义 events", custom["eventCount"], 13)
    custom_app_duty = {ln["pkg"]: ln["duty"] for ln in custom["lines"]}
    reporter.eq("自定义 verify-a 合计 duty", custom_app_duty.get(PKG_A), 10)
    reporter.eq("自定义 免打扰 合计 duty", custom_app_duty.get(PKG_DND), 3)
    reporter.eq("自定义 群发 合计 duty", custom_app_duty.get(PKG_NOTICE), 8)
    reporter.eq("自定义 微信 合计 duty", custom_app_duty.get(PKG_MM), 5)
    reporter.eq("自定义 夜间 合计 duty", custom_app_duty.get(PKG_B), 6)
    reporter.eq("自定义 混合 合计 duty", custom_app_duty.get(PKG_MIX), 9)

    # ---- 8b. 封顶 fixture（unit=30，夜间+群发） -------------------------
    reporter.section("8b. 封顶 fixture（unit=30，23:10/23:11 夜间+群发 → 90 + 36 = 126 / saved 54）")
    cap_tariff = Tariff(unit_seconds=30)
    cap_fixture = build_cap_fixture(FIXED_DAY)
    cap_ref = compute(cap_fixture, cap_tariff, day=FIXED_DAY)
    cap_duty = {m.event_id: m.duty for m in cap_ref["marks"]}
    reporter.eq("cap#1 duty = 90 = unit×3.0（恰好触顶）", cap_duty["verify-cap1"], 90)
    reporter.eq("cap#2 duty = 36（90×0.4，未触顶）", cap_duty["verify-cap2"], 36)
    reporter.eq("cap 合计 duty", cap_ref["totalDuty"], 126)
    reporter.eq("cap savedSeconds（base 90 → 54）", cap_ref["savedSeconds"], 54)
    reporter.eq("cap bursts", cap_ref["bursts"], 1)
    reporter.eq("cap nightEvents / noticeEvents",
                (cap_ref["nightEvents"], cap_ref["noticeEvents"]), (2, 2))
    reporter.eq("cap 单条不超过 unit×capFactor",
                max(m.duty for m in cap_ref["marks"]) <= 30 * DEFAULT_CAP_FACTOR, True)

    # ---- 9. 免税额边界 ---------------------------------------------------
    reporter.section("9. 免税额与 status 边界（balance = exemption − Σduty，Σduty=81）")
    reporter.eq("total=81 / exemption=81 → balance 0",
                compute(fixture, Tariff(exemption_per_day=81), day=FIXED_DAY)["balance"], 0)
    reporter.eq("total=81 / exemption=81 → paid",
                compute(fixture, Tariff(exemption_per_day=81), day=FIXED_DAY)["status"], "paid")
    reporter.eq("total=81 / exemption=80 → balance -1",
                compute(fixture, Tariff(exemption_per_day=80), day=FIXED_DAY)["balance"], -1)
    reporter.eq("total=81 / exemption=80 → owed",
                compute(fixture, Tariff(exemption_per_day=80), day=FIXED_DAY)["status"], "owed")
    reporter.eq("exemption=0 → balance -total",
                compute(fixture, Tariff(exemption_per_day=0), day=FIXED_DAY)["balance"], -81)
    reporter.eq("自定义 exemption 生效", compute(fixture, custom_tariff, day=FIXED_DAY)["exemption"], 120)

    # ---- 10. 精确有理数与浮点实现一致性 ----------------------------------
    reporter.section("10. 浮点与精确算数一致性（unit 2–30 × 全部系数组合，含封顶）")
    diffs = []
    for unit in range(2, 31):
        for af in (1.0, 0.5, 0.4):
            for nf in (1.0, 1.5):
                for nm in (1.0, 2.0):
                    for dm in (1.0, 0.5):
                        for bf in (1.0, 0.4):
                            exact_mult = _q(af) * _q(nf) * _q(nm) * _q(dm) * _q(bf)
                            raw_exact = _q(unit) * exact_mult
                            capped = min(raw_exact, _q(unit) * _q(DEFAULT_CAP_FACTOR))
                            exact = max(1, math.ceil(capped))
                            raw_float = unit * af * nf * nm * dm * bf
                            capped_float = min(raw_float, unit * DEFAULT_CAP_FACTOR)
                            floaty = max(1, math.ceil(capped_float - 1e-9))
                            if exact != floaty:
                                diffs.append((unit, af, nf, nm, dm, bf, exact, floaty))
    reporter.check("全部组合 float 路径与精确路径一致（无浮点 off-by-one）",
                   not diffs, f"以下组合不一致（unit, af, nf, nm, dm, bf, 精确, 浮点）：{diffs}")
    # 自测 fixture 的每条事件也交叉核对 float / exact 两条路径
    float_mismatch = [m.event_id for m in ref["marks"] if m.duty != m.duty_float]
    reporter.check("默认 fixture 逐条 float/exact duty 一致", not float_mismatch,
                   f"不一致事件：{float_mismatch}")
    float_saved_mismatch = [m.event_id for m in ref["marks"] if m.saved != m.saved_float]
    reporter.check("默认 fixture 逐条 float/exact saved 一致", not float_saved_mismatch,
                   f"不一致事件：{float_saved_mismatch}")
    cap_float_mismatch = [m.event_id for m in cap_ref["marks"] if m.duty != m.duty_float]
    reporter.check("封顶 fixture 逐条 float/exact duty 一致", not cap_float_mismatch,
                   f"不一致事件：{cap_float_mismatch}")

    # ---- 11. 连发折叠/明细口径辅助检查 -----------------------------------
    reporter.section("11. 明细行 note 与折叠辅助口径（§2.3 固定顺序）")
    line_by_pkg = {ln["pkg"]: ln for ln in ref["lines"]}
    reporter.eq("verify-a 折叠行条数", line_by_pkg[PKG_A]["count"], 4)
    reporter.eq("verify-a 行 note", line_by_pkg[PKG_A]["note"], "连发 3 条")
    reporter.eq("微信 行 note", line_by_pkg[PKG_MM]["note"], "连发 1 条")
    reporter.eq("免打扰 行 note", line_by_pkg[PKG_DND]["note"], "连发 1 条 · 免打扰 2 条")
    reporter.eq("群发 行 note", line_by_pkg[PKG_NOTICE]["note"], "群发 1 条")
    reporter.eq("夜间 行 note", line_by_pkg[PKG_B]["note"], "夜间 1 条")
    reporter.eq("混合 行 note", line_by_pkg[PKG_MIX]["note"],
                "夜间 2 条 · 连发 1 条 · 免打扰 2 条 · 群发 2 条")
    reporter.eq("默认 fixture 有连发节约的行数",
                len([ln for ln in ref["lines"] if ln["burst"] > 0]), 4)
    reporter.eq("verify-a 行 saved", line_by_pkg[PKG_A]["saved"], 12)
    reporter.eq("微信 行 saved", line_by_pkg[PKG_MM]["saved"], 2)
    reporter.eq("免打扰 行 saved", line_by_pkg[PKG_DND]["saved"], 10)
    reporter.eq("混合 行 saved", line_by_pkg[PKG_MIX]["saved"], 31)
    reporter.eq("行 dnd/notice 字段", (line_by_pkg[PKG_DND]["dnd"],
                                   line_by_pkg[PKG_NOTICE]["notice"]), (2, 1))


# --------------------------------------------------------------------------- #
# HTTP 客户端（urllib，仅标准库）
# --------------------------------------------------------------------------- #


class Client:
    def __init__(self, base: str, timeout: float = 15.0):
        self.base = base.rstrip("/")
        self.timeout = timeout

    def request(self, method: str, path: str, body=None, token=None, device_id=None):
        url = self.base + path
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if device_id:
            headers["X-Device-Id"] = device_id
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                parsed = json.loads(raw.decode("utf-8")) if raw else None
                return resp.status, parsed
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            try:
                parsed = json.loads(raw.decode("utf-8")) if raw else None
            except (UnicodeDecodeError, json.JSONDecodeError):
                parsed = {"raw": raw[:500].decode("utf-8", "replace")}
            return exc.code, parsed
        except urllib.error.URLError as exc:
            raise RuntimeError(f"无法连接 {url}：{exc.reason}") from exc


def tariff_from_server(payload: dict) -> Tariff:
    """把 GET /v1/tariff 的 JSON 转成参考 Tariff；缺字段会抛 KeyError（记为失败）。"""
    required = [
        "unitSeconds", "exemptionPerDay", "nightMultiplier", "nightStartHour",
        "nightEndHour", "burstMultiplier", "burstWindowSec",
        # v2026-11-c 起必须有这三个系数，否则参考实现无法与服务端逐位对齐
        "dndMultiplier", "noticeMultiplier", "capFactor",
    ]
    missing = [key for key in required if key not in payload]
    if missing:
        raise KeyError(f"tariff 缺字段：{missing}；实际字段：{sorted(payload)}")
    app_map = payload.get("appMultipliers")
    if not isinstance(app_map, dict):
        raise KeyError(f"tariff.appMultipliers 缺失或不是对象：{app_map!r}")
    return Tariff(
        unit_seconds=int(payload["unitSeconds"]),
        exemption_per_day=int(payload["exemptionPerDay"]),
        night_multiplier=float(payload["nightMultiplier"]),
        night_start_hour=int(payload["nightStartHour"]),
        night_end_hour=int(payload["nightEndHour"]),
        burst_multiplier=float(payload["burstMultiplier"]),
        burst_window_sec=int(payload["burstWindowSec"]),
        dnd_multiplier=float(payload["dndMultiplier"]),
        notice_multiplier=float(payload["noticeMultiplier"]),
        cap_factor=float(payload["capFactor"]),
        app_multipliers={str(k): float(v) for k, v in app_map.items()},
        app_multiplier_default=float(payload.get("appMultiplierDefault", 1.0)),
        version=str(payload.get("version", "")),
        updated_at=str(payload.get("updatedAt", "")),
    )


def compare_ref_invoice(ref: dict, inv: dict, tag: str = "") -> list:
    """把参考计算与服务端 invoice 逐字段比对，返回差异列表（空 = 一致）。"""
    problems = []

    def eq(field, expected, actual):
        if expected != actual:
            problems.append(f"{field}: 参考 {expected!r} / 服务端 {actual!r}")

    def close(field, expected, actual, tol=1e-6):
        try:
            if abs(float(expected) - float(actual)) > tol:
                problems.append(f"{field}: 参考 {expected!r} / 服务端 {actual!r}")
        except (TypeError, ValueError):
            problems.append(f"{field}: 参考 {expected!r} / 服务端不可比较 {actual!r}")

    eq("totalDuty", ref["totalDuty"], inv.get("totalDuty"))
    eq("exemption", ref["exemption"], inv.get("exemption"))
    eq("balance", ref["balance"], inv.get("balance"))
    eq("status", ref["status"], inv.get("status"))
    eq("tariffVersion", TARIFF_VERSION, inv.get("tariffVersion"))

    stats = inv.get("stats")
    if not isinstance(stats, dict):
        problems.append(f"stats 缺失或不是对象：{stats!r}")
    else:
        eq("stats.events", ref["eventCount"], stats.get("events"))
        eq("stats.bursts", ref["bursts"], stats.get("bursts"))
        close("stats.nightRatio", ref["nightRatio"], stats.get("nightRatio"), 1e-3)
        eq("stats.savedSeconds", ref["savedSeconds"], stats.get("savedSeconds"))
        eq("stats.dndEvents", ref["dndEvents"], stats.get("dndEvents"))
        eq("stats.noticeEvents", ref["noticeEvents"], stats.get("noticeEvents"))
        eq("stats.byHour", ref["byHour"], stats.get("byHour"))
        eq("stats.devices", ref["devices"], sorted(stats.get("devices") or []))

    server_lines = {}
    for line in inv.get("lines") or []:
        key = (line.get("app"), line.get("pkg"))
        server_lines[key] = line
    ref_lines = {(ln["app"], ln["pkg"]): ln for ln in ref["lines"]}
    if set(server_lines) != set(ref_lines):
        problems.append(
            f"lines 键不一致：参考 {sorted(ref_lines)} / 服务端 {sorted(server_lines)}")
    for key, expected_line in ref_lines.items():
        actual_line = server_lines.get(key)
        if actual_line is None:
            continue
        for field in ("count", "duty", "night", "burst", "dnd", "notice"):
            eq(f"line{key}.{field}", expected_line[field], actual_line.get(field))
        # note 顺序固定（夜间 · 连发 · 免打扰 · 群发），参考实现直接逐字比对
        eq(f"line{key}.note", expected_line["note"], actual_line.get("note") or "")
    return problems


def invoice_mismatches(ref: dict, inv: dict) -> list:
    return compare_ref_invoice(ref, inv)


def _check_tariff(reporter: Reporter, payload: dict, expected: Tariff, tag: str):
    """比对 GET /v1/tariff（或 POST /v1/settings 返回）与期望税则。"""
    try:
        tariff = tariff_from_server(payload)
    except (KeyError, TypeError, ValueError) as exc:
        reporter.fail(f"{tag} 结构解析", str(exc))
        return None
    reporter.eq(f"{tag} version", tariff.version, TARIFF_VERSION)
    reporter.eq(f"{tag} updatedAt", tariff.updated_at, TARIFF_UPDATED_AT)
    reporter.eq(f"{tag} unitSeconds", tariff.unit_seconds, expected.unit_seconds)
    reporter.eq(f"{tag} exemptionPerDay", tariff.exemption_per_day, expected.exemption_per_day)
    reporter.eq(f"{tag} nightMultiplier", tariff.night_multiplier, expected.night_multiplier)
    reporter.eq(f"{tag} nightStartHour", tariff.night_start_hour, expected.night_start_hour)
    reporter.eq(f"{tag} nightEndHour", tariff.night_end_hour, expected.night_end_hour)
    reporter.eq(f"{tag} burstMultiplier", tariff.burst_multiplier, expected.burst_multiplier)
    reporter.eq(f"{tag} burstWindowSec", tariff.burst_window_sec, expected.burst_window_sec)
    reporter.eq(f"{tag} dndMultiplier", tariff.dnd_multiplier, expected.dnd_multiplier)
    reporter.eq(f"{tag} noticeMultiplier", tariff.notice_multiplier, expected.notice_multiplier)
    reporter.eq(f"{tag} capFactor", tariff.cap_factor, expected.cap_factor)
    reporter.eq(f"{tag} appMultiplierDefault", tariff.app_multiplier_default,
                expected.app_multiplier_default)
    for pkg, mult in expected.app_multipliers.items():
        reporter.close(f"{tag} appMultipliers[{pkg}]", tariff.app_multipliers.get(pkg), mult)
    reporter.check(f"{tag} appMultipliers 无缺失/多余",
                   set(tariff.app_multipliers) == set(expected.app_multipliers),
                   f"服务端 {sorted(tariff.app_multipliers)} / 期望 {sorted(expected.app_multipliers)}")
    return tariff


# --------------------------------------------------------------------------- #
# 端到端模式
# --------------------------------------------------------------------------- #


def run_base(reporter: Reporter, args) -> int:
    client = Client(args.base, timeout=args.timeout)
    day = args.date or datetime.now(TZ).date().isoformat()
    device = "verify-tariff-dev-a"
    device_b = "verify-tariff-dev-b"
    reporter.section("B0. 服务可达性与版本")
    try:
        status, health = client.request("GET", "/health")
    except RuntimeError as exc:
        reporter.fail("后端可达", str(exc))
        return 2
    reporter.eq("GET /health 状态码", status, 200)
    reporter.eq("health.status", (health or {}).get("status"), "ok")
    reporter.eq("health.service", (health or {}).get("service"), "quiet-ledger")
    reporter.eq("服务端 VERSION", (health or {}).get("version"), SERVER_VERSION)

    class_device = "verify-conversation"
    status, class_account = client.request("POST", "/v1/accounts", {"deviceId": class_device})
    reporter.eq("分类测试账户创建", status, 200)
    class_token = (class_account or {}).get("token")
    class_events = build_conversation_fixture(day, class_device)
    class_payload = [e.as_api() for e in class_events]
    for raw in class_payload:
        raw.pop("title")
    class_payload[-1].pop("conversationClass")
    status, class_ingest = client.request("POST", "/v1/events", {
        "deviceId": class_device, "events": class_payload,
    }, token=class_token, device_id=class_device)
    reporter.eq("分类事件入账状态", status, 200)
    reporter.eq("分类事件均可省略标题", (class_ingest or {}).get("stored"), len(class_events))
    status, class_invoice = client.request(
        "GET", f"/v1/invoice?date={day}&deviceId=all", token=class_token, device_id=class_device,
    )
    reporter.eq("分类票据状态", status, 200)
    class_ref = compute(class_events, Tariff(), day=day)
    class_mismatches = invoice_mismatches(class_ref, class_invoice or {})
    reporter.check("分类票据与独立参考一致", not class_mismatches, "\n".join(class_mismatches))

    # ---- 建账户 ----------------------------------------------------------
    reporter.section("B1. 建账户 + 默认税则")
    status, account = client.request("POST", "/v1/accounts", {
        "deviceId": device,
        "model": "verify_tariff.py",
        "appVersion": "reference",
    })
    reporter.eq("POST /v1/accounts 状态码", status, 200)
    account = account or {}
    token = account.get("token")
    account_code = account.get("accountCode")
    reporter.check("返回 accountCode 形如 QL-XXXX",
                   bool(re.match(r"^QL-[A-Z0-9]{4}$", account_code or "")),
                   f"got {account_code!r}")
    reporter.check("返回 token", isinstance(token, str) and len(token or "") >= 16,
                   f"got {token!r}")
    reporter.eq("返回 tariffVersion", account.get("tariffVersion"), TARIFF_VERSION)
    reporter.eq("返回 deviceId", account.get("deviceId"), device)
    if not token:
        reporter.fail("无法继续", "建账户未拿到 token；后端可能未实现该接口")
        return 2

    status, tariff_payload = client.request("GET", "/v1/tariff", token=token, device_id=device)
    reporter.eq("GET /v1/tariff 状态码", status, 200)
    default_tariff = _check_tariff(reporter, tariff_payload or {}, Tariff(), "默认 tariff")
    if default_tariff is None:
        reporter.fail("无法继续", "tariff 结构不可用")
        return 2

    # ---- 灌 fixture ------------------------------------------------------
    reporter.section("B2. 灌 v2026-11-c 主 fixture（13 条事件）")
    fixture = build_main_fixture(day, device)
    status, ingest = client.request("POST", "/v1/events", {
        "deviceId": device,
        "events": [e.as_api() for e in fixture],
    }, token=token, device_id=device)
    reporter.eq("POST /v1/events 状态码", status, 200)
    ingest = ingest or {}
    reporter.eq("accepted", ingest.get("accepted"), 13)
    reporter.eq("stored", ingest.get("stored"), 13)
    reporter.eq("duplicated", ingest.get("duplicated"), 0)
    reporter.eq("invalid", ingest.get("invalid", 0), 0)

    # ---- 默认发票 --------------------------------------------------------
    reporter.section("B3. 默认发票逐字段比对（81 秒 / saved 55 / balance 159）")
    status, invoice = client.request(
        "GET", f"/v1/invoice?date={day}&deviceId={device}", token=token, device_id=device)
    reporter.eq("GET /v1/invoice 状态码", status, 200)
    invoice = invoice or {}
    reporter.eq("invoice.date", invoice.get("date"), day)
    reporter.eq("invoice.accountCode", invoice.get("accountCode"), account_code)
    reporter.check("invoice.invoiceId 形如 INV-*",
                   str(invoice.get("invoiceId", "")).startswith("INV-"),
                   f"got {invoice.get('invoiceId')!r}")
    reporter.eq("invoice.totalDuty", invoice.get("totalDuty"), 81)
    reporter.eq("invoice.exemption", invoice.get("exemption"), 240)
    reporter.eq("invoice.balance", invoice.get("balance"), 159)
    reporter.eq("invoice.status", invoice.get("status"), "paid")
    stats = invoice.get("stats") or {}
    reporter.eq("invoice.stats.events", stats.get("events"), 13)
    reporter.eq("invoice.stats.bursts", stats.get("bursts"), 6)
    reporter.eq("invoice.stats.savedSeconds", stats.get("savedSeconds"), 55)
    reporter.eq("invoice.stats.dndEvents", stats.get("dndEvents"), 4)
    reporter.eq("invoice.stats.noticeEvents", stats.get("noticeEvents"), 3)
    ref = compute(fixture, default_tariff, day=day)
    mismatches = invoice_mismatches(ref, invoice)
    reporter.check("默认发票与独立参考逐字段一致", not mismatches,
                   "\n".join(mismatches))
    # 明细 note（v2026-11-c 顺序：夜间、连发、免打扰、群发）
    line_notes = {(ln.get("pkg"), ln.get("app")): (ln.get("note") or "")
                  for ln in invoice.get("lines") or []}
    a_note = line_notes.get((PKG_A, "验证应用A"), "")
    dnd_note = line_notes.get((PKG_DND, "验证勿扰"), "")
    notice_note = line_notes.get((PKG_NOTICE, "验证群发"), "")
    mm_note = line_notes.get((PKG_MM, "微信"), "")
    b_note = line_notes.get((PKG_B, "验证应用B"), "")
    mix_note = line_notes.get((PKG_MIX, "验证混合"), "")
    reporter.eq("verify-a note", a_note, "连发 3 条")
    reporter.eq("微信 note", mm_note, "连发 1 条")
    reporter.eq("免打扰 note", dnd_note, "连发 1 条 · 免打扰 2 条")
    reporter.eq("群发 note", notice_note, "群发 1 条")
    reporter.eq("夜间 note", b_note, "夜间 1 条")
    reporter.eq("混合 note（夜间/连发/免打扰/群发）", mix_note,
                "夜间 2 条 · 连发 1 条 · 免打扰 2 条 · 群发 2 条")

    # ---- 周报 / 公开收据走同一套逻辑 ------------------------------------
    reporter.section("B4. 周报与公开收据同源（无第二份计价逻辑）")
    status, weekly = client.request(
        "GET", f"/v1/weekly?days=1&end={day}", token=token, device_id=device)
    reporter.eq("GET /v1/weekly 状态码", status, 200)
    weekly = weekly or {}
    reporter.eq("周报 totalDuty = 发票 totalDuty", weekly.get("totalDuty"), invoice.get("totalDuty"))
    reporter.eq("周报 totalEvents", weekly.get("totalEvents"), 13)
    reporter.eq("周报 tariffVersion", weekly.get("tariffVersion"), TARIFF_VERSION)
    invoice_id = invoice.get("invoiceId")
    status, share = client.request("POST", "/v1/receipt-shares", {"date": day},
                                   token=token, device_id=device)
    reporter.eq("创建收据分享链接 HTTP 状态码", status, 200)
    receipt_url = f"{client.base}/r/{(share or {}).get('shareId', '')}"
    receipt_ok = False
    try:
        with urllib.request.urlopen(receipt_url, timeout=args.timeout) as resp:
            html = resp.read().decode("utf-8", "replace")
            reporter.eq("公开收据 HTTP 状态码", resp.status, 200)
            reporter.check("公开收据 HTML 含 invoiceId", str(invoice_id) in html)
            if str(invoice.get("totalDuty")) not in html:
                reporter.warn("公开收据 HTML 未直接出现 totalDuty 数字",
                              "可能是 HTML 结构不同；请人工核对金额")
            receipt_ok = True
    except urllib.error.HTTPError as exc:
        reporter.fail("公开收据 HTTP 状态码", f"got {exc.code}")
    except urllib.error.URLError as exc:
        reporter.fail("公开收据可达", str(exc.reason))
    if not receipt_ok:
        reporter.warn("公开收据未深入校验", "跳过其余 HTML 断言")

    # ---- 自定义税则 ------------------------------------------------------
    reporter.section("B5. POST /v1/settings 自定义（unit=4 / exemption=120）")
    custom_tariff = Tariff(unit_seconds=4, exemption_per_day=120)
    status, settings_payload = client.request("POST", "/v1/settings", {
        "unitSeconds": 4,
        "exemptionPerDay": 120,
    }, token=token, device_id=device)
    reporter.eq("POST /v1/settings 状态码", status, 200)
    _check_tariff(reporter, settings_payload or {}, custom_tariff, "settings 返回")
    status, tariff_payload = client.request("GET", "/v1/tariff", token=token, device_id=device)
    reporter.eq("GET /v1/tariff 状态码（自定义后）", status, 200)
    _check_tariff(reporter, tariff_payload or {}, custom_tariff, "自定义 tariff")

    status, invoice_custom = client.request(
        "GET", f"/v1/invoice?date={day}&deviceId={device}", token=token, device_id=device)
    reporter.eq("GET /v1/invoice 状态码（自定义后）", status, 200)
    invoice_custom = invoice_custom or {}
    reporter.eq("自定义 totalDuty", invoice_custom.get("totalDuty"), 41)
    reporter.eq("自定义 exemption", invoice_custom.get("exemption"), 120)
    reporter.eq("自定义 balance", invoice_custom.get("balance"), 79)
    reporter.eq("自定义 status", invoice_custom.get("status"), "paid")
    custom_stats = invoice_custom.get("stats") or {}
    reporter.eq("自定义 stats.savedSeconds", custom_stats.get("savedSeconds"), 27)
    reporter.eq("自定义 stats.events", custom_stats.get("events"), 13)
    custom_ref = compute(fixture, custom_tariff, day=day)
    custom_mismatches = invoice_mismatches(custom_ref, invoice_custom)
    reporter.check("自定义发票与独立参考逐字段一致", not custom_mismatches,
                   "\n".join(custom_mismatches))

    # ---- 跨设备税则共享 --------------------------------------------------
    reporter.section("B6. 自定义税则跨设备共享（同账户）")
    status, invite = client.request("POST", "/v1/accounts/invite", {},
                                    token=token, device_id=device)
    reporter.eq("创建邀请码 HTTP 状态码", status, 200)
    status, joined = client.request("POST", "/v1/accounts/join", {
        "inviteCode": (invite or {}).get("inviteCode"),
        "deviceId": device_b,
        "model": "verify_tariff.py",
        "appVersion": "reference",
    })
    reporter.eq("POST /v1/accounts/join 状态码", status, 200)
    joined = joined or {}
    token_b = joined.get("token")
    reporter.check("加入设备返回 token", isinstance(token_b, str) and len(token_b or "") >= 16)
    if token_b:
        status, tariff_b = client.request("GET", "/v1/tariff", token=token_b, device_id=device_b)
        reporter.eq("设备 B GET /v1/tariff 状态码", status, 200)
        _check_tariff(reporter, tariff_b or {}, custom_tariff, "设备B tariff")
        status, invoice_b = client.request(
            "GET", f"/v1/invoice?date={day}&deviceId={device_b}", token=token_b, device_id=device_b)
        reporter.eq("设备 B GET /v1/invoice 状态码", status, 200)
        reporter.eq("设备 B 无事件：totalDuty=0",
                    (invoice_b or {}).get("totalDuty"), 0)
    else:
        reporter.fail("跨设备检查", "未拿到设备 B token")

    # ---- 恢复默认 --------------------------------------------------------
    reporter.section("B7. 恢复默认并复核（8 / 240 → 81 秒 / saved 55 / balance 159）")
    status, restore_payload = client.request("POST", "/v1/settings", {
        "unitSeconds": 8,
        "exemptionPerDay": 240,
    }, token=token, device_id=device)
    reporter.eq("POST /v1/settings 恢复默认 状态码", status, 200)
    _check_tariff(reporter, restore_payload or {}, Tariff(), "恢复后 settings 返回")
    status, invoice_restored = client.request(
        "GET", f"/v1/invoice?date={day}&deviceId={device}", token=token, device_id=device)
    reporter.eq("恢复后 GET /v1/invoice 状态码", status, 200)
    invoice_restored = invoice_restored or {}
    reporter.eq("恢复后 totalDuty", invoice_restored.get("totalDuty"), 81)
    reporter.eq("恢复后 savedSeconds",
                (invoice_restored.get("stats") or {}).get("savedSeconds"), 55)
    reporter.eq("恢复后 balance", invoice_restored.get("balance"), 159)
    restored_mismatches = invoice_mismatches(ref, invoice_restored)
    reporter.check("恢复后发票与独立参考逐字段一致", not restored_mismatches,
                   "\n".join(restored_mismatches))
    if token_b:
        status, tariff_after = client.request("GET", "/v1/tariff", token=token_b, device_id=device_b)
        reporter.eq("恢复后设备 B tariff 同步默认", status, 200)
        _check_tariff(reporter, tariff_after or {}, Tariff(), "恢复后设备B tariff")

    # ---- 非法设置校验 ----------------------------------------------------
    reporter.section("B8. POST /v1/settings 边界校验（unit 2–30 / exemption 0–1800）")
    invalid_cases = [
        ("unit=1 拒绝", {"unitSeconds": 1, "exemptionPerDay": 240}),
        ("unit=31 拒绝", {"unitSeconds": 31, "exemptionPerDay": 240}),
        ("unit=8.5 拒绝", {"unitSeconds": 8.5, "exemptionPerDay": 240}),
        ("exemption=-1 拒绝", {"unitSeconds": 8, "exemptionPerDay": -1}),
        ("exemption=1801 拒绝", {"unitSeconds": 8, "exemptionPerDay": 1801}),
    ]
    for name, body in invalid_cases:
        status, payload = client.request("POST", "/v1/settings", body,
                                         token=token, device_id=device)
        reporter.eq(f"{name}（状态码 400）", status, 400)
    status, tariff_unchanged = client.request("GET", "/v1/tariff", token=token, device_id=device)
    reporter.eq("非法设置后 tariff 未变（unit=8）",
                (tariff_unchanged or {}).get("unitSeconds"), 8)
    reporter.eq("非法设置后 tariff 未变（exemption=240）",
                (tariff_unchanged or {}).get("exemptionPerDay"), 240)

    # ---- 边界 fixture（120 秒窗口 / 夜间小时 / 多设备） -----------------
    if not args.no_edge:
        reporter.section("B9. 边界 fixture：120 秒窗口 / 夜间小时 / 多设备隔离")
        edge_device = "verify-edge-a"
        status, edge_account = client.request("POST", "/v1/accounts", {
            "deviceId": edge_device,
            "model": "verify_tariff.py",
            "appVersion": "reference",
        })
        reporter.eq("边界账户创建状态码", status, 200)
        edge_account = edge_account or {}
        edge_token = edge_account.get("token")
        edge_events, dev_a, dev_b = build_edge_fixture(day)
        for dev in (dev_a, dev_b):
            batch = [e.as_api() for e in edge_events if e.device_id == dev]
            status, ingest = client.request("POST", "/v1/events", {
                "deviceId": dev,
                "events": batch,
            }, token=edge_token, device_id=dev)
            reporter.eq(f"边界事件灌入 {dev}（状态码）", status, 200)
            reporter.eq(f"边界事件灌入 {dev}（stored）", (ingest or {}).get("stored"), len(batch))
        status, edge_invoice = client.request(
            "GET", f"/v1/invoice?date={day}&deviceId=all", token=edge_token, device_id=dev_a)
        reporter.eq("边界发票状态码", status, 200)
        edge_ref = compute(edge_events, Tariff(), day=day)
        reporter.eq("边界发票 totalDuty", (edge_invoice or {}).get("totalDuty"),
                    edge_ref["totalDuty"])
        edge_mismatches = invoice_mismatches(edge_ref, edge_invoice or {})
        reporter.check("边界发票与独立参考逐字段一致", not edge_mismatches,
                       "\n".join(edge_mismatches))
        # 单设备视图：只看设备 A
        status, device_invoice = client.request(
            "GET", f"/v1/invoice?date={day}&deviceId={dev_a}", token=edge_token, device_id=dev_a)
        reporter.eq("设备 A 发票状态码", status, 200)
        device_ref = compute([e for e in edge_events if e.device_id == dev_a], Tariff(), day=day)
        reporter.eq("设备 A 发票 totalDuty", (device_invoice or {}).get("totalDuty"),
                    device_ref["totalDuty"])
        reporter.eq("设备 A 发票 stats.devices", (device_invoice or {}).get("stats", {}).get("devices"),
                    [dev_a])

    # ---- 跨日重置：自然日内才判连发（§2 硬断言） ------------------------
    if not args.no_cross_day:
        reporter.section("B10. 跨日不算连发（连发按 Asia/Shanghai 自然日重置）")
        cross_device = "verify-cross-dev"
        status, cross_account = client.request("POST", "/v1/accounts", {
            "deviceId": cross_device,
            "model": "verify_tariff.py",
            "appVersion": "reference",
        })
        cross_token = (cross_account or {}).get("token")
        cross_events = build_cross_day_fixture(day, cross_device)
        status, ingest = client.request("POST", "/v1/events", {
            "deviceId": cross_device,
            "events": [e.as_api() for e in cross_events],
        }, token=cross_token, device_id=cross_device)
        reporter.eq("跨日 fixture 灌入状态码", status, 200)
        reporter.eq("跨日 fixture stored", (ingest or {}).get("stored"), 2)
        status, cross_invoice = client.request(
            "GET", f"/v1/invoice?date={day}&deviceId={cross_device}",
            token=cross_token, device_id=cross_device)
        reporter.eq("跨日发票状态码", status, 200)
        cross_invoice = cross_invoice or {}
        cross_stats = cross_invoice.get("stats") or {}
        cross_ref = compute(cross_events, Tariff(), day=day)
        reporter.eq("跨日：当天事件按正常价（非连发）12 秒",
                    cross_invoice.get("totalDuty"), 12)
        reporter.eq("跨日：stats.bursts = 0", cross_stats.get("bursts"), 0)
        reporter.eq("跨日：stats.savedSeconds = 0", cross_stats.get("savedSeconds"), 0)
        reporter.eq("跨日：events = 1（只算当天）", cross_stats.get("events"), 1)
        cross_mismatches = invoice_mismatches(cross_ref, cross_invoice)
        reporter.check("跨日发票与独立参考逐字段一致", not cross_mismatches,
                       "\n".join(cross_mismatches))

    # ---- 封顶账户（unit=30，夜间 + 群发） -------------------------------
    if not args.no_cap:
        reporter.section("B11. 封顶边界（unit=30，夜间+群发 → 90 = unit×3.0；连发后 126/54）")
        cap_device = "verify-cap-dev"
        status, cap_account = client.request("POST", "/v1/accounts", {
            "deviceId": cap_device,
            "model": "verify_tariff.py",
            "appVersion": "reference",
        })
        reporter.eq("封顶账户创建状态码", status, 200)
        cap_token = (cap_account or {}).get("token")
        cap_tariff = Tariff(unit_seconds=30)
        status, cap_settings = client.request("POST", "/v1/settings", {
            "unitSeconds": 30,
            "exemptionPerDay": 240,
        }, token=cap_token, device_id=cap_device)
        reporter.eq("封顶账户 POST /v1/settings 状态码", status, 200)
        _check_tariff(reporter, cap_settings or {}, cap_tariff, "封顶 tariff")
        cap_fixture = build_cap_fixture(day, cap_device)
        status, cap_ingest = client.request("POST", "/v1/events", {
            "deviceId": cap_device,
            "events": [e.as_api() for e in cap_fixture],
        }, token=cap_token, device_id=cap_device)
        reporter.eq("封顶事件灌入状态码", status, 200)
        reporter.eq("封顶事件 stored", (cap_ingest or {}).get("stored"), 2)
        status, cap_invoice = client.request(
            "GET", f"/v1/invoice?date={day}&deviceId={cap_device}",
            token=cap_token, device_id=cap_device)
        reporter.eq("封顶发票状态码", status, 200)
        cap_invoice = cap_invoice or {}
        reporter.eq("封顶发票 totalDuty = 90 + 36 = 126",
                    cap_invoice.get("totalDuty"), 126)
        cap_stats = cap_invoice.get("stats") or {}
        reporter.eq("封顶发票 savedSeconds = 54", cap_stats.get("savedSeconds"), 54)
        reporter.eq("封顶发票 bursts = 1", cap_stats.get("bursts"), 1)
        reporter.eq("封顶发票 noticeEvents = 2", cap_stats.get("noticeEvents"), 2)
        reporter.eq("封顶发票 dndEvents = 0", cap_stats.get("dndEvents"), 0)
        cap_ref = compute(cap_fixture, cap_tariff, day=day)
        reporter.eq("封顶单条不超过 unit×capFactor",
                    max(m.duty for m in cap_ref["marks"]) <= 30 * DEFAULT_CAP_FACTOR, True)
        cap_mismatches = invoice_mismatches(cap_ref, cap_invoice)
        reporter.check("封顶发票与独立参考逐字段一致", not cap_mismatches,
                       "\n".join(cap_mismatches))
    return 0


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #

EPILOG = """\
人工审计清单（详见 tools/AUDIT-CHECKLIST.md）：
  1. backend/app.py 的 build_day / invoice_payload / handle_weekly / handle_receipt
     共用同一份计价逻辑，没有第二份公式（grep UNIT_SECONDS / appMultipliers）。
  2. Model.kt Duty.mark 与 §2 逐位一致：前缀匹配、夜间 1.5、群发 2.0、免打扰 0.5、
     连发 0.4、单条封顶 unit×3.0、ceil 只在最后、最低 1 秒；连发按北京时间自然日重置。
  3. savedSeconds = 全部优惠合计（base − duty，base 只含应用/夜间/群发），
     不是只算连发；票据/明细/桌面组件文案统一「优惠已减 N 秒」。
  4. JSON 契约：GET /v1/tariff 新增 dndMultiplier / noticeMultiplier / capFactor
     （以及 appMultipliers / appMultiplierDefault）；invoice stats 新增
     dndEvents / noticeEvents；lines[] 新增 dnd / notice；旧 App 忽略新字段仍可用。
  5. 事件标记：NotifEvent.dnd/notice 为布尔（投递时算），只上送布尔不上送原文；
     旧事件/旧客户端缺字段按 false，票面与 v2026-11-b 一致。
  6. 隐私：Api.pushEvents 默认省略 title（Prefs.uploadTitles=false）；服务端 title 可缺省。
  7. 数据层：ledger.db v3 迁移 events.dnd/notice；旧库 onOpen 平滑补列。
  8. 设置页「计税规则」：单价 2–30、免税额 0–1800、恢复默认、应用并结算，
     离线先本地生效并标记待同步；自定义跨设备共享。
  9. 明细三处连发折叠默认收起；行标签 夜/连/静/群 与 MarkedEvent 字段一致。
 10. 官网税率表/示例/FAQ 数字与 v2026-11-c 一致；旧账户默认 8/240；verify.sh 新 fixture。
 11. 连发只在 Asia/Shanghai 自然日内判断（每天 0 点重置，跨日不算连发）。
 12. 封顶是护栏：当前系数最大值为夜间1.5×群发2.0=3.0，unit=30 时恰好 90 秒。
"""


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="独立复算并验证 QuietLedger 税率 v2026-11-c（纯标准库，不 import backend/app.py）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=EPILOG,
    )
    parser.add_argument("--base", metavar="URL",
                        help="端到端模式：如 http://127.0.0.1:8791（后端就绪后使用）")
    parser.add_argument("--date", metavar="YYYY-MM-DD",
                        help="--base 模式的结算日；默认北京时间今天")
    parser.add_argument("--timeout", type=float, default=15.0, metavar="SEC",
                        help="HTTP 超时秒数（默认 15）")
    parser.add_argument("--no-edge", action="store_true",
                        help="--base 模式跳过 120 秒窗口/夜间小时/多设备边界 fixture")
    parser.add_argument("--no-cross-day", action="store_true",
                        help="--base 模式跳过跨日自然日重置断言")
    parser.add_argument("--no-cap", action="store_true",
                        help="--base 模式跳过封顶账户（unit=30 夜间+群发）断言")
    parser.add_argument("--verbose", action="store_true", help="输出更多细节")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    reporter = Reporter(verbose=args.verbose)
    print("=" * 78)
    print("QuietLedger 税率独立验证 — v2026-11-c（参考实现：tools/verify_tariff.py）")
    print("规则来源：docs/TAX-RULES.md §2；不 import backend/app.py。")
    print("=" * 78)

    run_self_test(reporter)

    rc = 0
    if args.base:
        print("\n" + "=" * 78)
        print(f"端到端模式：--base {args.base}")
        print("=" * 78)
        rc = run_base(reporter, args)
    else:
        print("\n提示：加 --base http://127.0.0.1:PORT 可在后端就绪后做端到端逐字段比对。")

    exit_code = reporter.summary()
    if rc == 2:
        exit_code = 2
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
