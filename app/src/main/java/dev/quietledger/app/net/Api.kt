package dev.quietledger.app.net

import dev.quietledger.app.BuildConfig
import dev.quietledger.app.core.Account
import dev.quietledger.app.core.DeviceInfo
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.InvoiceLine
import dev.quietledger.app.core.NotifEvent
import dev.quietledger.app.core.Report
import dev.quietledger.app.core.ReportApp
import dev.quietledger.app.core.ReportBucket
import dev.quietledger.app.core.ReportWindow
import dev.quietledger.app.core.Tariff
import dev.quietledger.app.core.Weekly
import dev.quietledger.app.core.WeeklyApp
import dev.quietledger.app.core.WeeklyDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ApiException(val status: Int, val apiCode: String?, message: String) : IOException(message)

data class HealthInfo(val ok: Boolean, val version: String, val time: String)
data class PushResult(val accepted: Int, val duplicated: Int)

/** 服务器 /v1/app/latest 下发的版本清单，APK 由同一后端的 /apk 路由托管。 */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val notes: String,
    val publishedAt: String,
)

object Api {
    private val primaryBase: String = BuildConfig.API_BASE.trimEnd('/')
    private val healthFallback = BuildConfig.HEALTH_FALLBACK.trimEnd('/')
    private val relayBase = "https://ql.130923.xyz:80".takeIf { primaryBase == "https://ql.130923.xyz" }
    private val relayFactory by lazy {
        RelaySocketFactory(URL(primaryBase).host, HttpsURLConnection.getDefaultSSLSocketFactory())
    }
    private val routeLock = Mutex()

    @Volatile
    private var selectedBase: String? = null

