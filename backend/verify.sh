#!/usr/bin/env bash
# QuietLedger (打扰税) end-to-end verification.
#
# Intended to run on the Aliyun ECS host as root, exercising the *public* URL:
#   bash backend/verify.sh
#
# It creates throw-away accounts, ingests a hand-computed v2026-11-d fixture,
# asserts the invoice math (appFactor + night + notice + dnd + burst + cap +
# total-discount savedSeconds), checks per-account custom tax rules
# (POST /v1/settings) and their restore, weekly / arbitrary-period report
# (GET /v1/report) / devices / meta / tariff / public receipt, the title-less
# (privacy default) ingest path, restarts the service and re-checks
# persistence, then confirms the pre-existing services are untouched.
# Exit code 0 == all checks passed.
#
# Override the endpoint with:  BASE=http://host/quiet-api bash verify.sh
# Skip the systemd restart step with:  SKIP_RESTART=1 bash verify.sh

set -uo pipefail

BASE="${BASE:-http://127.0.0.1:8791}"
TMP="$(mktemp -d /tmp/ql-verify.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0

pass() { printf '[PASS] %s\n' "$*"; PASS=$((PASS + 1)); }
fail() { printf '[FAIL] %s\n' "$*"; FAIL=$((FAIL + 1)); }

check_eq() {
    local desc="$1" got="$2" want="$3"
    if [ "$got" = "$want" ]; then
        pass "$desc = $got"
    else
        fail "$desc: expected [$want], got [$got]"
    fi
}

# http <method> <path> <outfile> [bodyfile] [token] [device]
http() {
    local method="$1" path="$2" out="$3" body="${4:-}" token="${5:-}" device="${6:-}"
    local args=(-sS --max-time 20 -o "$out" -w '%{http_code}' -X "$method")
    args+=(-H 'Content-Type: application/json')
    [ -n "$body" ] && args+=(-d @"$body")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$device" ] && args+=(-H "X-Device-Id: $device")
    curl "${args[@]}" "$BASE$path"
}

make_share() {
    printf '{"date":"%s"}\n' "$1" > "$TMP/share_body.json"
    local code
    code=$(http POST /v1/receipt-shares "$TMP/share.json" "$TMP/share_body.json" "$2" "$3")
    [ "$code" = 200 ] || return 1
    jget "$TMP/share.json" "j['shareId']"
}

# jget <jsonfile> <python expression using j>
jget() {
    python3 -c 'import json,sys; j=json.load(open(sys.argv[1], encoding="utf-8")); print(eval(sys.argv[2]))' "$1" "$2"
}

# jassert <description> <jsonfile> <python expression using j>
jassert() {
    local desc="$1" file="$2" expr="$3"
    if python3 -c "import json,sys; j=json.load(open(sys.argv[1], encoding='utf-8')); sys.exit(0 if ($expr) else 1)" "$file"; then
        pass "$desc"
    else
        fail "$desc (expr: $expr)"
    fi
}

# jsame <description> <jsonfileA> <jsonfileB>
jsame() {
    local desc="$1" file_a="$2" file_b="$3"
    if python3 -c 'import json,sys; a=json.load(open(sys.argv[1], encoding="utf-8")); b=json.load(open(sys.argv[2], encoding="utf-8")); sys.exit(0 if a==b else 1)' "$file_a" "$file_b"; then
        pass "$desc"
    else
        fail "$desc"
    fi
}

# jfile <jsonfile> <python expression using j>  -- pretty print for debugging
jfile() {
    python3 -c 'import json,sys; j=json.load(open(sys.argv[1], encoding="utf-8")); print(json.dumps(j, ensure_ascii=False)[:2000])' "$1"
}

echo "================================================================"
echo " QuietLedger verification (tax rules v2026-11-d)"
echo " base URL : $BASE"
echo " host     : $(hostname)"
echo " time     : $(date -u '+%Y-%m-%dT%H:%M:%SZ') / $(TZ=Asia/Shanghai date '+%Y-%m-%d %H:%M:%S %Z')"
echo "================================================================"

# ---------------------------------------------------------------- 1. health
echo
echo "--- 1. health ---------------------------------------------------"
code=$(http GET /health "$TMP/health.json")
check_eq "GET $BASE/health -> HTTP" "$code" "200"
jassert "health.status is ok" "$TMP/health.json" "j['status'] == 'ok'"
jassert "health.service is quiet-ledger" "$TMP/health.json" "j['service'] == 'quiet-ledger'"
jassert "health.version is 1.4.2" "$TMP/health.json" "j['version'] == '1.4.2'"
jassert "health.db is ok" "$TMP/health.json" "j['db'] == 'ok'"
jassert "health.time present" "$TMP/health.json" "bool(j['time'])"
echo "GET /health -> $(cat "$TMP/health.json")"

# The landing page is rendered by the same process and must show v2026-11-d.
echo
echo "--- 1b. landing page copy (v2026-11-d) -------------------------"
code=$(http GET / "$TMP/home.html")
check_eq "GET $BASE/ -> HTTP" "$code" "200"
for needle in "2026-11-d" "连发折扣" "群发加成" "勿扰折扣" "单条封顶" "聊天应用" "办公应用" "会话分类" "自定义" "上送通知原文" "HTTPS 同步"; do
    if grep -qF -- "$needle" "$TMP/home.html"; then
        pass "landing page contains: $needle"
    else
        fail "landing page missing: $needle"
    fi
done
# Sample invoice on the landing page, hand-computed with v2026-11-d:
#   微信 ×0.5, 12 条其中 6 条连发: 6*ceil(8*0.5) + 6*ceil(8*0.5*0.4) = 24 + 12 = 36
#   办公 ×0.4, 8 条:               8*ceil(8*0.4) = 8*4 = 32
#   群发 ×2.0, 1 条:               ceil(8*2.0) = 16
#   勿扰 ×0.5, 2 条:               2*ceil(8*0.5) = 2*4 = 8
#   夜间 ×1.5, 2 条:               2*ceil(8*1.5) = 2*12 = 24
#   total 116, 优惠已减 6*(4-2) = 12, balance 240 - 116 = 124, paid
if python3 - "$TMP/home.html" <<'PY'
import re
import sys
from pathlib import Path

page = Path(sys.argv[1]).read_text(encoding="utf-8")
sample = page.split('id="receipt-sample"', 1)[1].split("</figure>", 1)[0]
rows = re.findall(r'<td class="num">\d+</td><td class="num">(\d+)</td>', sample)
amounts = re.findall(r'<b class="num">(\d+) 秒</b>', sample)
assert len(rows) == 5, rows
duty, saved, exemption, balance = map(int, amounts)
assert sum(map(int, rows)) == duty
assert (duty, saved, exemption, balance) == (116, 12, 240, 124)
PY
then
    pass "sample receipt reconciles rows, discounts and balance"
else
    fail "sample receipt amounts do not reconcile"
fi
if grep -qF -- "@@" "$TMP/home.html"; then
    fail "landing page has unreplaced @@PLACEHOLDER@@"
else
    pass "landing page has no unreplaced placeholder"
fi
for stale in "2026-11-a" "2026-11-b" "七条规则"; do
    if grep -qF -- "$stale" "$TMP/home.html"; then
        fail "landing page still mentions $stale"
    else
        pass "landing page has no stale copy: $stale"
    fi
done

# ------------------------------------------------------ 2. create account
echo
echo "--- 2. create account ------------------------------------------"
STAMP="$(date +%s)"
DEV1="verify-dev1-$STAMP"
DEV2="verify-dev2-$STAMP"
DEV3="verify-dev3-$STAMP"
printf '{"deviceId":"%s","model":"VerifyBot","appVersion":"1.4.0"}\n' "$DEV1" > "$TMP/account_body.json"
code=$(http POST /v1/accounts "$TMP/account.json" "$TMP/account_body.json")
check_eq "POST /v1/accounts -> HTTP" "$code" "200"
ACCOUNT_CODE=$(jget "$TMP/account.json" "j['accountCode']")
TOKEN=$(jget "$TMP/account.json" "j['token']")
if python3 -c 'import re,sys; sys.exit(0 if re.fullmatch(r"QL-[A-Z0-9]{4}", sys.argv[1]) else 1)' "$ACCOUNT_CODE"; then
    pass "accountCode matches QL-[A-Z0-9]{4}: $ACCOUNT_CODE"
else
    fail "accountCode format: $ACCOUNT_CODE"
