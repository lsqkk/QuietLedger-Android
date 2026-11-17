package dev.quietledger.app.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import dev.quietledger.app.core.Account
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Invoice
import dev.quietledger.app.core.Tariff
import dev.quietledger.app.data.LedgerDb
import dev.quietledger.app.data.Prefs
import dev.quietledger.app.net.Api
import dev.quietledger.app.net.ApiException
import dev.quietledger.app.net.Json
import dev.quietledger.app.service.NoiseSweeper
import dev.quietledger.app.service.SyncJobService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException

data class SyncResult(
    val ok: Boolean,
    val pushed: Int,
    val pending: Int,
    val invoice: Invoice?,
    val tariff: Tariff?,
    val message: String,
)

object SyncManager {
    private val mutex = Mutex()

    /** 本地缓存的税率表；没有（或还是 v1.3.1 的旧规则）则用新默认规则。 */
    fun cachedTariff(context: Context): Tariff {
        val raw = Prefs.tariffJson(context) ?: return Tariff.DEFAULT
        return try {
            val parsed = Json.parseTariff(JSONObject(raw))
            // v1.3.1 缓存里 burstMultiplier=2.0（旧规则是连发加价）且没有
            // appMultipliers；首次成功同步前按它估算会明显偏高，直接回落到
            // v2026-11-b 默认表，等 sync 拉到服务器税则后覆盖缓存。
            if (parsed.burstMultiplier > 1.0 || parsed.appMultipliers.isEmpty()) {
                Tariff.DEFAULT
            } else {
                parsed
            }
        } catch (_: Exception) {
            Tariff.DEFAULT
        }
    }