    val baseUrl: String get() = primaryBase
    val serverHost: String get() = URL(primaryBase).host

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        deviceId: String? = null,
    ): JSONObject = withRoute(retry = method == "GET") { base ->
        callAt(base, method, path, body, token, deviceId)
    }

    private suspend fun selectBase(): String {
        val relay = relayBase ?: return primaryBase
        return routeLock.withLock {
            selectedBase ?: try {
                callAt(primaryBase, "GET", "/health", null, null, null)
                primaryBase.also { selectedBase = it }
            } catch (failure: IOException) {
                if (failure is ApiException) throw failure
                callAt(relay, "GET", "/health", null, null, null)
                relay.also { selectedBase = it }
            }
        }
    }

    private suspend fun <T> withRoute(retry: Boolean, request: suspend (String) -> T): T {
        val base = selectBase()
        return try {
            request(base)
        } catch (failure: IOException) {
            if (failure is ApiException) throw failure
            routeLock.withLock {
                if (selectedBase == base) selectedBase = null
            }
            val relay = relayBase
            if (!retry || relay == null) throw failure
            val alternate = if (base == primaryBase) relay else primaryBase
            request(alternate).also {
                routeLock.withLock { selectedBase = alternate }
            }
        }
    }

    private fun openConnection(base: String, path: String): HttpURLConnection {
        val url = URL(base + path)
        if (base != relayBase) return url.openConnection() as HttpURLConnection
        return (url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
            sslSocketFactory = relayFactory
        }
    }

    fun openApkConnection(apkUrl: String): HttpURLConnection {
        val origins = listOfNotNull(primaryBase, relayBase)
        val origin = origins.firstOrNull { apkUrl == "$it/apk/quietledger.apk" }
            ?: throw IOException("安装包地址不可用")
        return openConnection(origin, "/apk/quietledger.apk")
    }

    private suspend fun callAt(
        base: String,
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
        deviceId: String?,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = URL(base + path)
        require(url.protocol == "https" || (
            method == "GET" && path == "/health" && token == null && body == null && deviceId == null
        ))
        val conn = openConnection(base, path)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "QuietLedger/${BuildConfig.VERSION_NAME} Android")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (deviceId != null) conn.setRequestProperty("X-Device-Id", deviceId)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                var code: String? = null
                var msg = "HTTP $status"
                try {
                    val err = JSONObject(text)
                    code = err.optString("error").ifBlank { null }
                    msg = err.optString("message").ifBlank { msg }
                } catch (_: Exception) {
                }
                throw ApiException(status, code, msg)
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun health(): HealthInfo {
        val o = try {
            call("GET", "/health")
        } catch (e: IOException) {
            if (healthFallback.isBlank() || e is ApiException) throw e
            callAt(healthFallback, "GET", "/health", null, null, null)
        }
        return HealthInfo(
            ok = o.optString("status") == "ok",
            version = o.optString("version", "?"),
            time = o.optString("time", ""),
        )
    }

    suspend fun createAccount(deviceId: String, model: String): Account {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("model", model)
            .put("appVersion", BuildConfig.VERSION_NAME)
        val o = call("POST", "/v1/accounts", body, deviceId = deviceId)
        return Account(
            accountCode = o.getString("accountCode"),
            token = o.getString("token"),
            deviceId = deviceId,
        )
    }

    suspend fun issueInvite(token: String, deviceId: String): String =
        call("POST", "/v1/accounts/invite", JSONObject(), token, deviceId).getString("inviteCode")

    suspend fun joinAccount(inviteCode: String, deviceId: String, model: String): Account {
        val body = JSONObject()
            .put("inviteCode", inviteCode.trim())
            .put("deviceId", deviceId)
            .put("model", model)
            .put("appVersion", BuildConfig.VERSION_NAME)
        val o = call("POST", "/v1/accounts/join", body, deviceId = deviceId)
        return Account(
            accountCode = o.getString("accountCode"),
            token = o.getString("token"),
            deviceId = deviceId,
        )
    }

    suspend fun shareReceipt(token: String, deviceId: String, date: String): String {
        val body = JSONObject().put("date", date)
        val shareId = call("POST", "/v1/receipt-shares", body, token, deviceId).getString("shareId")
        return "$primaryBase/r/$shareId"
    }

    suspend fun tariff(token: String, deviceId: String): Tariff =
        Json.parseTariff(call("GET", "/v1/tariff", token = token, deviceId = deviceId))

    /**
     * 账户级税则：单价 2–30 秒、每日免税额 0–1800 秒。
     * 返回账户生效后的税则（与 GET /v1/tariff 相同结构），App 直接写缓存。
     */
    suspend fun setTaxRules(
        token: String,
        deviceId: String,
        unitSeconds: Int,
        exemptionPerDay: Int,
    ): Tariff {
        val body = JSONObject()
            .put("unitSeconds", unitSeconds.coerceIn(2, 30))
            .put("exemptionPerDay", exemptionPerDay.coerceIn(0, 1800))
        return Json.parseTariff(
            call("POST", "/v1/settings", body, token = token, deviceId = deviceId),
        )
    }

    /**
     * 上送事件批次。
     *
     * v1.6.0 隐私默认：只有 [uploadTitles] 为 true（设置 → 同步 → 上送通知原文）时
     * 才写 `title` 字段，默认整段省略；`dnd/notice` 布尔始终上送。
     * 调用方（SyncManager）应传 `Prefs.uploadTitles(context)`。
     */
    suspend fun pushEvents(
        token: String,
        deviceId: String,
        events: List<NotifEvent>,
        uploadTitles: Boolean = false,
    ): PushResult {
        val arr = JSONArray()
        for (e in events) {
            val o = JSONObject()
                .put("id", e.id)
                .put("pkg", e.pkg)
                .put("app", e.app)
                .put("ts", e.ts)
                .put("priority", e.priority)
                .put("category", e.category)
                .put("ongoing", e.ongoing)
                .put("clearable", e.clearable)
                .put("conversationClass", e.conversationClass.wire)
                .put("dnd", e.dnd)
                .put("notice", e.notice)
            // 默认完全省略 title 字段（不是空串），避免任何原文离机。
            if (uploadTitles) o.put("title", e.title)
            arr.put(o)
        }
        val body = JSONObject().put("deviceId", deviceId).put("events", arr)
        val o = call("POST", "/v1/events", body, token = token, deviceId = deviceId)
        return PushResult(
            accepted = o.optInt("accepted", o.optInt("stored", 0)),
            duplicated = o.optInt("duplicated", 0),
        )
    }

    suspend fun invoice(token: String, deviceId: String, date: String): Invoice =
        Json.parseInvoice(call("GET", "/v1/invoice?date=$date&deviceId=all", token = token, deviceId = deviceId))

    suspend fun weekly(token: String, deviceId: String, end: String, days: Int = 7): Weekly =
        Json.parseWeekly(
            call("GET", "/v1/weekly?end=$end&days=${days.coerceIn(1, 30)}", token = token, deviceId = deviceId),
        )

    /**
     * 周期统计（服务端跨设备汇总）：kind=week/month/quarter/year。
     * 本机同步关闭或网络失败时由 App 用本机账本算同样的结构（source=local）。
     */
    suspend fun report(
        token: String,
        deviceId: String,
        kind: String,
        end: String,
        days: Int = 7,
    ): Report = Json.parseReport(
        call(
            "GET",
            "/v1/report?kind=$kind&end=$end&days=${days.coerceIn(1, 30)}",
            token = token,
            deviceId = deviceId,
        ),
    )

    /** 删除账户里已入账的重复/噪音通知；服务器按剩余事件重新结算。 */
    suspend fun purgeEvents(
        token: String,
        deviceId: String,
        packages: List<String> = emptyList(),
        titleContains: List<String> = emptyList(),
        eventIds: List<String> = emptyList(),
    ): Int {
        if (packages.isEmpty() && titleContains.isEmpty() && eventIds.isEmpty()) return 0
        val body = JSONObject()
        if (packages.isNotEmpty()) {
            val arr = JSONArray()
            packages.forEach { arr.put(it) }
            body.put("packages", arr)
        }
        if (titleContains.isNotEmpty()) {
            val arr = JSONArray()
            titleContains.forEach { arr.put(it) }
            body.put("titleContains", arr)
        }
        if (eventIds.isNotEmpty()) {
            val arr = JSONArray()
            eventIds.take(500).forEach { arr.put(it) }
            body.put("eventIds", arr)
        }
        val o = call("POST", "/v1/events/purge", body, token = token, deviceId = deviceId)
        return o.optInt("deleted", 0)
    }

    /** 设置昵称，会显示在分享收据上。 */
    suspend fun setProfile(token: String, deviceId: String, nickname: String): String {
        val body = JSONObject().put("nickname", nickname)
        val o = call("POST", "/v1/profile", body, token = token, deviceId = deviceId)
        return o.optString("nickname", nickname)
    }

    suspend fun devices(token: String, deviceId: String): List<DeviceInfo> {
        val o = call("GET", "/v1/devices", token = token, deviceId = deviceId)
        val arr = o.optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            DeviceInfo(
                deviceId = d.optString("deviceId", ""),
                model = d.optString("model", ""),
                lastSeenAt = d.optString("lastSeenAt", ""),
                eventCount = d.optInt("eventCount", 0),
            )
        }
    }

    /** 公开接口：不需要账户 token。 */
    suspend fun appLatest(): AppUpdate = withRoute(retry = true) { origin ->
        val o = callAt(origin, "GET", "/v1/app/latest", null, null, null)
        AppUpdate(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            apkUrl = "$origin/apk/quietledger.apk",
            apkSize = o.optLong("apkSize", 0L),
            sha256 = o.optString("sha256", ""),
            notes = o.optString("notes", ""),
            publishedAt = o.optString("publishedAt", ""),
        )
    }
}

