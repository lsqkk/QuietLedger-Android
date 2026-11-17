# 税率规则 v2026-11-d（v1.9.1 起）

本文件是当前唯一规则口径。Android 本机估算与服务端结算必须逐位一致；
改规则时同时改 `backend/app.py` 与 `app/src/main/java/dev/quietledger/app/core/Model.kt`，
并同步更新本文件、`backend/verify.sh`、App 内「计价规则」弹层与官网税率表。

## 1. 为什么又改一次

v2026-11-b 只把「连发」从加成改成折扣，账单仍然只看应用权重、夜间和连发，
有两类真实场景没被反映：

1. **没打断也照收全价**：手机开着免打扰，或者用户刚点过「静默 1 小时」，
   通知根本没把人吵醒，却和正常打扰同价。
2. **群发提醒和私聊同价**：`@所有人` / `@全体成员` / `@all` / `@everyone`
   一条就能惊动整群人，按私聊的单价算明显低估。

v2026-11-c 加两个系数（免打扰 ×0.5、群发 ×2.0）和一个单条封顶（×3.0），
并把 `savedSeconds` 的含义从「仅连发」改成「**优惠合计**」：

```
savedSeconds = 连发折扣 + 免打扰折扣 + 封顶
```

字段名沿用 `savedSeconds`（票据/明细/桌面组件/旧缓存都按这个键读），
但界面文案统一改成中性的「优惠已减 N 秒」，不再单独强调连发。

## 2. 计价模型

「不计打扰」记 0 秒，保留明细和通知条数，既不触发连发也不增加优惠秒数。其余通知只在最后取整一次，最低 1 秒：

```
mult = conversationFactor ?? appFactor(pkg)
if night:   mult *= nightMultiplier     # 1.5
if notice:  mult *= noticeMultiplier    # 2.0
if dnd:     mult *= dndMultiplier       # 0.5
if burst:   mult *= burstMultiplier     # 0.4
raw    = unitSeconds * mult
capped = min(raw, unitSeconds * capFactor)   # capFactor = 3.0
duty   = max(1, ceil(capped - 1e-9))
```