    fun cachedInvoice(context: Context): Invoice? {
        val raw = Prefs.invoiceJson(context) ?: return null
        return try {
            Json.parseInvoice(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    /** 把一个账户/设备的事件推给服务器，然后拉取服务器结算的当日税单。 */
    suspend fun sync(context: Context): SyncResult = mutex.withLock {
        val db = LedgerDb.get(context)
        // 云端同步关闭：不建账户、不推送、不拉取，只回本机账本。任何调用方都不该在这时
        // 产生网络请求（自动同步 / 通知防抖 / 手动同步入口都已提前挡掉），这里是最后一道闸。
        if (!Prefs.syncEnabled(context)) {
            return@withLock SyncResult(
                ok = false,
                pushed = 0,
                pending = db.pendingCount(),
                invoice = cachedInvoice(context),
                tariff = cachedTariff(context),
                message = "同步已关闭，仅本机记账",
            )
        }
        val deviceId = Prefs.deviceId(context)
        var pushed = 0
        // 每次同步先自动合并历史刷屏/状态类通知（不依赖应用名，无需用户操作）。
        val sweep = try {
            NoiseSweeper.sweep(context)
        } catch (_: Exception) {
            NoiseSweeper.Result(0, emptyList())
        }
        try {
            val token = Prefs.token(context) ?: run {
                val acc: Account = Api.createAccount(deviceId, Prefs.modelName())
                Prefs.saveAccount(context, acc.accountCode, acc.token)
                acc.token
            }

            // v2026-11-b：离线时改的税则先本地生效；联网后先推给服务器，
            // 把服务器返回的生效税则写回缓存，再拉 tariff / invoice。
            if (Prefs.taxPending(context)) {
                val rules = Api.setTaxRules(
                    token,
                    deviceId,
                    Prefs.pendingTaxUnit(context),
                    Prefs.pendingTaxExemption(context),
                )
                Prefs.saveTariffJson(context, Json.tariffToJson(rules))
                Prefs.clearPendingTax(context)
            }

            // 隐私默认：只有「上送通知原文」开启时才把 title 交给网络层，否则整段省略。
            val uploadTitles = Prefs.uploadTitles(context)
            while (true) {
                val batch = db.pendingEvents(200)
                if (batch.isEmpty()) break
                val r = Api.pushEvents(token, deviceId, batch, uploadTitles = uploadTitles)
                db.markSynced(batch.map { it.id })
                pushed += r.accepted + r.duplicated
                if (batch.size < 200) break
            }

            val tariff = Api.tariff(token, deviceId)
            Prefs.saveTariffJson(context, Json.tariffToJson(tariff))

            val today = Fiscal.iso(Fiscal.today())
            val invoice = Api.invoice(token, deviceId, today)
            Prefs.saveInvoiceJson(context, Json.invoiceToJson(invoice))

            // 本机删掉的刷屏事件，同步从服务器删除（按事件 id，特征识别不按应用名）。
            if (sweep.eventIds.isNotEmpty()) {
                runCatching { Api.purgeEvents(token, deviceId, eventIds = sweep.eventIds) }
            }
            // 昵称在本地修改过就同步到服务器（分享收据会显示）。
            val nickname = Prefs.nickname(context)
            if (nickname.isNotBlank() && nickname != Prefs.syncedNickname(context)) {
                runCatching {
                    val saved = Api.setProfile(token, deviceId, nickname)
                    Prefs.setSyncedNickname(context, saved)
                }
            }

            Prefs.setLastSyncAt(context, System.currentTimeMillis())
            Prefs.setLastError(context, null)

            SyncResult(
                ok = true,
                pushed = pushed,
                pending = db.pendingCount(),
                invoice = invoice,
                tariff = tariff,
                message = "已结算",
            )
        } catch (e: ApiException) {
            val friendly = "同步失败：${e.message}"
            Prefs.setLastError(context, friendly)
            SyncResult(
                ok = false,
                pushed = pushed,
                pending = db.pendingCount(),
                invoice = cachedInvoice(context),
                tariff = cachedTariff(context),
                message = friendly,
            )
        } catch (e: IOException) {
            val friendly = "网络不可用，已转本机记账；联网后自动补送（${db.pendingCount()} 条待同步）"
            Prefs.setLastError(context, friendly)
            SyncResult(
                ok = false,
                pushed = pushed,
                pending = db.pendingCount(),
                invoice = cachedInvoice(context),
                tariff = cachedTariff(context),
                message = friendly,
            )
        } catch (e: Exception) {
            Prefs.setLastError(context, e.message)
            SyncResult(
                ok = false,
                pushed = pushed,
                pending = db.pendingCount(),
                invoice = cachedInvoice(context),
                tariff = cachedTariff(context),
                message = "同步失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /** Redeem a one-time invite before syncing against the new account. */
    suspend fun join(context: Context, inviteCode: String): SyncResult {
        if (!Prefs.syncEnabled(context)) {
            return SyncResult(
                ok = false,
                pushed = 0,
                pending = LedgerDb.get(context).pendingCount(),
                invoice = cachedInvoice(context),
                tariff = cachedTariff(context),
                message = "同步已关闭，仅本机记账",
            )
        }
        try {
            mutex.withLock {
                val deviceId = Prefs.deviceId(context)
                val acc = Api.joinAccount(inviteCode, deviceId, Prefs.modelName())
                Prefs.saveAccount(context, acc.accountCode, acc.token)
            }
        } catch (e: ApiException) {
            return SyncResult(
                false, 0, LedgerDb.get(context).pendingCount(),
                cachedInvoice(context), cachedTariff(context),
                if (e.apiCode == "invalid_invite") "邀请码无效或已过期" else "加入失败：${e.message}",
            )
        } catch (e: IOException) {
            return SyncResult(
                false, 0, LedgerDb.get(context).pendingCount(),
                cachedInvoice(context), cachedTariff(context), "网络不可用",
            )
        }
        return sync(context)
    }
}

/** 后台同步：15 分钟周期（联网才跑）+ 通知到达后的 5 秒防抖单次任务。 */
object SyncScheduler {
    const val JOB_PERIODIC = 1001
    const val JOB_ONESHOT = 1002

    fun ensurePeriodic(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        // 云端同步关闭：撤掉周期与待跑的单次任务，后台不再联网。
        if (!Prefs.syncEnabled(context)) {
            js.cancel(JOB_PERIODIC)
            js.cancel(JOB_ONESHOT)
            return
        }
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        val job = JobInfo.Builder(
            JOB_PERIODIC,
            ComponentName(context, SyncJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15 * 60 * 1000L)
            .build()
        js.schedule(job)
    }

    fun scheduleSoon(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        // 同步关闭时不排任务，已经排上的也撤掉。
        if (!Prefs.syncEnabled(context)) {
            js.cancel(JOB_ONESHOT)
            return
        }
        // 已经在排队的单次任务不要再排：重复 schedule 会把 minimumLatency 重新计时，
        // 通知密集时结算会被无限推迟（看起来像"不计了"）。
        if (js.getPendingJob(JOB_ONESHOT) != null) return
        val job = JobInfo.Builder(
            JOB_ONESHOT,
            ComponentName(context, SyncJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(10_000L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_LINEAR)
            .build()
        js.schedule(job)
    }
}