fi
if python3 -c 'import re,sys; sys.exit(0 if re.fullmatch(r"[0-9a-f]{32,}", sys.argv[1]) else 1)' "$TOKEN"; then
    pass "token is 32+ lowercase hex chars (length ${#TOKEN})"
else
    fail "token format (length ${#TOKEN})"
fi
jassert "create response echoes deviceId" "$TMP/account.json" "j['deviceId'] == '$DEV1'"
jassert "create response tariffVersion 2026-11-d" "$TMP/account.json" "j['tariffVersion'] == '2026-11-d'"
jassert "create response serverTime present" "$TMP/account.json" "bool(j['serverTime'])"

# --------------------------------------------------------- 3. join device
echo
echo "--- 3. join existing account with second device ----------------"
printf '{}\n' > "$TMP/invite_body.json"
code=$(http POST /v1/accounts/invite "$TMP/invite.json" "$TMP/invite_body.json")
check_eq "issuing an invite requires auth" "$code" "401"
code=$(http POST /v1/accounts/invite "$TMP/invite.json" "$TMP/invite_body.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/accounts/invite -> HTTP" "$code" "200"
INVITE=$(jget "$TMP/invite.json" "j['inviteCode']")
jassert "invite expires in ten minutes" "$TMP/invite.json" "j['expiresInSeconds'] == 600"
printf '{"inviteCode":"%s","deviceId":"%s","model":"VerifyBot-2","appVersion":"1.9.3"}\n' \
    "$INVITE" "$DEV2" > "$TMP/join_body.json"
code=$(http POST /v1/accounts/join "$TMP/join.json" "$TMP/join_body.json")
check_eq "POST /v1/accounts/join -> HTTP" "$code" "200"
jassert "join returns the same accountCode" "$TMP/join.json" "j['accountCode'] == '$ACCOUNT_CODE'"
jassert "join returns a token" "$TMP/join.json" "len(j['token']) >= 32"
jassert "join echoes the second deviceId" "$TMP/join.json" "j['deviceId'] == '$DEV2'"

code=$(http POST /v1/accounts/join "$TMP/join_replay.json" "$TMP/join_body.json")
check_eq "invite cannot be used twice" "$code" "404"
printf '{"accountCode":"%s","deviceId":"nope"}\n' "$ACCOUNT_CODE" > "$TMP/join_bad_body.json"
code=$(http POST /v1/accounts/join "$TMP/join_bad.json" "$TMP/join_bad_body.json")
check_eq "legacy account code cannot join" "$code" "404"
jassert "join fails without a valid invite" "$TMP/join_bad.json" "j['error'] == 'invalid_invite'"

# ---------------------------------------------------------------- 4. tariff
echo
echo "--- 4. tariff (defaults for a brand new account) ---------------"
code=$(http GET /v1/tariff "$TMP/tariff.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/tariff -> HTTP" "$code" "200"
jassert "tariff version 2026-11-d" "$TMP/tariff.json" "j['version'] == '2026-11-d'"
jassert "tariff unitSeconds 8" "$TMP/tariff.json" "j['unitSeconds'] == 8"
jassert "tariff nightMultiplier 1.5" "$TMP/tariff.json" "j['nightMultiplier'] == 1.5"
jassert "tariff night window 23..7" "$TMP/tariff.json" "j['nightStartHour'] == 23 and j['nightEndHour'] == 7"
jassert "tariff burst is a discount (0.4) over 120 s" "$TMP/tariff.json" "j['burstMultiplier'] == 0.4 and j['burstWindowSec'] == 120"
jassert "tariff dndMultiplier 0.5 (v2026-11-d)" "$TMP/tariff.json" "j['dndMultiplier'] == 0.5"
jassert "tariff noticeMultiplier 2.0 (v2026-11-d)" "$TMP/tariff.json" "j['noticeMultiplier'] == 2.0"
jassert "tariff capFactor 3.0 (v2026-11-d)" "$TMP/tariff.json" "j['capFactor'] == 3.0"
jassert "tariff exemptionPerDay 240" "$TMP/tariff.json" "j['exemptionPerDay'] == 240"
jassert "tariff currency SEC" "$TMP/tariff.json" "j['currency'] == 'SEC'"
jassert "tariff updatedAt 2026-11-15" "$TMP/tariff.json" "j['updatedAt'] == '2026-11-15T00:00:00Z'"
# v2026-11-d app weights (unchanged since v2026-11-b), pushed to the client so
# its offline estimate matches the server exactly.
jassert "appMultipliers: 9 prefixes" "$TMP/tariff.json" "len(j['appMultipliers']) == 9"
jassert "appMultiplierDefault 1.0" "$TMP/tariff.json" "j['appMultiplierDefault'] == 1.0"
jassert "appMultipliers: WeChat/QQ/TIM 0.5" "$TMP/tariff.json" "j['appMultipliers']['com.tencent.mm'] == 0.5 and j['appMultipliers']['com.tencent.mobileqq'] == 0.5 and j['appMultipliers']['com.tencent.tim'] == 0.5"
jassert "appMultipliers: office apps 0.4" "$TMP/tariff.json" "j['appMultipliers']['com.tencent.wework'] == 0.4 and j['appMultipliers']['com.alibaba.android.rimet'] == 0.4 and j['appMultipliers']['com.ss.android.lark'] == 0.4 and j['appMultipliers']['com.larksuite.suite'] == 0.4 and j['appMultipliers']['com.microsoft.teams'] == 0.4 and j['appMultipliers']['com.slack'] == 0.4"
echo "tariff -> $(jfile "$TMP/tariff.json")"

# ------------------------------- 5. legacy account keeps default rules
echo
echo "--- 5. pre-existing / second account keeps default rules -------"
printf '{"deviceId":"%s","model":"VerifyOld","appVersion":"1.3.1"}\n' "$DEV3" > "$TMP/account2_body.json"
code=$(http POST /v1/accounts "$TMP/account2.json" "$TMP/account2_body.json")
check_eq "POST /v1/accounts (second account) -> HTTP" "$code" "200"
ACCOUNT2_CODE=$(jget "$TMP/account2.json" "j['accountCode']")
TOKEN2=$(jget "$TMP/account2.json" "j['token']")
code=$(http GET /v1/tariff "$TMP/tariff2.json" "" "$TOKEN2" "$DEV3")
check_eq "GET /v1/tariff (second account) -> HTTP" "$code" "200"
jassert "second account tariff defaults 8 / 240" "$TMP/tariff2.json" "j['unitSeconds'] == 8 and j['exemptionPerDay'] == 240"
jassert "second account has appMultipliers too" "$TMP/tariff2.json" "j['appMultipliers']['com.tencent.mm'] == 0.5 and j['appMultiplierDefault'] == 1.0"
code=$(http GET "/v1/invoice?date=2026-01-01&deviceId=all" "$TMP/invoice2.json" "" "$TOKEN2" "$DEV3")
check_eq "GET /v1/invoice (second account) -> HTTP" "$code" "200"
jassert "second account empty day: duty 0, exemption 240, paid" "$TMP/invoice2.json" "j['totalDuty'] == 0 and j['exemption'] == 240 and j['balance'] == 240 and j['status'] == 'paid'"

# --------------------------------------------------- 6. fixture + ingest
echo
echo "--- 6. event ingest (hand-computed v2026-11-d fixture) ---------"
TODAY=$(python3 - <<'PY'
from datetime import datetime, timedelta, timezone
print(datetime.now(timezone(timedelta(hours=8))).date().isoformat())
PY
)
echo "Asia/Shanghai today = $TODAY"
START=$(python3 -c "from datetime import date,timedelta; print((date.fromisoformat('$TODAY')-timedelta(days=6)).isoformat())")
YESTERDAY=$(python3 -c "from datetime import date,timedelta; print((date.fromisoformat('$TODAY')-timedelta(days=1)).isoformat())")
echo "week window = $START .. $TODAY"
python3 - "$TODAY" "$DEV1" "$TMP/events.json" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
today = sys.argv[1]
device = sys.argv[2]
out = sys.argv[3]
day = datetime.fromisoformat(today).replace(tzinfo=TZ)


def at(hour, minute, second=0):
    return int(day.replace(hour=hour, minute=minute, second=second, microsecond=0).timestamp() * 1000)


def ev(eid, pkg, app, ts, category="msg", dnd=None, notice=None, title=None):
    item = {"id": eid, "pkg": pkg, "app": app, "ts": ts, "priority": 0,
            "category": category, "ongoing": False, "clearable": True}
    # title is optional since v1.6.0: most of this fixture omits it, exactly
    # like a default (upload-titles-off) client.
    if title is not None:
        item["title"] = title
    if dnd is not None:
        item["dnd"] = dnd
    if notice is not None:
        item["notice"] = notice
    return item


t = at(10, 0)   # 10:00 Asia/Shanghai, not night
events = [
    # com.verify.a: 4 events, 60 s apart, no app weight, no discounts.
    #   8 + ceil(8*0.4)=4 *3 = 20 s, saved 3*(8-4) = 12.
    ev("verify-a1", "com.verify.a", "VerifyAppA", t),
    ev("verify-a2", "com.verify.a", "VerifyAppA", t + 60_000),
    ev("verify-a3", "com.verify.a", "VerifyAppA", t + 120_000),
    ev("verify-a4", "com.verify.a", "VerifyAppA", t + 180_000),
    # com.verify.dnd: 2 events during Do Not Disturb, 60 s apart.
    #   8*0.5 = 4, 8*0.5*0.4 = 1.6 -> 2 => 6 s; base = 8 each => saved 4+6 = 10.
    ev("verify-dnd1", "com.verify.dnd", "VerifyDnd", at(10, 5), dnd=True),
    ev("verify-dnd2", "com.verify.dnd", "VerifyDnd", at(10, 6), dnd=True),
    # com.verify.notice: one group broadcast. 8*2.0 = 16 s, base 16 => saved 0.
    ev("verify-notice1", "com.verify.notice", "VerifyNotice", at(10, 7), notice=True),
    # com.tencent.mm (WeChat, appFactor 0.5):
    #   ceil(8*0.5)=4 + burst ceil(8*0.5*0.4)=2 + ceil(8*0.5)=4 = 10, saved 2.
    ev("verify-mm1", "com.tencent.mm", "WeChat", at(10, 10)),
    ev("verify-mm2", "com.tencent.mm", "WeChat", at(10, 11)),
    ev("verify-mm3", "com.tencent.mm", "WeChat", at(10, 21)),
    # com.verify.b at 23:30 => night, ceil(8*1.5)=12, base 12 => saved 0.
    ev("verify-b1", "com.verify.b", "VerifyAppB", at(23, 30), "call"),
    # com.verify.mix at 23:50/23:51: night + broadcast + dnd (+ burst on #2).
    #   #1: 8*1.5*2*0.5 = 12, base ceil(8*1.5*2) = 24 => saved 12
    #   #2: 8*1.5*2*0.5*0.4 = 4.8 -> 5, base 24 => saved 19
    #   line: 17 s, burst 1, night 2, dnd 2, notice 2, saved 31
    ev("verify-mix1", "com.verify.mix", "VerifyMix", at(23, 50), dnd=True, notice=True),
    ev("verify-mix2", "com.verify.mix", "VerifyMix", at(23, 51), dnd=True, notice=True),
]
payload = {"deviceId": device, "events": events}
with open(out, "w", encoding="utf-8") as fh:
    json.dump(payload, fh)
print("fixture: 13 events, expected duty 20+6+16+10+12+17 = 81 s, "
      "savedSeconds 12+10+0+2+0+31 = 55, balance 240-81 = 159")
PY
code=$(http POST /v1/events "$TMP/ingest.json" "$TMP/events.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/events -> HTTP" "$code" "200"
jassert "ingest accepted 13" "$TMP/ingest.json" "j['accepted'] == 13"
jassert "ingest stored 13" "$TMP/ingest.json" "j['stored'] == 13"
jassert "ingest duplicated 0" "$TMP/ingest.json" "j['duplicated'] == 0"
echo "ingest -> $(cat "$TMP/ingest.json")"

# replay the same batch: idempotent
code=$(http POST /v1/events "$TMP/ingest_replay.json" "$TMP/events.json" "$TOKEN" "$DEV1")
check_eq "replay same batch -> HTTP" "$code" "200"
jassert "replay accepted 13" "$TMP/ingest_replay.json" "j['accepted'] == 13"
jassert "replay stored 0" "$TMP/ingest_replay.json" "j['stored'] == 0"
jassert "replay duplicated 13" "$TMP/ingest_replay.json" "j['duplicated'] == 13"
echo "replay -> $(cat "$TMP/ingest_replay.json")"

# invalid events are skipped and reported
printf '{"deviceId":"%s","events":[{"id":"","pkg":"x","app":"y","ts":1},{"id":"bad-ts","pkg":"com.verify.a","app":"VerifyAppA","ts":0}]}\n' "$DEV1" > "$TMP/bad_events.json"
code=$(http POST /v1/events "$TMP/ingest_bad.json" "$TMP/bad_events.json" "$TOKEN" "$DEV1")
check_eq "invalid events -> HTTP" "$code" "200"
jassert "invalid events skipped (invalid=2)" "$TMP/ingest_bad.json" "j['accepted'] == 0 and j['invalid'] == 2 and j['stored'] == 0"

# empty batch is fine
printf '{"deviceId":"%s","events":[]}\n' "$DEV1" > "$TMP/empty_events.json"
code=$(http POST /v1/events "$TMP/ingest_empty.json" "$TMP/empty_events.json" "$TOKEN" "$DEV1")
check_eq "empty batch -> HTTP" "$code" "200"
jassert "empty batch accepted/stored 0" "$TMP/ingest_empty.json" "j['accepted'] == 0 and j['stored'] == 0"

# >500 events rejected
python3 - "$DEV1" "$TMP/too_many.json" <<'PY'
import json
import sys
device = sys.argv[1]
payload = {"deviceId": device, "events": [
    {"id": "too-many-%d" % i, "pkg": "com.verify.a", "app": "VerifyAppA", "ts": 1}
    for i in range(501)
]}
with open(sys.argv[2], "w", encoding="utf-8") as fh:
    json.dump(payload, fh)
PY
code=$(http POST /v1/events "$TMP/too_many_resp.json" "$TMP/too_many.json" "$TOKEN" "$DEV1")
check_eq "501 events -> HTTP" "$code" "400"
jassert "501 events error code" "$TMP/too_many_resp.json" "j['error'] == 'too_many_events'"

# ------------------------------------------------------------- 7. invoice
echo
echo "--- 7. invoice: default rules (81 s / saved 55 / balance 159) --"
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/invoice.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/invoice -> HTTP" "$code" "200"
INVOICE_ID=$(jget "$TMP/invoice.json" "j['invoiceId']")
TOTAL_DUTY=$(jget "$TMP/invoice.json" "j['totalDuty']")
echo "invoice -> $(jfile "$TMP/invoice.json")"
jassert "invoice date" "$TMP/invoice.json" "j['date'] == '$TODAY'"
jassert "invoice accountCode" "$TMP/invoice.json" "j['accountCode'] == '$ACCOUNT_CODE'"
jassert "invoiceId format INV-YYYYMMDD-XXXX" "$TMP/invoice.json" "j['invoiceId'].startswith('INV-' + '$TODAY'.replace('-', '') + '-') and len(j['invoiceId'].split('-')[2]) == 4"
jassert "duty total 81 (A 20 + dnd 6 + notice 16 + WeChat 10 + night 12 + mix 17)" "$TMP/invoice.json" "j['totalDuty'] == 81"
jassert "exemption 240" "$TMP/invoice.json" "j['exemption'] == 240"
jassert "balance = 240 - 81 = 159" "$TMP/invoice.json" "j['balance'] == 159"
jassert "status paid (81 <= 240)" "$TMP/invoice.json" "j['status'] == 'paid'"
jassert "tariff version 2026-11-d" "$TMP/invoice.json" "j['tariffVersion'] == '2026-11-d'"
jassert "VerifyAppA line: 4 events / 20 s / 3 burst / plain note" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('count') == 4 and next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('duty') == 20 and next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('burst') == 3 and next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('dnd') == 0 and next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('notice') == 0"
jassert "VerifyAppA note: 连发 3 条" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('note','') == '连发 3 条'"
jassert "VerifyDnd line: 2 events / 6 s / 1 burst / 2 dnd" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('count') == 2 and next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('duty') == 6 and next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('burst') == 1 and next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('dnd') == 2"
jassert "VerifyDnd note order: 连发 then 免打扰" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('note','') == '连发 1 条 · 免打扰 2 条'"
jassert "VerifyNotice line: 1 event / 16 s / 1 notice (x2.0)" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyNotice'), {}).get('duty') == 16 and next((l for l in j['lines'] if l['app']=='VerifyNotice'), {}).get('notice') == 1 and next((l for l in j['lines'] if l['app']=='VerifyNotice'), {}).get('note') == '群发 1 条'"
jassert "WeChat line: 3 events / 10 s / 1 burst (appFactor 0.5)" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='WeChat'), {}).get('count') == 3 and next((l for l in j['lines'] if l['app']=='WeChat'), {}).get('duty') == 10 and next((l for l in j['lines'] if l['app']=='WeChat'), {}).get('burst') == 1"
jassert "WeChat note: 连发 1 条" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='WeChat'), {}).get('note','') == '连发 1 条'"
jassert "VerifyAppB line: night 12 s" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyAppB'), {}).get('count') == 1 and next((l for l in j['lines'] if l['app']=='VerifyAppB'), {}).get('duty') == 12 and next((l for l in j['lines'] if l['app']=='VerifyAppB'), {}).get('night') == 1 and next((l for l in j['lines'] if l['app']=='VerifyAppB'), {}).get('note','') == '夜间 1 条'"
jassert "VerifyMix line: 17 s / night 2 / burst 1 / dnd 2 / notice 2" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('duty') == 17 and next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('night') == 2 and next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('burst') == 1 and next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('dnd') == 2 and next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('notice') == 2"
jassert "VerifyMix note order: 夜间/连发/免打扰/群发" "$TMP/invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('note','') == '夜间 2 条 · 连发 1 条 · 免打扰 2 条 · 群发 2 条'"
jassert "stats events 13" "$TMP/invoice.json" "j['stats']['events'] == 13"
jassert "stats nightRatio 3/13 = 0.2308" "$TMP/invoice.json" "j['stats']['nightRatio'] == 0.2308"
jassert "stats bursts 6" "$TMP/invoice.json" "j['stats']['bursts'] == 6"
jassert "stats savedSeconds 55 (all discounts, not only bursts)" "$TMP/invoice.json" "j['stats']['savedSeconds'] == 55"
jassert "stats dndEvents 4 / noticeEvents 3" "$TMP/invoice.json" "j['stats']['dndEvents'] == 4 and j['stats']['noticeEvents'] == 3"
jassert "stats byHour 10:00 -> 10, 23:00 -> 3" "$TMP/invoice.json" "j['stats']['byHour'][10] == 10 and j['stats']['byHour'][23] == 3"
jassert "stats devices = [first device]" "$TMP/invoice.json" "j['stats']['devices'] == ['$DEV1']"