- `unitSeconds`：基础单价，默认 8 秒/条；账户可自定义（2–30 的整数）。
- `appFactor`：按包名前缀匹配，默认 1.0。
  - 微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`、TIM `com.tencent.tim`：**0.5**
  - 企业微信 `com.tencent.wework`、钉钉 `com.alibaba.android.rimet`、
    飞书 `com.ss.android.lark` / `com.larksuite.suite`、Teams `com.microsoft.teams`、
    Slack `com.slack`：**0.4**
- 会话分类优先于包名权重：工作 `work` ×1；家人 `family`、朋友 `friends` 各 ×0.25；
  闲聊群 `casual` ×0.1；不计打扰 `free` 为 0 秒。恢复「按应用计税」会删除本机规则。
  白天首条、默认单价且无其它规则时，同一个微信里的工作、朋友、闲聊、免计和未分类分别是 8、2、1、0、4 秒。
- `night`：北京时间 `hour >= 23 || hour < 7` 时为 1.5，其余 1.0。
- `notice`（v2026-11-c 新增）：投递时 `title + " " + text` 命中正则
  `[@＠]\s*(所有人|全体成员|全体|all|everyone)`（大小写不敏感，支持全角 ＠）。
  只判断布尔值，实现放在 `NotifRules.isBroadcastNotice(title, text)`。
- `dnd`（v2026-11-c 新增）：投递时设备处于免打扰 → 0.5。
  判定 = 系统免打扰（`Silence.hasAccess()` 为 true 且 `Silence.isSilenced()`）
  **或** `Prefs.dndUntil(context) > event.ts`（本 App「静默 1 小时」）。
  读系统 DND 前必须先确认有通知策略访问权限，否则按 false，避免 SecurityException。
- `burst`：与同设备、同应用、同类别上一条间隔 ≤ 120 秒时为 **0.4**（从第二条起），否则 1.0。
  **连发只在 Asia/Shanghai 自然日内判断：每天 0 点重置，跨日不算连发**（本机与服务端一致）。
- `capFactor`（v2026-11-c 新增）：单条封顶 3.0，即单条最多 `unit × 3.0` 秒。
  当前系数组合下（应用权重 ≤ 1、夜间 1.5、群发 2.0）最大乘数恰好是 3.0，
  所以封顶是护栏而不是日常折扣；以后若加入 >1 的应用权重或更高加成，它会立即生效。
- 免税额：默认 240 秒/天，账户可自定义（0–1800 的整数）；`balance = exemption - Σduty`，
  `balance < 0` 为欠税，`>= 0` 为结清。
- 结算日：Asia/Shanghai 自然日。

优惠合计（`savedSeconds`）：

```
baseMult = conversationFactor ?? appFactor(pkg)
if night:  baseMult *= nightMultiplier
if notice: baseMult *= noticeMultiplier
base  = max(1, ceil(unitSeconds * baseMult - 1e-9))
saved = max(0, base - duty)          # 每条事件分别算，再求和
```

也就是说 `base` 是「只算应用权重、夜间、群发加成」的原价，`duty` 是实际收的钱，
差额就是静默折扣、连发折扣和封顶省下来的秒数。没有命中任何折扣的事件 `saved = 0`。

示例（默认单价 8 秒）：

| 场景 | 单条税秒 | 优惠 |
| --- | --- | --- |
| 普通应用、白天、首条 | 8 | 0 |
| 普通应用、白天、连发第 2 条起 | ceil(8×0.4)=4 | 4 |
| 微信、白天、首条 | ceil(8×0.5)=4 | 0 |
| 微信、白天、连发第 2 条起 | ceil(8×0.5×0.4)=2 | 2 |
| 微信、夜间、首条 | ceil(8×0.5×1.5)=6 | 0 |
| 微信、夜间、连发第 2 条起 | ceil(8×0.5×1.5×0.4)=3 | 3 |
| 普通应用、白天、免打扰中、首条 | ceil(8×0.5)=4 | 4 |
| 微信、白天、免打扰、连发 | ceil(8×0.5×0.5×0.4)=1 | 3（base 4） |
| 普通应用、白天、群发 @所有人、首条 | ceil(8×2.0)=16 | 0 |
| 普通应用、夜间、群发、免打扰、连发 | ceil(8×1.5×2.0×0.5×0.4)=ceil(4.8)=5 | 19（base 24） |
| unit=30、夜间、群发（最坏情况） | raw=90，cap=90 → 90 | 0（恰好等于封顶） |

## 3. 事件标记与数据流

- 会话识别优先使用 Android 会话快捷方式和结构化会话标题；无结构化身份时只匹配
  聊天通知的完整标题，不以空格拆正文。应用/Android 用户隔离；没有稳定线索时仍按应用计税。
  没有快捷方式的同名会话可能无法区分，改名后需要重新分类。
- `ledger.db` v4 保存会话名称、身份和本机规则。选择分类只改变之后入账的通知，历史结算不回写。
  `POST /v1/events` 只附上 `conversationClass`，不送会话名称、身份或规则；缺省/未知类别回退到应用权重。
  选择上传通知原文时，标题字段仍可能包含联系人或群名。

- 每个通知事件新增两个**布尔**字段（只传布尔，不传原文）：
  - `dnd`：投递时设备是否处于免打扰；
  - `notice`：是否命中群发提醒正则。
- 客户端在 `NotificationWatchService.onNotificationPosted` 投递时计算一次并写入
  `ledger.db`（v3 迁移：`events` 加 `dnd/notice` 两列，老库 `onUpgrade`/`onOpen` 平滑补列，
  旧事件默认 0，与旧税则结果一致）。
- 上送 `POST /v1/events` 时带上 `dnd` / `notice`；服务端 `_validate_event` 用
  `as_bool` 解析，缺省按 `false`（兼容旧客户端）。
- 明细行 `lines[]` 增加 `dnd` / `notice`（条数）；`stats` 增加
  `dndEvents` / `noticeEvents`；`note` 按命中拼接，顺序固定：
  `夜间 N 条 · 连发 N 条 · 免打扰 N 条 · 群发 N 条`，只拼非零项。

## 4. 自定义税则（按账户）

- 账户级覆盖，存在服务端 `accounts` 表：
  `unit_seconds INTEGER NULL`、`exemption_per_day INTEGER NULL`（NULL = 用全局默认）。
- 同账户所有设备共享同一套税则；离线时本机先按本地值估算，联网后以服务端结算为准。
- `POST /v1/settings`（需要 token）
  body：`{"unitSeconds": 8, "exemptionPerDay": 240}`
  校验：unit 2–30 整数；exemption 0–1800 整数。返回与 `GET /v1/tariff` 相同的结构。
- `GET /v1/tariff` 返回**该账户生效值**（覆盖值或默认值），字段名不变，新增：
  - `dndMultiplier`: `0.5`
  - `noticeMultiplier`: `2.0`
  - `capFactor`: `3.0`
  - `appMultipliers`: `{"com.tencent.mm": 0.5, ...}`（前缀表）
  - `appMultiplierDefault`: `1.0`
- 自定义只影响当前账户的发票、周报与公开收据；税率版本号仍为 `2026-11-d`。
- 旧版 App 会忽略新字段，本机估算按旧规则（没有 dnd/notice 折扣/加成）；
  服务端以其收到的事件为准重新计价，允许旧客户端票面在极端情况有差异。文档已注明。

## 5. 隐私：标题默认不上送

- v1.6.0 起 `Api.pushEvents` 默认**完全省略 `title` 字段**；只有当
  `Prefs.uploadTitles(context)` 为 true（设置 → 同步 → 上送通知原文）时才写入。
- 服务端 `_validate_event` 的 `title` 可选，省略即存 `''`，不会因为缺 title 判无效；
  数据库 `events.title` 默认 `''`。
- `dnd` / `notice` 布尔始终上送（即使没开上送原文），否则服务端无法计价。
- 客户端本地账本仍保存通知摘要（明细/核对/删除需要），只改「上送」。
- 服务端日志不打印请求体或标题；token 只存 SHA-256；`titleContains` purge 仍保留，
  但只在事件里确实有 title（用户主动开启上送）时才有内容可匹配。

## 6. 服务端约定

- 常量：`TARIFF_VERSION = "2026-11-c"`，`TARIFF_UPDATED_AT = "2026-11-10T00:00:00Z"`，
  服务版本 `VERSION = "1.2.0"`。
- `build_day()` 内部读取账户税则，是**唯一计价点**；`invoice_payload()` /
  `handle_weekly` / `handle_receipt` 全部复用它，不允许出现第二份计价逻辑。
- invoice `stats` 新增 `dndEvents` / `noticeEvents`；`lines[].note` 按第 3 节拼接。
- 官网税率表/示例/FAQ 文案同步为 v2026-11-c 的新规则（勿扰折扣、群发加成、单条封顶）。
- 公开收据页（`/r/<shareId>`）：主动分享时生成随机链接，单页内联、0 圆角、保留 `noindex`，
  底部增加指向 `https://ql.130923.xyz` 的 CTA，`<head>` 增加 og:title/description/url。
