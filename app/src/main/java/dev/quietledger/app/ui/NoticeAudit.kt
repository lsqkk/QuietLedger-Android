package dev.quietledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.core.Skipped

/**
 * 通知核对：把「为什么没记上」摊开给用户看。
 *
 * 三种情况一眼能分清：
 *  - 通知使用权没开：一条都收不到；
 *  - 授权还在但监听断开：最近入账会停住，这里给出重连；
 *  - 收到了但被规则跳过：下面按原因列出样本，用户能看出是系统通知还是重复。
 *
 * v1.8.0（审计 P1-6）：全 App 只叫「跳过」；样本数和今日跳过数并排给，附「为什么样本更少」。
 */
@Composable
fun NoticeAuditSheet(state: AppState) {
    val enabled = state.listenerOn
    val connected = state.listenerConnected
    val trouble = state.listenerTrouble
    var whyOpen by remember { mutableStateOf(false) }
    // 最近入账优先用实时监听时间；若本机账本已有今天的事件（备份恢复/导入），用最后一条兜底。
    val lastAt = if (state.lastEventAt > 0) state.lastEventAt
    else state.todayEvents.maxOfOrNull { it.ts } ?: 0L
    QlSheet(
        title = "通知核对",
        subtitle = "最近 3 天",
        onClose = { state.dismissAudit() },
        scrollable = true,
    ) {
        Column(Modifier.fillMaxWidth()) {
            AuditKeyValue("监听状态", when {
                !enabled -> "未开启"
                connected -> "已连接"
                trouble -> "已断开"
                else -> "连接中"
            }, when {
                !enabled -> QL.Seal
                connected -> QL.Green
                trouble -> QL.Amber
                else -> QL.InkSoft
            })
            AuditKeyValue(
                "最近入账",
                if (lastAt > 0) Fiscal.md(lastAt) + " " + Fiscal.clockText(lastAt) else "暂无",
                if (lastAt > 0) QL.Ink else QL.InkFaint,
            )
            AuditKeyValue("今日入账", "${state.localTodayEvents} 条", QL.Ink)
            // 与明细「跳过」同一口径：今日被规则跳过（不计数入账）的条数，不是历史累计、也不是样本条数。
            AuditKeyValue(
                "跳过",
                "${state.filteredToday} 条",
                if (state.filteredToday > 0) QL.Amber else QL.InkFaint,
            )
            if (state.auditReasons.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                HairHLine(color = QL.RuleSoft)
                // 原因一行一个，不再用「状态/进度类」和「/」把三件事压成一行。
                Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    state.auditReasons.forEach { (reason, count) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(skipReasonLabel(reason), style = QL.bodySoft, modifier = Modifier.weight(1f))
                            MonoText("$count", style = QL.monoSmall, color = QL.InkSoft)
                        }
                    }
                }
            }

            if (!enabled) {
                Spacer(Modifier.height(10.dp))
                NoticeBar(
                    text = "通知使用权未开启，App 收不到任何通知。",
                    actionLabel = "去开启",
                    onAction = { state.openListenerSettings() },
                )
            } else if (trouble) {
                Spacer(Modifier.height(10.dp))
                NoticeBar(
                    text = "监听已断开，系统可能清理过后台。",
                    actionLabel = "重连",
                    onAction = { state.reconnectListener() },
                    tone = QL.Amber,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "样本 ${state.auditSkipped.size} 条 / 今日 ${state.filteredToday} 条",
                    style = QL.label.copy(color = QL.InkSoft, letterSpacing = 1.4.sp),
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            HairHLine(color = QL.RuleSoft)
            Box(Modifier.fillMaxWidth().heightIn(max = 268.dp)) {
                if (state.auditSkipped.isEmpty()) {
                    Column(Modifier.padding(vertical = 16.dp)) {
                        Text("今天没有跳过任何通知", style = QL.h3)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "如果这里是 0、今日入账也是 0，说明通知没有到达 App，先看重连或授权。",
                            style = QL.bodySoft,
                        )
                        Spacer(Modifier.height(8.dp))
                        QlButton("再看一遍", { state.refreshAudit() }, compact = true)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        // id 是 skipped 表主键，天然唯一；用 ts+pkg 在同毫秒同包时会撞 key。
                        items(state.auditSkipped, key = { it.id }) { item: Skipped ->
                            SkippedRow(item, Modifier.animateItem())
                        }
                    }
                }
            }
            // 样本数天然小于今日跳过数：5 秒节流 + 只留最近 3 天。默认收起，不占正文。
            if (state.auditSkipped.isNotEmpty() || state.filteredToday > state.auditSkipped.size) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (whyOpen) "收起" else "为什么样本更少",
                    style = QL.label.copy(color = QL.InkSoft),
                    modifier = Modifier
                        .qlClickable { whyOpen = !whyOpen }
                        .padding(vertical = 4.dp),
                )
                if (whyOpen) {
                    Spacer(Modifier.height(2.dp))
                    MicroLabel("同一个应用 5 秒内最多记 1 条；样本只留最近 3 天。")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QlButton(
                    label = "重新检查",
                    onClick = { state.refreshAudit(); state.refresh() },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                if (enabled && trouble) {
                    QlButton(
                        label = "重连监听",
                        onClick = { state.reconnectListener() },
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = true,
                    )
                } else if (!enabled) {
                    QlButton(
                        label = "去开启",
                        onClick = { state.openListenerSettings() },
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = true,
                    )
                } else {
                    QlButton(
                        label = "关闭",
                        onClick = { state.dismissAudit() },
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = true,
                    )
                }
            }
        }
    }
}