# invoice id is stable across requests (share link must not change)
code=$(http GET "/v1/invoice?date=$TODAY" "$TMP/invoice2req.json" "" "$TOKEN" "$DEV1")
check_eq "second invoice request -> HTTP" "$code" "200"
jassert "invoiceId stable across requests" "$TMP/invoice2req.json" "j['invoiceId'] == '$INVOICE_ID'"

# device filter
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=$DEV2" "$TMP/invoice_dev2.json" "" "$TOKEN" "$DEV2")
check_eq "invoice for second device -> HTTP" "$code" "200"
jassert "second device has no events (duty 0, paid)" "$TMP/invoice_dev2.json" "j['totalDuty'] == 0 and j['status'] == 'paid' and j['stats']['events'] == 0"

# bad date
code=$(http GET "/v1/invoice?date=2026-13-99" "$TMP/invoice_bad_date.json" "" "$TOKEN")
check_eq "invalid date -> HTTP" "$code" "400"

# --------------------------------- 7b. burst resets at Asia/Shanghai midnight
# §2: bursts are judged inside one natural day only.  Yesterday 23:59:30 and
# today 00:00:30 are 60 s apart on the same device+package, but the 00:00:30
# event must NOT be a burst (duty 12 = night 8×1.5, bursts 0, saved 0).
echo
echo "--- 7b. cross-midnight burst reset (natural-day rule) ----------"
DEV4="verify-cross-$STAMP"
printf '{"deviceId":"%s","model":"VerifyCross","appVersion":"1.4.0"}\n' "$DEV4" > "$TMP/account4_body.json"
code=$(http POST /v1/accounts "$TMP/account4.json" "$TMP/account4_body.json")
check_eq "cross-day account -> HTTP" "$code" "200"
TOKEN4=$(jget "$TMP/account4.json" "j['token']")
python3 - "$TODAY" "$DEV4" "$TMP/cross_events.json" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
today = datetime.fromisoformat(sys.argv[1]).replace(tzinfo=TZ)
device = sys.argv[2]
out = sys.argv[3]
prev = today - timedelta(days=1)