- `verify.sh` 用新 fixture 重算并断言：appFactor、夜间、勿扰折扣、群发加成、封顶、
  `savedSeconds`（优惠合计）、自定义税则生效/恢复、公开收据金额、旧事件默认值、
  标题不上送。

### 服务端 fixture（verify.sh 用，已手算）

北京时间当天、同一设备：

| 事件 | 时间 | 命中 | 税秒 | base → 优惠 |
| --- | --- | --- | --- | --- |
| `com.verify.a` ×4（间隔 60 s） | 10:00–10:03 | 后 3 条连发 | 8 + 4×3 = 20 | 8 → 12 |
| `com.verify.dnd` ×2（间隔 60 s） | 10:05、10:06 | 免打扰；第 2 条还连发 | 4 + 2 = 6 | 8 → 4、8 → 6，共 10 |
| `com.verify.notice` ×1 | 10:07 | 群发 @所有人 | 16 | 16 → 0 |
| `com.tencent.mm` ×3 | 10:10、10:11、10:21 | 第 2 条连发 | 4 + 2 + 4 = 10 | 4 → 2（共 2） |
| `com.verify.b` ×1 | 23:30 | 夜间 | 12 | 12 → 0 |
| `com.verify.mix` ×2 | 23:50、23:51 | 夜间+群发+免打扰；第 2 条还连发 | 12 + 5 = 17 | 24 → 12、24 → 19，共 31 |

合计：**应缴 81 秒**，`savedSeconds` **55 秒**，events 13，bursts 6，
dndEvents 4，noticeEvents 3，nightRatio 3/13 = 0.2308，免税额 240 →
balance 159，paid。

自定义后（unit=4，exemption=120）同一 fixture 应得：

- `com.verify.a`：4 + 3×ceil(4×0.4)=2 → 10 秒（优惠 6）
- `com.verify.dnd`：ceil(4×0.5)=2 + ceil(4×0.5×0.4)=1 → 3 秒（优惠 5）
- `com.verify.notice`：ceil(4×2.0)=8 秒（优惠 0）
- `com.tencent.mm`：2 + ceil(4×0.5×0.4)=1 + 2 → 5 秒（优惠 1）
- `com.verify.b`：ceil(4×1.5)=6 秒（优惠 0）
- `com.verify.mix`：6 + ceil(4×1.5×2.0×0.5×0.4)=3 → 9 秒（优惠 15）

合计 **41 秒**，`savedSeconds` **27 秒**，balance 120 − 41 = 79，paid。

封顶 fixture（单独账户 unit=30、exemption=240，23:10 / 23:11 两条「夜间+群发」）：