/** 内部跳过原因 → 用户能读的一行；不再出现「状态/进度类」这种分类词。 */
private fun skipReasonLabel(reason: String): String = when (reason) {
    "状态/进度类" -> "下载进度"
    "系统状态提示" -> "系统状态"
    else -> reason
}

@Composable
private fun AuditKeyValue(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = QL.bodySoft, modifier = Modifier.weight(1f))
        Text(value, style = QL.monoSmall.copy(color = color))
    }
}

@Composable
private fun SkippedRow(item: Skipped, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(Fiscal.clockText(item.ts), style = QL.monoSmall, color = QL.InkFaint, modifier = Modifier.width(42.dp))
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(item.app, style = QL.h3.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.title.isNotBlank()) {
                Text(item.title, style = QL.micro.copy(color = QL.InkFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier
                .background(QL.SealWash)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(
                skipReasonLabel(item.reason),
                style = QL.micro.copy(color = QL.Seal, fontSize = 8.sp, letterSpacing = 0.2.sp),
            )
        }
    }
}

/**
 * 监听修复：后台被清理后「通知使用权还在但连不上」是这台手机最常见的状态。
 * 按顺序给 4 个动作，前两个通常就能救回来。
 */
@Composable
fun ListenerFixSheet(state: AppState) {
    QlSheet(
        title = "监听修复",
        subtitle = "后台被清理后，系统会掐掉监听绑定",
        onClose = { state.dismissListenerFix() },
    ) {
        Column(Modifier.fillMaxWidth()) {
            AuditKeyValue(
                "监听状态",
                when {
                    !state.listenerOn -> "未开启"
                    state.listenerConnected -> "已连接"
                    state.listenerConnecting -> "正在连接"
                    else -> "已断开"
                },
                when {
                    !state.listenerOn -> QL.Seal
                    state.listenerConnected -> QL.Green
                    else -> QL.Amber
                },
            )
            AuditKeyValue(
                "电池白名单",
                if (state.batteryWhitelisted) "已加入" else "未加入",
                if (state.batteryWhitelisted) QL.Green else QL.Amber,
            )
            Spacer(Modifier.height(10.dp))
            HairHLine(color = QL.RuleSoft)
            FixStep(index = "1", title = "立即重连", note = "把系统绑定重新拉起来") {
                QlButton("重连", { state.reconnectListener() }, compact = true)
            }
            FixStep(index = "2", title = "加入电池白名单", note = "省电清理最常掐断监听") {
                QlButton(
                    if (state.batteryWhitelisted) "再检查" else "去设置",
                    { state.requestBatteryWhitelist() },
                    compact = true,
                )
            }
            FixStep(index = "3", title = "允许自启动", note = "开机/清后台后能自己回来") {
                QlButton("去设置", { state.openAutoStartSettings() }, compact = true)
            }
            FixStep(index = "4", title = "重新开一次通知使用权", note = "前 3 步都不行再试") {
                QlButton("去系统设置", { state.openListenerSettings() }, compact = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QlButton(
                    label = "重新检查",
                    onClick = { state.refresh() },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                QlButton(
                    label = "关闭",
                    onClick = { state.dismissListenerFix() },
                    modifier = Modifier.weight(1f),
                    compact = true,
                    primary = true,
                )
            }
            Spacer(Modifier.height(6.dp))
            MicroLabel("修好后回来看状态；还不行就先用「通知核对」确认通知到底有没有到 App。")
        }
    }
}

@Composable
private fun FixStep(index: String, title: String, note: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(index, style = QL.micro.copy(color = QL.Seal, letterSpacing = 1.sp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = QL.h3.copy(fontSize = 13.5.sp))
                Spacer(Modifier.height(1.dp))
                MicroLabel(note)
            }
            Spacer(Modifier.width(8.dp))
            action()
        }
        HairHLine(color = QL.RuleSoft)
    }
}