def ts(hour, minute, second=0):
    return int((prev + timedelta(hours=hour, minutes=minute, seconds=second)).timestamp() * 1000)


def ev(eid, day_ts):
    return {"id": eid, "pkg": "com.verify.cross", "app": "VerifyCross",
            "title": eid, "ts": day_ts, "priority": 0, "category": "msg",
            "ongoing": False, "clearable": True}


payload = {"deviceId": device, "events": [
    ev("verify-cross-y", ts(23, 59, 30)),
    ev("verify-cross-t", ts(24, 0, 30)),
]}
with open(out, "w", encoding="utf-8") as fh:
    json.dump(payload, fh)
print("cross-day fixture: yesterday 23:59:30 + today 00:00:30 (60 s apart, same pkg)")
PY
code=$(http POST /v1/events "$TMP/cross_ingest.json" "$TMP/cross_events.json" "$TOKEN4" "$DEV4")
check_eq "cross-day ingest -> HTTP" "$code" "200"
jassert "cross-day ingest stored 2" "$TMP/cross_ingest.json" "j['stored'] == 2"
code=$(http GET "/v1/invoice?date=$YESTERDAY&deviceId=all" "$TMP/cross_inv_prev.json" "" "$TOKEN4" "$DEV4")
check_eq "cross-day invoice (yesterday) -> HTTP" "$code" "200"
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/cross_inv_today.json" "" "$TOKEN4" "$DEV4")
check_eq "cross-day invoice (today) -> HTTP" "$code" "200"
jassert "cross-day: yesterday night duty 12 (first of its day)" "$TMP/cross_inv_prev.json" "j['totalDuty'] == 12 and j['stats']['events'] == 1"
jassert "cross-day: today's first event is NOT a burst (night 12)" "$TMP/cross_inv_today.json" "j['totalDuty'] == 12 and j['stats']['events'] == 1"
jassert "cross-day: today bursts 0" "$TMP/cross_inv_today.json" "j['stats']['bursts'] == 0"
jassert "cross-day: today savedSeconds 0" "$TMP/cross_inv_today.json" "j['stats']['savedSeconds'] == 0"
code=$(http GET "/v1/weekly?days=2&end=$TODAY" "$TMP/cross_weekly.json" "" "$TOKEN4" "$DEV4")
check_eq "cross-day weekly -> HTTP" "$code" "200"
jassert "cross-day weekly agrees with invoices (12 + 12)" "$TMP/cross_weekly.json" "j['days'][0]['date'] == '$YESTERDAY' and j['days'][0]['duty'] == 12 and j['days'][1]['date'] == '$TODAY' and j['days'][1]['duty'] == 12 and j['totalDuty'] == 24"
echo "cross-day today invoice -> $(jfile "$TMP/cross_inv_today.json")"