/** JSON <-> 模型，同时用于本地缓存（离线时展示上一次服务器税单）。 */
object Json {

    private fun parseAppMultipliers(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val prefix = keys.next()
            out[prefix] = o.optDouble(prefix, Tariff.APP_MULTIPLIER_DEFAULT)
        }
        return out
    }

    fun parseTariff(o: JSONObject): Tariff = Tariff(
        version = o.optString("version", "unknown"),
        unitSeconds = o.optInt("unitSeconds", 8),
        nightMultiplier = o.optDouble("nightMultiplier", 1.5),
        nightStartHour = o.optInt("nightStartHour", 23),
        nightEndHour = o.optInt("nightEndHour", 7),
        burstMultiplier = o.optDouble("burstMultiplier", 0.4),
        burstWindowSec = o.optInt("burstWindowSec", 120),
        dndMultiplier = o.optDouble("dndMultiplier", 0.5),
        noticeMultiplier = o.optDouble("noticeMultiplier", 2.0),
        capFactor = o.optDouble("capFactor", 3.0),
        exemptionPerDay = o.optInt("exemptionPerDay", 240),
        appMultipliers = parseAppMultipliers(o.optJSONObject("appMultipliers")),
        updatedAt = o.optString("updatedAt", ""),
    )

    fun tariffToJson(t: Tariff): String {
        val multipliers = JSONObject()
        for ((prefix, value) in t.appMultipliers) multipliers.put(prefix, value)
        return JSONObject()
            .put("version", t.version)
            .put("unitSeconds", t.unitSeconds)
            .put("nightMultiplier", t.nightMultiplier)
            .put("nightStartHour", t.nightStartHour)
            .put("nightEndHour", t.nightEndHour)
            .put("burstMultiplier", t.burstMultiplier)
            .put("burstWindowSec", t.burstWindowSec)
            .put("dndMultiplier", t.dndMultiplier)
            .put("noticeMultiplier", t.noticeMultiplier)
            .put("capFactor", t.capFactor)
            .put("exemptionPerDay", t.exemptionPerDay)
            .put("appMultipliers", multipliers)
            .put("appMultiplierDefault", Tariff.APP_MULTIPLIER_DEFAULT)
            .put("updatedAt", t.updatedAt)
            .toString()
    }

    fun parseInvoice(o: JSONObject): Invoice {
        val linesArr = o.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until linesArr.length()).map { i ->
            val l = linesArr.getJSONObject(i)
            InvoiceLine(
                app = l.optString("app", "未知"),
                pkg = l.optString("pkg", ""),
                count = l.optInt("count", 0),
                duty = l.optInt("duty", 0),
                night = l.optInt("night", 0),
                burst = l.optInt("burst", 0),
                dnd = l.optInt("dnd", 0),
                notice = l.optInt("notice", 0),
                note = l.optString("note", ""),
            )
        }
        val stats = o.optJSONObject("stats") ?: JSONObject()
        val byHour = IntArray(24)
        stats.optJSONArray("byHour")?.let { a ->
            for (i in 0 until minOf(24, a.length())) byHour[i] = a.optInt(i, 0)
        }
        val devices = ArrayList<String>()
        stats.optJSONArray("devices")?.let { a ->
            for (i in 0 until a.length()) devices.add(a.optString(i))
        }
        return Invoice(
            invoiceId = o.optString("invoiceId", "LOCAL"),
            accountCode = o.optString("accountCode", ""),
            date = o.optString("date", ""),
            issuedAt = o.optString("issuedAt", ""),
            tariffVersion = o.optString("tariffVersion", ""),
            lines = lines,
            totalDuty = o.optInt("totalDuty", 0),
            exemption = o.optInt("exemption", 240),
            balance = o.optInt("balance", 0),
            status = o.optString("status", "paid"),
            events = stats.optInt("events", 0),
            nightRatio = stats.optDouble("nightRatio", 0.0),
            bursts = stats.optInt("bursts", 0),
            savedSeconds = stats.optInt("savedSeconds", 0),
            dndEvents = stats.optInt("dndEvents", 0),
            noticeEvents = stats.optInt("noticeEvents", 0),
            byHour = byHour,
            devices = devices,
            source = "server",
        )
    }

    fun invoiceToJson(inv: Invoice): String {
        val lines = JSONArray()
        for (l in inv.lines) {
            lines.put(
                JSONObject()
                    .put("app", l.app)
                    .put("pkg", l.pkg)
                    .put("count", l.count)
                    .put("duty", l.duty)
                    .put("night", l.night)
                    .put("burst", l.burst)
                    .put("dnd", l.dnd)
                    .put("notice", l.notice)
                    .put("note", l.note),
            )
        }
        val byHour = JSONArray()
        for (h in inv.byHour) byHour.put(h)
        val devices = JSONArray()
        for (d in inv.devices) devices.put(d)
        val stats = JSONObject()
            .put("events", inv.events)
            .put("nightRatio", inv.nightRatio)
            .put("bursts", inv.bursts)
            .put("savedSeconds", inv.savedSeconds)
            .put("dndEvents", inv.dndEvents)
            .put("noticeEvents", inv.noticeEvents)
            .put("byHour", byHour)
            .put("devices", devices)
        return JSONObject()
            .put("invoiceId", inv.invoiceId)
            .put("accountCode", inv.accountCode)
            .put("date", inv.date)
            .put("issuedAt", inv.issuedAt)
            .put("tariffVersion", inv.tariffVersion)
            .put("lines", lines)
            .put("totalDuty", inv.totalDuty)
            .put("exemption", inv.exemption)
            .put("balance", inv.balance)
            .put("status", inv.status)
            .put("stats", stats)
            .toString()
    }

    fun parseWeekly(o: JSONObject): Weekly {
        val daysArr = o.optJSONArray("days") ?: JSONArray()
        val days = (0 until daysArr.length()).map { i ->
            val d = daysArr.getJSONObject(i)
            WeeklyDay(d.optString("date"), d.optInt("events"), d.optInt("duty"))
        }
        val topArr = o.optJSONArray("topApps") ?: JSONArray()
        val top = (0 until topArr.length()).map { i ->
            val t = topArr.getJSONObject(i)
            WeeklyApp(t.optString("app"), t.optInt("count"), t.optInt("duty"))
        }
        return Weekly(
            start = o.optString("start", ""),
            end = o.optString("end", ""),
            totalDuty = o.optInt("totalDuty", 0),
            totalEvents = o.optInt("totalEvents", 0),
            days = days,
            topApps = top,
            nightRatio = o.optDouble("nightRatio", 0.0),
            tariffVersion = o.optString("tariffVersion", ""),
            source = "server",
        )
    }

    /** 服务端 /v1/report：柱子既可能是日（周/月）也可能是月（季/年），label 与 start/end 都带上。 */
    fun parseReport(o: JSONObject): Report {
        val bucketArr = o.optJSONArray("buckets") ?: JSONArray()
        val buckets = (0 until bucketArr.length()).map { i ->
            val b = bucketArr.getJSONObject(i)
            ReportBucket(
                label = b.optString("label", ""),
                duty = b.optInt("duty", 0),
                events = b.optInt("events", 0),
                start = b.optString("start", ""),
                end = b.optString("end", ""),
            )
        }
        val topArr = o.optJSONArray("topApps") ?: JSONArray()
        val top = (0 until topArr.length()).map { i ->
            val t = topArr.getJSONObject(i)
            ReportApp(
                app = t.optString("app", "未知"),
                count = t.optInt("count", 0),
                duty = t.optInt("duty", 0),
                pkg = t.optString("pkg", ""),
            )
        }
        val prevObj = o.optJSONObject("prev")
        val prev = prevObj?.let {
            ReportWindow(
                start = it.optString("start", ""),
                end = it.optString("end", ""),
                totalDuty = it.optInt("totalDuty", 0),
                totalEvents = it.optInt("totalEvents", 0),
            )
        }
        return Report(
            kind = o.optString("kind", "week"),
            start = o.optString("start", ""),
            end = o.optString("end", ""),
            dayCount = o.optInt("dayCount", 0),
            totalDuty = o.optInt("totalDuty", 0),
            totalEvents = o.optInt("totalEvents", 0),
            savedSeconds = o.optInt("savedSeconds", 0),
            nightRatio = o.optDouble("nightRatio", 0.0),
            buckets = buckets,
            topApps = top,
            prev = prev,
            tariffVersion = o.optString("tariffVersion", ""),
            source = "server",
        )
    }
}