- 第 1 条：30×1.5×2.0 = 90 = min(raw, 30×3.0) → **90 秒**（优惠 0）
- 第 2 条：30×1.5×2.0×0.4 = 36 ≤ 90 → **36 秒**（base 90，优惠 54）

合计 **126 秒**，`savedSeconds` **54 秒**。

## 7. App 侧约定（Kotlin）

`core/Model.kt`：

```kotlin
data class NotifEvent(..., val dnd: Boolean = false, val notice: Boolean = false)
data class MarkedEvent(..., val dnd: Boolean, val notice: Boolean, val duty: Int, val saved: Int)
data class Tariff(
    ..., val dndMultiplier: Double = 0.5, val noticeMultiplier: Double = 2.0,
    val capFactor: Double = 3.0, ...
)
data class InvoiceLine(..., val dnd: Int = 0, val notice: Int = 0, val note: String = "")
data class Invoice(..., val savedSeconds: Int = 0, val dndEvents: Int = 0,
                   val noticeEvents: Int = 0, ...)

object Duty {
    // 按 ts 升序返回，规则与服务端逐位一致；saved 是单条优惠合计
    fun mark(events: List<NotifEvent>, tariff: Tariff): List<MarkedEvent>
    fun compute(...): Invoice            // 汇总 savedSeconds / dndEvents / noticeEvents
    fun weeklyLocal(...): Weekly         // 内部走 compute，自动一致
}
```

- 默认值必须保留：旧缓存 JSON 缺 `dnd/notice/dndEvents/noticeEvents/新系数` 也能解析。
- `Json.parseTariff` / `tariffToJson` 读写 `dndMultiplier/noticeMultiplier/capFactor`；
  `Json.parseInvoice` / `invoiceToJson` 读写 `lines[].dnd/notice` 与
  `stats.dndEvents/noticeEvents`。

`data/Store.kt`：

- `ledger.db` **v3**：`events` 加 `dnd INTEGER NOT NULL DEFAULT 0`、
  `notice INTEGER NOT NULL DEFAULT 0`；`onUpgrade`（oldVersion < 3）与 `onOpen`
  都用 `PRAGMA table_info` 幂等补列。`insertEvent` / `pendingEvents` /
  `eventsBetween` / `recent` 全部读写这两列。
- `Prefs` 新增：

```kotlin
fun uploadTitles(c): Boolean            // 默认 false，key upload_titles
fun setUploadTitles(c, on: Boolean)
fun syncEnabled(c): Boolean             // 默认 true，key sync_enabled
fun setSyncEnabled(c, on: Boolean)
fun filteredToday(c): Int               // 按 Fiscal.today() 跨天返回 0
fun addFiltered(c)                      // 先按天重置，再 +1
```

`filteredCount`（历史累计）保留给旧代码兼容，本轮界面不再用它。

`sync/Sync.kt`（app-settings 维护）：

- 同步开头若 `!Prefs.syncEnabled(context)` 直接返回，不发任何网络请求。
- 上送事件时调用 `Api.pushEvents(token, deviceId, batch, Prefs.uploadTitles(context))`。

`net/Api.kt`：

```kotlin
suspend fun pushEvents(token, deviceId, events, uploadTitles: Boolean = false): PushResult
```

默认 `uploadTitles = false`（最安全的默认值）：不传参数时标题不会离机。

## 8. UI 约定

- 明细（按时间、应用下钻、周报日详情）：同应用 120 秒内的连发默认折叠成一行
  （应用 + 时段 + 条数 + 合计税秒 + 省 N 秒），点击展开逐条；默认全部收起。
  行尾标签可显示 夜 / 连 / 静 / 群（`MarkedEvent.night/burst/dnd/notice`）。
- 票据：合计区显示中性的「优惠已减 N 秒」（= `savedSeconds`，全部优惠），
  不再用「连发」绿色强调；税目 `note` 按第 3 节顺序展示。
- 计价规则弹层：展示单价值、夜间加成、群发加成、勿扰折扣、连发折扣（不是加成）、
  单条封顶、聊天/办公折扣表与免税额；提供「调整我的税则」入口。
- 设置新增「计税规则」页：单价 -/＋（2–30）、免税额 -/＋（0–1800，步长 60）、
  恢复默认、应用并结算；离线时先本地生效并标记待同步。
- 桌面组件：显示税额与「优惠已减 N 秒」；「钉到桌面」在系统不支持时不能只弹一个
  失败 toast，必须展示手动添加步骤。
- 快捷磁贴：图标固定 `res/drawable/ic_tile.xml`（单色白收据 + 锯齿底 + evenOdd
  镂空横线，系统按状态染色，不能出现黑色线）；label「静默」。