# -------------------------------------------------------------- 8. weekly
echo
echo "--- 8. weekly report ------------------------------------------"
code=$(http GET "/v1/weekly?end=$TODAY" "$TMP/weekly.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/weekly -> HTTP" "$code" "200"
echo "weekly -> $(jfile "$TMP/weekly.json")"
jassert "weekly end = today" "$TMP/weekly.json" "j['end'] == '$TODAY'"
jassert "weekly covers 7 days" "$TMP/weekly.json" "len(j['days']) == 7"
jassert "weekly start = end - 6 days" "$TMP/weekly.json" "j['start'] == '$START' and j['days'][6]['date'] == j['end']"
jassert "weekly today's duty 81 / events 13" "$TMP/weekly.json" "next((d for d in j['days'] if d['date']=='$TODAY'), {}).get('duty') == 81 and next((d for d in j['days'] if d['date']=='$TODAY'), {}).get('events') == 13"
jassert "weekly totalDuty includes fixture" "$TMP/weekly.json" "j['totalDuty'] >= 81"
jassert "weekly top app = VerifyAppA (4 events, 20 duty)" "$TMP/weekly.json" "j['topApps'][0]['app'] == 'VerifyAppA' and j['topApps'][0]['count'] == 4 and j['topApps'][0]['duty'] == 20"
# v1.9.0: topApps carries pkg so the app can draw the real icon and drill down.
jassert "weekly top app pkg = com.verify.a" "$TMP/weekly.json" "j['topApps'][0]['pkg'] == 'com.verify.a'"
jassert "weekly nightRatio 3/13 = 0.2308" "$TMP/weekly.json" "j['nightRatio'] == 0.2308"
jassert "weekly tariffVersion 2026-11-d" "$TMP/weekly.json" "j['tariffVersion'] == '2026-11-d'"

# -------------------------------------------------------- 8b. report (v1.3.0)
echo
echo "--- 8b. arbitrary-period report (GET /v1/report) ---------------"
MONTH_START=$(python3 -c "from datetime import date; print(date.fromisoformat('$TODAY').replace(day=1).isoformat())")
MONTH_DAYS=$(python3 -c "from datetime import date; print((date.fromisoformat('$TODAY')-date.fromisoformat('$MONTH_START')).days+1)")
MONTH_LABEL=${MONTH_START:5}
MONTH_PREV_END=$(python3 -c "from datetime import date,timedelta; print((date.fromisoformat('$MONTH_START')-timedelta(days=1)).isoformat())")
MONTH_PREV_START=$(python3 -c "from datetime import date,timedelta; print((date.fromisoformat('$MONTH_START')-timedelta(days=$MONTH_DAYS)).isoformat())")
QUARTER_START=$(python3 -c "from datetime import date; d=date.fromisoformat('$TODAY'); print(date(d.year, ((d.month-1)//3)*3+1, 1).isoformat())")
QUARTER_LABEL=$(python3 -c "from datetime import date; d=date.fromisoformat('$TODAY'); print(date(d.year, ((d.month-1)//3)*3+1, 1).strftime('%Y-%m'))")
# quarter/year bucket by natural month, clipped to the requested end (no future
# months), so the client never draws a zero bar for a month that hasn't started.
QUARTER_END=$TODAY
QUARTER_BUCKETS=$(python3 -c "from datetime import date; d=date.fromisoformat('$TODAY'); print(d.month-((d.month-1)//3)*3)")
YEAR_START=$(python3 -c "from datetime import date; d=date.fromisoformat('$TODAY'); print(date(d.year,1,1).isoformat())")
YEAR_END=$TODAY
YEAR_BUCKETS=$(python3 -c "from datetime import date; d=date.fromisoformat('$TODAY'); print(d.month)")

# (1) kind=week must agree exactly with the legacy /v1/weekly endpoint.
code=$(http GET "/v1/report?kind=week&days=7&end=$TODAY" "$TMP/report_week.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/report?kind=week -> HTTP" "$code" "200"
echo "report week -> $(jfile "$TMP/report_week.json")"
jassert "report week kind/start/end/dayCount 7" "$TMP/report_week.json" "j['kind'] == 'week' and j['start'] == '$START' and j['end'] == '$TODAY' and j['dayCount'] == 7"
jassert "report week buckets = 7 days, ascending, ends today" "$TMP/report_week.json" "len(j['buckets']) == 7 and j['buckets'][0]['start'] == '$START' and j['buckets'][0]['end'] == '$START' and j['buckets'][-1]['end'] == '$TODAY' and j['buckets'][-1]['label'] == '$TODAY'[5:]"
jassert "report week buckets sum to totals" "$TMP/report_week.json" "sum(b['duty'] for b in j['buckets']) == j['totalDuty'] and sum(b['events'] for b in j['buckets']) == j['totalEvents']"
jassert "report week topApps/tariffVersion present" "$TMP/report_week.json" "j['tariffVersion'] == '2026-11-d' and isinstance(j['topApps'], list) and bool(j['generatedAt'])"
jassert "report week topApps carry pkg (v1.9.0 icons)" "$TMP/report_week.json" "all('pkg' in a for a in j['topApps']) and any(a['pkg'] for a in j['topApps'])"
rep_duty=$(jget "$TMP/report_week.json" "j['totalDuty']")
rep_events=$(jget "$TMP/report_week.json" "j['totalEvents']")
wk_duty=$(jget "$TMP/weekly.json" "j['totalDuty']")
wk_events=$(jget "$TMP/weekly.json" "j['totalEvents']")
check_eq "report week totalDuty == weekly totalDuty" "$rep_duty" "$wk_duty"
check_eq "report week totalEvents == weekly totalEvents" "$rep_events" "$wk_events"

# (2) kind=month: natural month up to the requested end (today), one bucket/day.
code=$(http GET "/v1/report?kind=month&end=$TODAY" "$TMP/report_month.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/report?kind=month -> HTTP" "$code" "200"
echo "report month -> $(jfile "$TMP/report_month.json")"
jassert "report month start = 1st of month, end = request day" "$TMP/report_month.json" "j['kind'] == 'month' and j['start'] == '$MONTH_START' and j['end'] == '$TODAY' and j['dayCount'] == $MONTH_DAYS"
jassert "report month buckets = dayCount, one per day" "$TMP/report_month.json" "len(j['buckets']) == j['dayCount'] and j['buckets'][0]['label'] == '$MONTH_LABEL' and j['buckets'][0]['start'] == '$MONTH_START' and j['buckets'][-1]['end'] == '$TODAY'"
jassert "report month buckets sum to totals / includes fixture 81" "$TMP/report_month.json" "sum(b['duty'] for b in j['buckets']) == j['totalDuty'] and j['totalDuty'] >= 81"
jassert "report month prev = equal period right before start" "$TMP/report_month.json" "j['prev']['end'] == '$MONTH_PREV_END' and j['prev']['start'] == '$MONTH_PREV_START' and isinstance(j['prev']['totalDuty'], int) and isinstance(j['prev']['totalEvents'], int)"

# (3) kind=quarter: natural-month buckets so far, first bucket starts at quarter day 1.
code=$(http GET "/v1/report?kind=quarter&end=$TODAY" "$TMP/report_quarter.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/report?kind=quarter -> HTTP" "$code" "200"
echo "report quarter -> $(jfile "$TMP/report_quarter.json")"
jassert "report quarter start = quarter first day" "$TMP/report_quarter.json" "j['kind'] == 'quarter' and j['start'] == '$QUARTER_START' and j['end'] == '$TODAY'"
jassert "report quarter = natural-month buckets up to today, first = quarter day 1" "$TMP/report_quarter.json" "len(j['buckets']) == $QUARTER_BUCKETS and j['buckets'][0]['label'] == '$QUARTER_LABEL' and j['buckets'][0]['start'] == '$QUARTER_START' and j['buckets'][-1]['end'] == '$QUARTER_END'"
jassert "report quarter buckets sum to totals" "$TMP/report_quarter.json" "sum(b['duty'] for b in j['buckets']) == j['totalDuty'] and sum(b['events'] for b in j['buckets']) == j['totalEvents']"

# (4) kind=year: month buckets from Jan to the current month (no future months).
code=$(http GET "/v1/report?kind=year&end=$TODAY" "$TMP/report_year.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/report?kind=year -> HTTP" "$code" "200"
echo "report year -> $(jfile "$TMP/report_year.json")"
jassert "report year start = Jan 1 of end's year" "$TMP/report_year.json" "j['kind'] == 'year' and j['start'] == '$YEAR_START' and j['end'] == '$TODAY'"
jassert "report year has one bucket per month so far" "$TMP/report_year.json" "len(j['buckets']) == $YEAR_BUCKETS and j['buckets'][0]['start'] == '$YEAR_START' and j['buckets'][-1]['end'] == '$YEAR_END'"
jassert "report year buckets sum to totals / includes fixture" "$TMP/report_year.json" "sum(b['duty'] for b in j['buckets']) == j['totalDuty'] and j['totalDuty'] >= 81"

# (5) auth contract: report requires the same bearer token as /v1/weekly.
code=$(http GET "/v1/report?kind=month&end=$TODAY" "$TMP/report_noauth.json")
check_eq "report without token -> HTTP" "$code" "401"
jassert "report 401 body error unauthorized" "$TMP/report_noauth.json" "j['error'] == 'unauthorized' and bool(j['message'])"

# (6) invalid kind / days / end / method.
code=$(http GET "/v1/report?kind=bogus&end=$TODAY" "$TMP/report_badkind.json" "" "$TOKEN" "$DEV1")
check_eq "report kind=bogus -> HTTP" "$code" "400"
jassert "report bad kind error bad_request" "$TMP/report_badkind.json" "j['error'] == 'bad_request' and bool(j['message'])"
code=$(http GET "/v1/report?kind=week&days=0&end=$TODAY" "$TMP/report_baddays.json" "" "$TOKEN" "$DEV1")
check_eq "report week days=0 -> HTTP" "$code" "400"
code=$(http GET "/v1/report?kind=week&days=31&end=$TODAY" "$TMP/report_baddays2.json" "" "$TOKEN" "$DEV1")
check_eq "report week days=31 -> HTTP" "$code" "400"
code=$(http GET "/v1/report?kind=month&end=2026-13-01" "$TMP/report_badend.json" "" "$TOKEN" "$DEV1")
check_eq "report invalid end -> HTTP" "$code" "400"
printf '{}\n' > "$TMP/report_post_body.json"
code=$(http POST /v1/report "$TMP/report_post.json" "$TMP/report_post_body.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/report -> HTTP" "$code" "405"
jassert "POST report error method_not_allowed" "$TMP/report_post.json" "j['error'] == 'method_not_allowed'"


# ------------------------------------------------------------- 9. devices
echo
echo "--- 9. devices --------------------------------------------------"
code=$(http GET /v1/devices "$TMP/devices.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/devices -> HTTP" "$code" "200"
echo "devices -> $(cat "$TMP/devices.json")"
jassert "two devices registered" "$TMP/devices.json" "len(j['devices']) == 2"
jassert "device list contains both ids" "$TMP/devices.json" "{d['deviceId'] for d in j['devices']} == {'$DEV1', '$DEV2'}"
jassert "first device eventCount 13" "$TMP/devices.json" "next((d for d in j['devices'] if d['deviceId']=='$DEV1'), {}).get('eventCount') == 13"
jassert "every device has lastSeenAt" "$TMP/devices.json" "all(d.get('lastSeenAt') for d in j['devices'])"

# ---------------------------------------------------------------- 10. meta
echo
echo "--- 10. meta -----------------------------------------------------"
code=$(http GET /v1/meta "$TMP/meta.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/meta -> HTTP" "$code" "200"
echo "meta -> $(cat "$TMP/meta.json")"
jassert "meta counters present and sane" "$TMP/meta.json" "j['accounts'] >= 2 and j['events'] >= 13 and j['invoices'] >= 1 and bool(j['serverTime'])"

# ----------------------------------------------------------- 11. receipt
echo
echo "--- 11. shareable HTML receipt (default rules) -----------------"
code=$(http GET "/r/$INVOICE_ID" "$TMP/unshared.html")
check_eq "invoice number is private" "$code" "404"
SHARE_ID=$(make_share "$TODAY" "$TOKEN" "$DEV1")
if [ -z "$SHARE_ID" ]; then fail "could not create receipt share"; fi
code=$(http GET "/r/$SHARE_ID" "$TMP/receipt.html")
check_eq "GET /r/<shareId> -> HTTP" "$code" "200"
for needle in "$INVOICE_ID" "81 秒" "240 秒" "55 秒" "优惠已减" "本收据由静默税局生成" "北京时间" "noindex, nofollow"; do
    if grep -qF -- "$needle" "$TMP/receipt.html"; then
        pass "receipt contains: $needle"
    else
        fail "receipt missing: $needle"
    fi
done
# v1.6.0: public receipt is a gentle landing CTA + Open Graph metadata, still
# one inline page with no external assets and still noindex.
for needle in 'href="https://ql.130923.xyz"' 'target="_blank"' 'rel="noopener"' \
              'property="og:title"' 'property="og:description"' 'property="og:url"' \
              'content="https://ql.130923.xyz"'; do
    if grep -qF -- "$needle" "$TMP/receipt.html"; then
        pass "receipt contains: $needle"
    else
        fail "receipt missing: $needle"
    fi
done
# Public receipts must not leak the account code (join credential).
if grep -qF -- "$ACCOUNT_CODE" "$TMP/receipt.html"; then
    fail "receipt leaks accountCode"
else
    pass "receipt does not expose accountCode"
fi
if grep -qF 'class="status paid"' "$TMP/receipt.html"; then
    pass "receipt status paid"
else
    fail "receipt status paid missing"
fi
code=$(http GET "/r/INV-19700101-ZZZZ" "$TMP/receipt404.html")
check_eq "GET /r/<unknown> -> HTTP" "$code" "404"
echo "receipt status: $(head -1 "$TMP/receipt.html") (size $(wc -c < "$TMP/receipt.html") bytes)"

# --------------------------------------------------------- 12. auth rules
echo
echo "--- 12. auth / error contract ----------------------------------"
code=$(http GET "/v1/invoice?date=$TODAY" "$TMP/noauth.json")
check_eq "invoice without token -> HTTP" "$code" "401"
jassert "401 body has snake_case error" "$TMP/noauth.json" "j['error'] == 'unauthorized' and bool(j['message'])"
code=$(http GET "/v1/invoice?date=$TODAY" "$TMP/badauth.json" "" "deadbeefdeadbeefdeadbeefdeadbeef" "$DEV1")
check_eq "invoice with bad token -> HTTP" "$code" "401"
jassert "bad token error unauthorized" "$TMP/badauth.json" "j['error'] == 'unauthorized'"
printf '{"unitSeconds":8,"exemptionPerDay":240}\n' > "$TMP/settings_body.json"
code=$(http POST /v1/settings "$TMP/settings_noauth.json" "$TMP/settings_body.json" "" "")
check_eq "settings without token -> HTTP" "$code" "401"

# ------------------------------------------- 13. per-account tax rules
echo
echo "--- 13. custom tax rules (unit=4, exemption=120) ----------------"
printf '{"unitSeconds":4,"exemptionPerDay":120}\n' > "$TMP/settings_custom.json"
code=$(http POST /v1/settings "$TMP/settings_resp.json" "$TMP/settings_custom.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/settings -> HTTP" "$code" "200"
jassert "settings response unit 4 / exemption 120" "$TMP/settings_resp.json" "j['unitSeconds'] == 4 and j['exemptionPerDay'] == 120"
jassert "settings response keeps tariff version + appMultipliers" "$TMP/settings_resp.json" "j['version'] == '2026-11-d' and j['appMultipliers']['com.tencent.mm'] == 0.5 and j['appMultiplierDefault'] == 1.0"
code=$(http GET /v1/tariff "$TMP/tariff_custom.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/tariff after settings -> HTTP" "$code" "200"
jassert "GET tariff reflects custom 4 / 120" "$TMP/tariff_custom.json" "j['unitSeconds'] == 4 and j['exemptionPerDay'] == 120"
jsame "settings response equals GET /v1/tariff" "$TMP/settings_resp.json" "$TMP/tariff_custom.json"

# The same fixture is now priced with unit=4 / exemption=120:
#   VerifyAppA 4 + 3*ceil(4*0.4)=2 -> 10 s (saved 6)
#   VerifyDnd  4*0.5=2 + ceil(4*0.5*0.4)=1 -> 3 s (saved 5)
#   VerifyNotice 4*2.0 = 8 s (saved 0)
#   WeChat     2 + ceil(4*0.5*0.4)=1 + 2 -> 5 s (saved 1)
#   night      ceil(4*1.5) = 6 s
#   VerifyMix  4*1.5*2*0.5=6 + ceil(4*1.5*2*0.5*0.4)=3 -> 9 s (saved 15)
#   total 41 s, savedSeconds 27, balance 120 - 41 = 79
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/invoice_custom.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/invoice (custom) -> HTTP" "$code" "200"
echo "invoice custom -> $(jfile "$TMP/invoice_custom.json")"
jassert "custom duty total 41" "$TMP/invoice_custom.json" "j['totalDuty'] == 41"
jassert "custom savedSeconds 27 (all discounts)" "$TMP/invoice_custom.json" "j['stats']['savedSeconds'] == 27"
jassert "custom exemption 120 / balance 79 / paid" "$TMP/invoice_custom.json" "j['exemption'] == 120 and j['balance'] == 79 and j['status'] == 'paid'"
jassert "custom savedSeconds still below duty total" "$TMP/invoice_custom.json" "j['stats']['savedSeconds'] < j['totalDuty']"
jassert "custom VerifyAppA line 10 s / 3 burst" "$TMP/invoice_custom.json" "next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('duty') == 10 and next((l for l in j['lines'] if l['app']=='VerifyAppA'), {}).get('burst') == 3"
jassert "custom VerifyDnd line 3 s / 2 dnd" "$TMP/invoice_custom.json" "next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('duty') == 3 and next((l for l in j['lines'] if l['app']=='VerifyDnd'), {}).get('dnd') == 2"
jassert "custom VerifyNotice line 8 s / 1 notice" "$TMP/invoice_custom.json" "next((l for l in j['lines'] if l['app']=='VerifyNotice'), {}).get('duty') == 8 and next((l for l in j['lines'] if l['app']=='VerifyNotice'), {}).get('notice') == 1"
jassert "custom WeChat line 5 s / 1 burst" "$TMP/invoice_custom.json" "next((l for l in j['lines'] if l['app']=='WeChat'), {}).get('duty') == 5 and next((l for l in j['lines'] if l['app']=='WeChat'), {}).get('burst') == 1"
jassert "custom night line 6 s" "$TMP/invoice_custom.json" "next((l for l in j['lines'] if l['app']=='VerifyAppB'), {}).get('duty') == 6"
jassert "custom VerifyMix line 9 s" "$TMP/invoice_custom.json" "next((l for l in j['lines'] if l['app']=='VerifyMix'), {}).get('duty') == 9"
jassert "custom invoice id unchanged" "$TMP/invoice_custom.json" "j['invoiceId'] == '$INVOICE_ID'"
code=$(http GET "/v1/weekly?end=$TODAY" "$TMP/weekly_custom.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/weekly (custom) -> HTTP" "$code" "200"
jassert "custom weekly today's duty 41" "$TMP/weekly_custom.json" "next((d for d in j['days'] if d['date']=='$TODAY'), {}).get('duty') == 41"

# Custom rules are per account: the second account must stay on defaults.
code=$(http GET /v1/tariff "$TMP/tariff2b.json" "" "$TOKEN2" "$DEV3")
check_eq "GET /v1/tariff (second account, after custom) -> HTTP" "$code" "200"
jassert "custom rules do not leak to the second account" "$TMP/tariff2b.json" "j['unitSeconds'] == 8 and j['exemptionPerDay'] == 240"

# Public receipt is re-rendered with the account's custom exemption/unit.
code=$(http GET "/r/$SHARE_ID" "$TMP/receipt_custom.html")
check_eq "GET /r/<shareId> (custom) -> HTTP" "$code" "200"
for needle in "41 秒" "120 秒" "27 秒" "优惠已减"; do
    if grep -qF -- "$needle" "$TMP/receipt_custom.html"; then
        pass "custom receipt contains: $needle"
    else
        fail "custom receipt missing: $needle"
    fi
done

# Boundary values are accepted: unit 2, exemption 1800.
printf '{"unitSeconds":2,"exemptionPerDay":1800}\n' > "$TMP/settings_boundary.json"
code=$(http POST /v1/settings "$TMP/settings_boundary_resp.json" "$TMP/settings_boundary.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/settings boundary 2/1800 -> HTTP" "$code" "200"
jassert "boundary 2/1800 accepted" "$TMP/settings_boundary_resp.json" "j['unitSeconds'] == 2 and j['exemptionPerDay'] == 1800"

# Zero exemption turns the same fixture into an owed invoice: 0 - 81 = -81.
printf '{"unitSeconds":8,"exemptionPerDay":0}\n' > "$TMP/settings_owed.json"
code=$(http POST /v1/settings "$TMP/settings_owed_resp.json" "$TMP/settings_owed.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/settings zero exemption -> HTTP" "$code" "200"
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/invoice_owed.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/invoice (owed) -> HTTP" "$code" "200"
jassert "zero exemption: duty 81, balance -81, owed" "$TMP/invoice_owed.json" "j['totalDuty'] == 81 and j['exemption'] == 0 and j['balance'] == -81 and j['status'] == 'owed'"
code=$(http GET "/r/$SHARE_ID" "$TMP/receipt_owed.html")
check_eq "GET /r/<shareId> (owed) -> HTTP" "$code" "200"
if grep -qF 'class="status owed"' "$TMP/receipt_owed.html" && grep -qF "欠税" "$TMP/receipt_owed.html"; then
    pass "owed receipt shows 欠税"
else
    fail "owed receipt does not show 欠税"
fi

# Validation contract: integers only, unit 2-30, exemption 0-1800.
for body in '{"unitSeconds":1,"exemptionPerDay":240}' \
            '{"unitSeconds":31,"exemptionPerDay":240}' \
            '{"unitSeconds":8.5,"exemptionPerDay":240}' \
            '{"unitSeconds":"8","exemptionPerDay":240}' \
            '{"unitSeconds":8,"exemptionPerDay":-1}' \
            '{"unitSeconds":8,"exemptionPerDay":1801}' \
            '{}'; do
    printf '%s\n' "$body" > "$TMP/settings_bad.json"
    code=$(http POST /v1/settings "$TMP/settings_bad_resp.json" "$TMP/settings_bad.json" "$TOKEN" "$DEV1")
    if [ "$code" = "400" ]; then
        pass "invalid settings rejected with 400: $body"
    else
        fail "invalid settings not rejected ($code): $body"
    fi
done
code=$(http GET /v1/settings "$TMP/settings_get.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/settings -> HTTP" "$code" "405"

# Restore defaults: the invoice must be byte-identical to the pre-custom one.
printf '{"unitSeconds":8,"exemptionPerDay":240}\n' > "$TMP/settings_restore.json"
code=$(http POST /v1/settings "$TMP/settings_restore_resp.json" "$TMP/settings_restore.json" "$TOKEN" "$DEV1")
check_eq "POST /v1/settings restore -> HTTP" "$code" "200"
jassert "restored tariff 8 / 240" "$TMP/settings_restore_resp.json" "j['unitSeconds'] == 8 and j['exemptionPerDay'] == 240"
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/invoice_restored.json" "" "$TOKEN" "$DEV1")
check_eq "GET /v1/invoice (restored) -> HTTP" "$code" "200"
jassert "restored duty 81 / saved 55 / balance 159" "$TMP/invoice_restored.json" "j['totalDuty'] == 81 and j['stats']['savedSeconds'] == 55 and j['balance'] == 159 and j['status'] == 'paid'"
jsame "invoice identical to pre-custom defaults" "$TMP/invoice.json" "$TMP/invoice_restored.json"

# ------------------------------------- 13b. privacy: title is optional
echo
echo "--- 13b. title-less ingest (default privacy) -------------------"
# v1.6.0: clients no longer upload notification titles by default, so the
# server must accept events without a title, store '' and never echo text.
DEV6="verify-priv-$STAMP"
printf '{"deviceId":"%s","model":"VerifyPriv","appVersion":"1.6.0"}\n' "$DEV6" > "$TMP/account6_body.json"
code=$(http POST /v1/accounts "$TMP/account6.json" "$TMP/account6_body.json")
check_eq "privacy account -> HTTP" "$code" "200"
TOKEN6=$(jget "$TMP/account6.json" "j['token']")
python3 - "$YESTERDAY" "$DEV6" "$TMP/priv_events.json" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
day = datetime.fromisoformat(sys.argv[1]).replace(tzinfo=TZ)
device = sys.argv[2]
out = sys.argv[3]


def at(hour, minute):
    return int(day.replace(hour=hour, minute=minute, second=0, microsecond=0).timestamp() * 1000)


payload = {"deviceId": device, "events": [
    # No "title"/"dnd"/"notice" keys at all: this is the default v1.6.0 client
    # payload, and the old v1.5.0 client payload too (missing bools => False).
    {"id": "verify-priv-1", "pkg": "com.verify.priv1", "app": "VerifyPriv1",
     "ts": at(9, 0), "priority": 0, "category": "msg",
     "ongoing": False, "clearable": True},
    # A title that must never leak into invoice / receipt HTML.
    {"id": "verify-priv-2", "pkg": "com.verify.priv2", "app": "VerifyPriv2",
     "title": "verify-title-secret", "ts": at(9, 30), "priority": 0,
     "category": "msg", "ongoing": False, "clearable": True},
]}
with open(out, "w", encoding="utf-8") as fh:
    json.dump(payload, fh)
print("privacy fixture: 2 events, one title-less, one secret title")
PY
code=$(http POST /v1/events "$TMP/priv_ingest.json" "$TMP/priv_events.json" "$TOKEN6" "$DEV6")
check_eq "title-less ingest -> HTTP" "$code" "200"
jassert "title-less events accepted and stored" "$TMP/priv_ingest.json" "j['accepted'] == 2 and j['stored'] == 2 and j.get('invalid', 0) == 0"
code=$(http GET "/v1/invoice?date=$YESTERDAY&deviceId=all" "$TMP/priv_invoice.json" "" "$TOKEN6" "$DEV6")
check_eq "privacy invoice -> HTTP" "$code" "200"
jassert "privacy day: 2 events / 16 s / due to old-client defaults no dnd/notice" "$TMP/priv_invoice.json" "j['stats']['events'] == 2 and j['totalDuty'] == 16 and j['stats']['dndEvents'] == 0 and j['stats']['noticeEvents'] == 0"
PRIV_SHARE_ID=$(make_share "$YESTERDAY" "$TOKEN6" "$DEV6")
code=$(http GET "/r/$PRIV_SHARE_ID" "$TMP/priv_receipt.html")
check_eq "privacy receipt -> HTTP" "$code" "200"
if grep -qF -- "verify-title-secret" "$TMP/priv_receipt.html" "$TMP/priv_invoice.json"; then
    fail "notification title leaked into invoice/receipt"
else
    pass "notification title never appears in invoice/receipt"
fi

# ------------------------------------------- 13c. single-event cap (x3.0)
echo
echo "--- 13c. per-event cap at unit x capFactor ---------------------"
# unit=30, night + notice => raw = 30*1.5*2.0 = 90 s, cap = 30*3.0 = 90 s.
# The worst case current tables can hit lands exactly on the cap, so this is
# the cap boundary: 90 s, not 90.0001 or 270.
DEV7="verify-cap-$STAMP"
printf '{"deviceId":"%s","model":"VerifyCap","appVersion":"1.6.0"}\n' "$DEV7" > "$TMP/account7_body.json"
code=$(http POST /v1/accounts "$TMP/account7.json" "$TMP/account7_body.json")
check_eq "cap account -> HTTP" "$code" "200"
TOKEN7=$(jget "$TMP/account7.json" "j['token']")
printf '{"unitSeconds":30,"exemptionPerDay":240}\n' > "$TMP/settings_cap.json"
code=$(http POST /v1/settings "$TMP/settings_cap_resp.json" "$TMP/settings_cap.json" "$TOKEN7" "$DEV7")
check_eq "cap account unit=30 -> HTTP" "$code" "200"
jassert "cap account tariff capFactor 3.0" "$TMP/settings_cap_resp.json" "j['capFactor'] == 3.0 and j['unitSeconds'] == 30"
python3 - "$TODAY" "$DEV7" "$TMP/cap_events.json" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
day = datetime.fromisoformat(sys.argv[1]).replace(tzinfo=TZ)
device = sys.argv[2]
out = sys.argv[3]


def at(hour, minute):
    return int(day.replace(hour=hour, minute=minute, second=0, microsecond=0).timestamp() * 1000)


def ev(eid, ts):
    return {"id": eid, "pkg": "com.verify.cap", "app": "VerifyCap",
            "ts": ts, "priority": 0, "category": "msg",
            "ongoing": False, "clearable": True, "dnd": False, "notice": True}


payload = {"deviceId": device, "events": [
    ev("verify-cap1", at(23, 10)),   # 30 * 1.5 * 2.0 = 90 = cap
    ev("verify-cap2", at(23, 11)),   # *0.4 burst = 36, base 90 => saved 54
]}
with open(out, "w", encoding="utf-8") as fh:
    json.dump(payload, fh)
print("cap fixture: 2 night+broadcast events, expected duty 90+36 = 126, saved 54")
PY
code=$(http POST /v1/events "$TMP/cap_ingest.json" "$TMP/cap_events.json" "$TOKEN7" "$DEV7")
check_eq "cap ingest -> HTTP" "$code" "200"
jassert "cap ingest stored 2" "$TMP/cap_ingest.json" "j['stored'] == 2"
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/cap_invoice.json" "" "$TOKEN7" "$DEV7")
check_eq "cap invoice -> HTTP" "$code" "200"
jassert "cap boundary: duty 126 (90 capped + 36 burst), saved 54" "$TMP/cap_invoice.json" "j['totalDuty'] == 126 and j['stats']['savedSeconds'] == 54 and j['stats']['events'] == 2"
jassert "cap boundary: night 2 / notice 2 / burst 1" "$TMP/cap_invoice.json" "j['stats']['nightRatio'] == 1.0 and j['stats']['dndEvents'] == 0 and j['stats']['noticeEvents'] == 2 and j['stats']['bursts'] == 1"
jassert "cap boundary: line duty 126 equals sum" "$TMP/cap_invoice.json" "next((l for l in j['lines'] if l['app']=='VerifyCap'), {}).get('duty') == 126"
jassert "cap boundary: every event <= unit*capFactor per event" "$TMP/cap_invoice.json" "j['totalDuty'] <= 30 * 3 * j['stats']['events'] and j['stats']['savedSeconds'] >= 0"

# ------------------------------------------ 14. restart persistence check
echo
echo "--- 14. restart persistence ------------------------------------"
if [ "${SKIP_RESTART:-0}" = "1" ]; then
    echo "[SKIP] SKIP_RESTART=1"
elif [ "$(id -u)" = "0" ] && command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet quiet-ledger; then
    cp "$TMP/invoice_restored.json" "$TMP/invoice_before_restart.json"
    systemctl restart quiet-ledger
    healthy=0
    for _ in $(seq 1 40); do
        code=$(http GET /health "$TMP/health_after.json")
        if [ "$code" = "200" ]; then healthy=1; break; fi
        sleep 0.5
    done
    check_eq "healthy again after systemctl restart" "$healthy" "1"
    code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/invoice_after_restart.json" "" "$TOKEN" "$DEV1")
    check_eq "invoice after restart -> HTTP" "$code" "200"
    jsame "invoice identical after restart (idempotent, persisted)" "$TMP/invoice_before_restart.json" "$TMP/invoice_after_restart.json"
    code=$(http GET /v1/tariff "$TMP/tariff_after_restart.json" "" "$TOKEN" "$DEV1")
    check_eq "GET /v1/tariff after restart -> HTTP" "$code" "200"
    jassert "custom rules persisted across restart (restored defaults)" "$TMP/tariff_after_restart.json" "j['unitSeconds'] == 8 and j['exemptionPerDay'] == 240"
    echo "invoice after restart -> $(jfile "$TMP/invoice_after_restart.json")"
else
    echo "[SKIP] restart persistence needs root + systemd on the server host"
fi

# ------------------------------------------- 17. conversation categories
echo
echo "--- 17. conversation categories in one app ---------------------"
python3 - "$TODAY" "$DEV3" > "$TMP/conversation.json" <<'PY'
import datetime, json, sys
stamp = int(datetime.datetime.fromisoformat(sys.argv[1] + "T10:00:00+08:00").timestamp() * 1000)
classes = ["work", "friends", "free", "casual", "family", "work", "unknown", None]
events = []
for i, kind in enumerate(classes):
    ev = dict(id=f"conversation-{i}", pkg="com.tencent.mm", app="WeChat", ts=stamp + i * 1000,
              category="msg", dnd=kind == "free", notice=kind == "free")
    if kind is not None:
        ev["conversationClass"] = kind
    events.append(ev)
print(json.dumps(dict(deviceId=sys.argv[2], events=events)))
PY
code=$(http POST /v1/events "$TMP/conversation_ingest.json" "$TMP/conversation.json" "$TOKEN2" "$DEV3")
check_eq "conversation ingest" "$code" "200"
jassert "all 8 accepted without titles" "$TMP/conversation_ingest.json" "j['stored'] == 8"
code=$(http GET "/v1/invoice?date=$TODAY&deviceId=all" "$TMP/conversation_invoice.json" "" "$TOKEN2" "$DEV3")
check_eq "conversation invoice" "$code" "200"
jassert "8+2+0+1+2+4+4+2 = 23 seconds; only same-class bursts" "$TMP/conversation_invoice.json" "j['totalDuty'] == 23 and j['stats']['events'] == 8 and j['stats']['bursts'] == 2"

# ------------------------------------------------------- 18. summary
echo
echo "================================================================"
echo " SUMMARY: $PASS passed, $FAIL failed"
echo " verify account : $ACCOUNT_CODE"
echo " invoice id     : $INVOICE_ID"
echo " invoice duty   : $TOTAL_DUTY (default exemption 240, balance 159)"
echo " custom rules   : unit 4 / exemption 120 => duty 41, saved 27, balance 79"
echo " cap boundary   : unit 30, night+broadcast => 90 s (= unit x 3.0)"
echo "================================================================"
if [ "$FAIL" -ne 0 ]; then
    echo "VERIFY FAILED"
    exit 1
fi
echo "VERIFY OK"
exit 0
