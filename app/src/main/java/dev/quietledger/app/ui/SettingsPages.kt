package dev.quietledger.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.quietledger.app.R
import dev.quietledger.app.core.Fiscal
import dev.quietledger.app.data.AppNames
import dev.quietledger.app.service.Notifications

/**
 * 设置二级页（账户 / 通知监听 / 同步 / 计税规则 / 日报 / 外观 / 桌面组件 / 版本与更新 / 关于）。
 *
 * 视觉与全局一致：0 圆角、0 阴影、1dp 线；数字一律等宽；颜色只走 QL 的纸墨章 token。
 * 这里只放「页面 + 页面内小部件」，通用导航组件在 NavComponents.kt，基础组件在 Components.kt。
 */

// ----------------------------------------------------------------- 小部件

/**
 * 分步说明的编号方块（桌面添加 / 磁贴步骤），和票据编号同一套语言。
 * 设置根页的 01–09 装饰性编号已去掉（审计 P2-1）；这里只用于真正的分步操作。
 */
@Composable
internal fun IndexTag(text: String) {
    Box(
        Modifier
            .size(24.dp)
            .border(1.dp, QL.Rule)
            .padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = QL.micro.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp, color = QL.InkFaint),
        )
    }
}

/** 数字一律等宽的「HH:mm」。 */
internal fun settingsClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** 结算时间：从未 / MM-dd HH:mm（北京时间）。 */
internal fun settingsSyncText(ts: Long): String {
    if (ts <= 0) return "从未"
    return Fiscal.md(ts) + " " + Fiscal.clockText(ts)
}

/**
 * 主题缩略图：一张迷你票据（paper 底 + ink 两行短线 + seal 小方章）。
 * 让「纸 / 墨 / 章」三色关系一眼可见，比四块一样的色块能分辨（审计 P2-2）。
 */
@Composable
internal fun MiniReceiptSwatch(
    paper: Color,
    ink: Color,
    seal: Color,
    rule: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(paper)
            .border(1.dp, rule),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val stroke = 1.dp.toPx().coerceAtLeast(1f)
            // 两行墨线 = 税单上的等宽数字
            drawLine(ink, Offset(w * 0.16f, h * 0.32f), Offset(w * 0.84f, h * 0.32f), stroke)
            drawLine(ink, Offset(w * 0.16f, h * 0.54f), Offset(w * 0.58f, h * 0.54f), stroke)
            // 小方章 = 税红
            val s = (h * 0.24f).coerceAtLeast(1f)
            drawRect(seal, topLeft = Offset(w * 0.68f, h * 0.66f), size = Size(s, s))
        }
    }
}

/** 一级说明：短句，说明操作后果；不是技术文档。 */
@Composable
private fun SettingsNote(text: String, color: Color = QL.InkFaint) {
    Text(
        text = text,
        style = QL.bodySoft.copy(color = color),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
    )
}

/** 左标题 + 右方角开关的一行，带细分隔线。 */
@Composable
private fun SettingToggleRow(
    title: String,
    note: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = QL.h3.copy(fontSize = 14.sp))
                if (note != null) {
                    Spacer(Modifier.height(2.dp))
                    MicroLabel(note)
                }
            }
            QlToggle(checked = checked, onChange = onChange)
        }
        HairHLine(color = QL.RuleSoft)
    }
}

/** 权限行：方形状态点 + 状态值；未开启时整行可点，去系统授权。 */
@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    SettingRow(
        title = title,
        value = if (granted) "已开启" else actionLabel,
        valueColor = if (granted) QL.Green else QL.Seal,
        onClick = if (granted) null else onAction,
        leading = { StatusDot(if (granted) QL.Green else QL.Seal) },
    )
}

/** 危险动作按钮：1dp 税红线 + 税红字，明确不属于常规操作。 */
@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(42.dp)
            .border(1.dp, QL.Seal)
            .qlClickable(haptic = HapticFeedbackType.LongPress, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = QL.label.copy(color = QL.Seal, fontSize = 11.sp, letterSpacing = 1.1.sp),
        )
    }
}

// ----------------------------------------------------------------- 01 账户与设备

@Composable
internal fun AccountPage(state: AppState, onBack: () -> Unit) {
    var joinCode by remember { mutableStateOf("") }
    var nickSheet by remember { mutableStateOf(false) }

    SubPage(title = "账户与设备", onBack = onBack, latin = "ACCOUNT & DEVICES", glyph = GlyphKind.USER) {
        GroupLabel("账户编号", "ACCOUNT CODE")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val code = state.accountCode
            if (code == null) {
                Text(
                    if (state.syncEnabled) "首次联网自动开户" else "仅本机记账",
                    style = QL.bodySoft,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    code,
                    style = QL.amountMd.copy(letterSpacing = 2.5.sp),
                    modifier = Modifier.weight(1f),
                )
                QlButton(
                    label = "复制",
                    onClick = { state.copyText(code, "账户编号") },
                    compact = true,
                )
            }
        }
        HairHLine(color = QL.RuleSoft)

        GroupLabel("个人", "PROFILE")
        SettingRow(
            title = "昵称",
            value = state.nickname.ifBlank { "未命名" },
            note = if (state.profileBusy) "保存中…" else "最多 24 个字",
            onClick = { nickSheet = true },
        )
        SettingRow(
            title = "账户内设备",
            value = when {
                state.accountCode != null -> "${state.deviceCount.coerceAtLeast(1)} 台"
                state.syncEnabled -> "未开户"
                else -> "仅本机"
            },
            note = "同一账户的每台手机都计价",
        )
        SettingRow(
            title = "本机编号",
            value = state.deviceId,
            note = "这台手机的名字",
            trailing = {
                QlButton(
                    label = "复制",
                    onClick = { state.copyText(state.deviceId, "设备编号") },
                    compact = true,
                )
            },
        )

        GroupLabel("加入账户", "JOIN")
        if (state.syncEnabled) {
            if (state.accountCode != null) {
                SettingRow(
                    title = if (state.inviteBusy) "正在生成邀请码" else "生成并复制邀请码",
                    note = "10 分钟内可用一次",
                    onClick = state::copyAccountInvite,
                )
            }
            SettingsNote("输入另一台设备生成的一次性邀请码。")
        } else {
            SettingsNote("开启云端同步后可加入账户。")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QlTextInput(
                value = joinCode,
                onValueChange = { joinCode = it },
                placeholder = "输入邀请码",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            QlButton(
                label = if (state.joinBusy) "加入中" else "加入",
                onClick = {
                    state.joinAccount(joinCode)
                    joinCode = ""
                },
                compact = true,
                enabled = state.syncEnabled && !state.joinBusy && joinCode.isNotBlank(),
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (nickSheet) {
        NicknameSheet(state, onClose = { nickSheet = false })
    }
}

@Composable
private fun NicknameSheet(state: AppState, onClose: () -> Unit) {
    var draft by remember(state.nickname) { mutableStateOf(state.nickname) }
    QlSheet(title = "改昵称", onClose = onClose, subtitle = "自己认得出就行") {
        QlTextInput(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "给自己起个昵称",
            uppercase = false,
            maxLength = 24,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QlButton(
                label = if (state.profileBusy) "保存中" else "保存",
                onClick = {
                    state.saveNickname(draft)
                    onClose()
                },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = !state.profileBusy,
            )
            QlButton("取消", onClose, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SheetActionRow(
            label = "清空昵称",
            onClick = {
                state.saveNickname("")
                onClose()
            },
            tone = QL.InkSoft,
            note = "清空后显示为「我」",
        )
    }
}

// ----------------------------------------------------------------- 02 通知监听

/**
 * 顶部状态块：用户反馈「收不到任何消息统计」时，先在这里看是哪一步断了。
 * 颜色只说明状态：税红=没授权，琥珀=授权在但连接断了，绿=正常。
 */
@Composable
private fun ListenerStatusBlock(state: AppState) {
    val tone = when {
        !state.listenerOn -> QL.Seal
        state.listenerConnected -> QL.Green
        state.listenerConnecting -> QL.InkSoft
        else -> QL.Amber
    }
    val text = when {
        !state.listenerOn -> "通知使用权未开启"
        state.listenerConnected -> "监听已连接"
        state.listenerConnecting -> "正在连接监听…"
        else -> "监听已断开"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .border(1.dp, tone)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone, 9)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = QL.h3.copy(fontSize = 14.sp),
            modifier = Modifier.weight(1f),
        )
        when {
            !state.listenerOn -> QlButton(
                label = "去开启",
                onClick = { state.openListenerSettings() },
                compact = true,
            )
            state.listenerTrouble -> QlButton(
                label = "修复",
                onClick = { state.listenerFixSheet() },
                compact = true,
            )
            state.listenerConnecting -> QlButton(
                label = "重连",
                onClick = { state.reconnectListener() },
                compact = true,
            )
            else -> Unit
        }
    }
}

@Composable
internal fun NotifyPage(state: AppState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { state.refreshAudit() }
    SubPage(title = "通知监听", onBack = onBack, latin = "LISTENER / PERMISSIONS / AUDIT", glyph = GlyphKind.BELL) {
        ListenerStatusBlock(state)
        SettingsNote("收不到统计，先看状态，再去「通知核对」查原因。")

        GroupLabel("权限", "PERMISSIONS")
        PermissionStatusRow(
            title = "通知使用权",
            granted = state.listenerOn,
            actionLabel = "去开启",
            onAction = { state.openListenerSettings() },
        )
        PermissionStatusRow(
            title = "通知权限",
            granted = state.notificationsOn,
            actionLabel = "去允许",
            onAction = { state.requestNotificationPermission() },
        )
        PermissionStatusRow(
            title = "免打扰访问",
            granted = state.dndAccess,
            actionLabel = "去授权",
            onAction = { state.openDndSettings() },
        )
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            QlButton(
                label = "通知设置",
                onClick = { state.openAppNotificationSettings() },
                modifier = Modifier.weight(1f),
            )
        }
        SettingsNote("听不到通知，就在系统里关掉再打开一次「打扰税」的通知使用权。")

        GroupLabel("后台常驻", "BACKGROUND")
        SettingRow(
            title = "电池优化白名单",
            value = if (state.batteryWhitelisted) "已加入" else "未加入",
            valueColor = if (state.batteryWhitelisted) QL.Green else QL.Amber,
            note = "清后台、省电时最容易被掐断的就是这里",
            onClick = { state.requestBatteryWhitelist() },
        )
        SettingRow(
            title = "允许自启动",
            value = "去设置",
            note = "开机或清理后台后能自己回来",
            onClick = { state.openAutoStartSettings() },
        )
        SettingRow(
            title = "监听修复",
            note = "连不上时按步骤处理",
            onClick = { state.listenerFixSheet() },
        )
        SettingsNote("后台被系统清理后监听会断开，前两项能明显减少。")

        GroupLabel("状态", "STATUS")
        // 实时监听时间优先；本机账本已有今天事件（备份恢复/导入）时用最后一条兜底。
        val lastAt = if (state.lastEventAt > 0) state.lastEventAt
        else state.todayEvents.maxOfOrNull { it.ts } ?: 0L
        SettingRow(
            title = "最近入账",
            value = if (lastAt > 0) {
                Fiscal.md(lastAt) + " " + Fiscal.clockText(lastAt)
            } else {
                "暂无"
            },
            note = "北京时间最近一条",
        )
        SettingRow(
            title = "跳过",
            value = "${state.filteredToday} 条",
            note = "系统通知、重复与进度条",
        )
        SettingRow(
            title = "通知核对",
            note = "查看被跳过的通知",
            onClick = { state.openAudit() },
        )

        GroupLabel("打扰控制", "SILENCE")
        // 和票据页的「静默」同一个入口：打开时先选 25/45/60 分钟，而不是偷偷挡 1 小时。
        SettingRow(
            title = "静默",
            value = when {
                state.dndActive && state.dndRemainingMin > 0 -> "剩 ${state.dndRemainingMin} 分"
                state.dndActive -> "生效中"
                else -> "未开启"
            },
            valueColor = if (state.dndActive) QL.Seal else QL.InkSoft,
            note = when {
                !state.dndAccess -> "先授予免打扰访问"
                state.dndActive -> "点一下结束；只挡提醒，照常记账"
                state.systemDnd -> "系统免打扰已开，不由打扰税控制"
                else -> "25 / 45 / 60 分钟，到点自动恢复"
            },
            leading = { SettingIcon(GlyphKind.SILENT) },
            onClick = { state.toggleSilence() },
            showChevron = false,
            trailing = {
                QlToggle(checked = state.dndActive, onChange = {
                    if (state.dndActive) state.endFocus() else state.toggleSilence()
                })
            },
        )

        GroupLabel("提醒", "ALERTS")
        SettingToggleRow(
            title = "免税额用完后提醒",
            note = if (state.budgetAlertOn) "当天第一次欠税时提醒一次" else "已关闭：欠税了自己看票据",
            checked = state.budgetAlertOn,
            onChange = { state.setBudgetAlert(it) },
        )

        GroupLabel("忽略的应用", "IGNORED APPS")
        if (state.ignoredPackages.isEmpty()) {
            SettingsNote("在明细里点一条记录，可以不再记录这个应用。")
        } else {
            val context = LocalContext.current
            state.ignoredPackages.sorted().forEach { pkg ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        AppNames.label(context, pkg),
                        style = QL.body,
                        modifier = Modifier.weight(1f),
                    )
                    QlButton("恢复", { state.unignoreApp(pkg) }, compact = true)
                }
                HairHLine(color = QL.RuleSoft)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ----------------------------------------------------------------- 03 同步

@Composable
internal fun SettlePage(state: AppState, onBack: () -> Unit) {
    val localEvents = state.localTodayEvents
    val serverEvents = state.serverTodayEvents
    val hasServerToday = state.serverInvoice?.date == Fiscal.iso(state.today)
    val reconcileGap = hasServerToday && localEvents > serverEvents
    SubPage(title = "同步", onBack = onBack, latin = "SYNC / RECONCILE", glyph = GlyphKind.SYNC) {
        GroupLabel("同步设置", "SYNC")
        SettingToggleRow(
            title = "云端同步",
            note = if (state.syncEnabled) "跨设备合并，已同步的账单可分享" else "仅本机记账，账本和票据完整可用",
            checked = state.syncEnabled,
            onChange = { state.changeSyncEnabled(it) },
        )
        SettingToggleRow(
            title = "上送通知原文",
            note = "默认关。关了只上送应用、时间、条数，原文只留在本机",
            checked = state.uploadTitles,
            onChange = { state.changeUploadTitles(it) },
        )

        GroupLabel("对账", "RECONCILE")
        SettingRow(
            title = "本机今日",
            value = "$localEvents 条",
            note = "本机账本记到的",
        )
        if (state.syncEnabled) {
            SettingRow(
                title = "同步",
                value = if (hasServerToday) "$serverEvents 条" else "未同步",
                valueColor = if (reconcileGap) QL.Amber else QL.InkSoft,
                note = if (hasServerToday) "含同账户设备" else "联网后自动同步",
            )
            if (reconcileGap) {
                MicroLabel(
                    text = "本机多 ${localEvents - serverEvents} 条，同步后补齐",
                    color = QL.Amber,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
        } else {
            // P0-5 残留：关闭同步后不再在三处重复「未开启同步」，一行说清账本与上次同步。
            SettingRow(
                title = "同步",
                value = "仅本机记账",
                note = buildString {
                    if (state.pending > 0) append("${state.pending} 条未上送，")
                    append("上次同步 ")
                    append(settingsSyncText(state.lastSyncAt))
                },
            )
        }
        SettingRow(
            title = "自动合并",
            value = "${state.autoMergedCount} 条",
            note = "重复与系统通知",
        )

        GroupLabel("税则", "TAX RULES")
        SettingRow(
            title = "计税规则",
            value = "${state.tariff.unitSeconds} 秒/条 / 免税 ${state.tariff.exemptionPerDay} 秒",
            valueColor = if (state.taxPending) QL.Amber else QL.InkSoft,
            note = if (state.taxPending) "等联网提交" else "单价与免税额可调",
            onClick = { state.openTaxSettings() },
        )

        if (state.syncEnabled) {
            GroupLabel("状态", "STATUS")
            SettingRow(
                title = "同步状态",
                value = when {
                    state.syncing -> "同步中…"
                    state.lastError != null -> "离线"
                    state.pending > 0 -> "待同步 ${state.pending}"
                    else -> "已同步"
                },
                valueColor = when {
                    state.lastError != null -> QL.Amber
                    state.pending > 0 -> QL.Amber
                    else -> QL.Green
                },
            )
            SettingRow(
                title = "上次同步",
                value = settingsSyncText(state.lastSyncAt),
            )
        }
        if (state.syncEnabled) {
            state.lastError?.let {
                Spacer(Modifier.height(8.dp))
                NoticeBar(
                    text = it,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    actionLabel = "重试",
                    onAction = { state.sync(manual = true) },
                    tone = QL.Amber,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        ButtonRow {
            // 同步关闭时别把一个黑色的「立即同步」主按钮摆在脸上：改成明确的「开启云端同步」。
            val syncOff = !state.syncEnabled
            QlButton(
                label = when {
                    syncOff -> "开启云端同步"
                    state.syncing -> "同步中…"
                    else -> "立即同步"
                },
                onClick = { if (syncOff) state.changeSyncEnabled(true) else state.sync() },
                modifier = Modifier.weight(1f),
                primary = !syncOff,
                enabled = !state.syncing,
            )
        }
        SettingsNote("断网时先记在本机，联网后自动补送。")
    }
}

// ----------------------------------------------------------------- 04 计税规则

/**
 * 计税规则：单价与免税额按账户可调；改完立刻按本机税则重新估算，联网后自动结算。
 * 固定部分（夜间、连发、勿扰、群发、封顶、应用权重）只读展示，避免出现第二套计价口径。
 */
@Composable
internal fun TaxPage(state: AppState, onBack: () -> Unit) {
    val t = state.tariff
    val unit = state.taxUnit
    val chat = t.appFactor("com.tencent.mm")
    val plainBurst = exampleDuty(unit, t.burstMultiplier)
    val dndDuty = exampleDuty(unit, t.dndMultiplier)
    val noticeDuty = exampleDuty(unit, t.noticeMultiplier)

    SubPage(title = "计税规则", onBack = onBack, latin = "TAX RULES / THIS ACCOUNT", glyph = GlyphKind.PERCENT) {
        when {
            state.taxPending -> NoticeBar(
                text = "已按新税则本机估算，联网后自动提交。",
                tone = QL.Amber,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            state.lastError != null -> NoticeBar(
                text = "离线：先按本机税则估算，联网后自动结算。",
                tone = QL.Amber,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        GroupLabel("单价", "UNIT")
        StepperRow(
            title = "每条打扰记多少秒",
            note = "2–30 秒，默认 8 秒",
            value = "$unit 秒/条",
            minusEnabled = unit > 2,
            plusEnabled = unit < 30,
            onMinus = { state.setUnitSeconds(unit - 1) },
            onPlus = { state.setUnitSeconds(unit + 1) },
        )

        GroupLabel("免税额", "EXEMPTION")
        StepperRow(
            title = "每天先免掉多少秒",
            note = "0–1800 秒，每档 60 秒，默认 240 秒",
            value = "${state.taxExemption} 秒/天",
            minusEnabled = state.taxExemption > 0,
            plusEnabled = state.taxExemption < 1800,
            onMinus = { state.setExemptionSeconds(state.taxExemption - 60) },
            onPlus = { state.setExemptionSeconds(state.taxExemption + 60) },
        )

        GroupLabel("固定规则", "FIXED")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            KeyValueRow(
                "夜间 / ${two(t.nightStartHour)}:00–${two(t.nightEndHour)}:00",
                "×${factorText(t.nightMultiplier)}",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "连发 / 同应用 ${t.burstWindowSec} 秒内第二条起",
                "×${factorText(t.burstMultiplier)}",
                note = "打折，不是加罚",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "免打扰 / 系统免打扰或静默中",
                "×${factorText(t.dndMultiplier)}",
                note = "打折，不是加罚",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "群发 / @所有人 / @全体成员",
                "×${factorText(t.noticeMultiplier)}",
                note = "提醒所有人，加倍",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "单条封顶",
                "≤ ${factorText(t.capFactor)} 倍单价",
                note = "再响也不超过",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow("聊天类 / 微信 / QQ / TIM", "×${factorText(chat)}")
            HairHLine(color = QL.RuleSoft)
            KeyValueRow(
                "办公类 / 企业微信 / 钉钉 / 飞书",
                "×${factorText(t.appFactor("com.tencent.wework"))}",
            )
            HairHLine(color = QL.RuleSoft)
            KeyValueRow("其它应用", "×${factorText(t.appFactor("dev.quietledger.other"))}")
            HairHLine(color = QL.RuleSoft)
            KeyValueRow("会话权重优先", "见会话分类")
        }

        GroupLabel("计价示例", "EXAMPLE")
        // 只给结果，不给公式：用户要判断的是「这一条值几秒」，不是复算实现（AGENTS §4 / 审计 P0-3）。
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                "同应用第 2 条起，记 $plainBurst 秒",
                style = QL.mono.copy(fontSize = 11.5.sp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "免打扰或静默中，记 $dndDuty 秒",
                style = QL.mono.copy(fontSize = 11.5.sp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "群发一条，记 $noticeDuty 秒",
                style = QL.mono.copy(fontSize = 11.5.sp),
            )
        }

        Spacer(Modifier.height(12.dp))
        ButtonRow {
            QlButton(
                label = "恢复默认",
                onClick = { state.resetTaxRules() },
                modifier = Modifier.weight(1f),
            )
            QlButton(
                label = if (state.syncing) "结算中…" else "应用并结算",
                onClick = { state.applyTaxRules() },
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = !state.syncing,
                haptic = HapticFeedbackType.TextHandleMove,
            )
        }
        SettingsNote("同账户所有设备共用一套税则；免税额每天按北京时间重置，欠税不跨天累计。")
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun StepperRow(
    title: String,
    note: String,
    value: String,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = QL.h3.copy(fontSize = 14.sp))
            Spacer(Modifier.height(2.dp))
            MicroLabel(note)
        }
        QlButton(label = "-", onClick = onMinus, compact = true, enabled = minusEnabled)
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            Text(value, style = QL.monoStrong.copy(fontSize = 14.sp), maxLines = 1)
        }
        QlButton(label = "+", onClick = onPlus, compact = true, enabled = plusEnabled)
    }
    HairHLine(color = QL.RuleSoft)
}

/** 系数展示：0.4 显示成「0.4」，1.0 显示成「1」。 */
private fun factorText(v: Double): String = if (v == v.toInt().toDouble()) "${v.toInt()}" else "$v"

private fun two(n: Int): String = "%02d".format(n)

// ----------------------------------------------------------------- 05 日报

/**
 * 日报：先看今晚会收到什么（同一份 buildDigest 文案），再决定开不开、几点发。
 *
 * v1.8.0：预览与通知共用 `Notifications.buildDigest`，数据取 `state.invoice`（与票据页同源），
 * 时间快选只给整点——税单按天，不需要 08:30 这种半档。
 */
@Composable
internal fun DigestPage(state: AppState, onBack: () -> Unit) {
    // 老版本可以用 +30 分钟设出 08:30：税单按天，进页面就归到整点。
    LaunchedEffect(Unit) {
        if (state.digestMinute != 0) state.setDigestTime(state.digestHour)
    }
    val inv = state.invoice
    val source = if (state.settledByServer) "已结算" else "本机"
    val (digestTitle, digestBody) = Notifications.buildDigest(inv, source)
    val digestLines = Notifications.digestLines(inv)
    var sent by remember { mutableStateOf(false) }
    val clock = "%02d:00".format(state.digestHour)
    val headline = when {
        !state.digestOn -> "日报已关闭"
        state.digestHour >= 18 -> "今晚 $clock 会收到"
        else -> "每天 $clock 会收到"
    }

    SubPage(title = "日报", onBack = onBack, latin = "DAILY DIGEST", glyph = GlyphKind.SUN) {
        if (!state.notificationsOn) {
            NoticeBar(
                text = "通知权限没开，日报不会送达。",
                actionLabel = "去允许",
                onAction = { state.requestNotificationPermission() },
                tone = QL.Amber,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        GroupLabel("今晚的样子", "PREVIEW")
        ReceiptPaper(modifier = Modifier.padding(horizontal = 20.dp)) {
            MicroLabel(headline, color = if (state.digestOn) QL.Seal else QL.InkFaint)
            Spacer(Modifier.height(7.dp))
            Text(digestTitle, style = QL.h3.copy(fontSize = 13.5.sp))
            Spacer(Modifier.height(3.dp))
            Text(digestBody, style = QL.bodySoft)
            Spacer(Modifier.height(5.dp))
            digestLines.forEach { line ->
                Text(line, style = QL.monoSmall.copy(color = QL.InkSoft), maxLines = 1)
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(6.dp))
            QlButton(
                label = if (sent) "已发送" else "现在发一条",
                onClick = {
                    state.sendTestDigest()
                    // 通知权限没开时 sendTestDigest 会提示失败，这里不谎报「已发送」。
                    sent = state.notificationsOn
                },
                modifier = Modifier.fillMaxWidth(),
                primary = !sent,
            )
        }
        Spacer(Modifier.height(6.dp))
        SettingsNote("发到这台手机的通知栏；不用等到 $clock。")

        GroupLabel("日报", "DIGEST")
        SettingToggleRow(
            title = "每天发一份税单",
            note = if (state.digestOn) "已开启" else "已关闭",
            checked = state.digestOn,
            onChange = { state.setDigest(it) },
        )

        GroupLabel("发送时间", "SEND AT")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(clock, style = QL.amountMd)
            Spacer(Modifier.width(8.dp))
            Text("北京时间", style = QL.bodySoft, modifier = Modifier.padding(bottom = 3.dp))
        }
        // 四个常用整点快选：从 21:00 改到 08:00 两下就能到（审计 P1-9）。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(8, 12, 21, 23).forEach { hour ->
                val label = "%02d:00".format(hour)
                DigestTimeCell(
                    label = label,
                    selected = state.digestHour == hour && state.digestMinute == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { state.setDigestTime(hour) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ButtonRow {
            QlButton("-1 小时", { state.shiftDigestTime(-60) }, Modifier.weight(1f), compact = true)
            QlButton("+1 小时", { state.shiftDigestTime(60) }, Modifier.weight(1f), compact = true)
        }
        SettingsNote("系统省电时可能晚几分钟。")
        Spacer(Modifier.height(10.dp))
    }
}

/** 日报时间快选格：方角、选中反白（和明细筛选条同一套语言）。 */
@Composable
private fun DigestTimeCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(32.dp)
            .background(if (selected) QL.Ink else Color.Transparent)
            .border(1.dp, if (selected) QL.Ink else QL.Rule)
            .qlClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = QL.monoSmall.copy(color = if (selected) QL.Paper else QL.InkSoft),
        )
    }
}

// ----------------------------------------------------------------- 06 外观

@Composable
internal fun AppearancePage(state: AppState, onBack: () -> Unit) {
    SubPage(title = "外观", onBack = onBack, latin = "APPEARANCE / THEMES", glyph = GlyphKind.CONTRAST) {
        GroupLabel("配色", "PALETTE")
        QL.palettes.forEach { p ->
            val selected = p.id == state.themeId
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .qlClickable(onClick = { state.setTheme(p.id) })
                        // 选中态 2dp 税红线：缩略图本身也有纸/墨/章三色，不再只靠一条细边（审计 P2-2）。
                        .then(if (selected) Modifier.border(2.dp, QL.Seal) else Modifier)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniReceiptSwatch(
                        paper = p.paper,
                        ink = p.ink,
                        seal = p.seal,
                        rule = p.rule,
                        modifier = Modifier.size(width = 46.dp, height = 30.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = QL.h3.copy(
                                fontSize = 14.sp,
                                color = if (selected) QL.Ink else QL.InkSoft,
                            ),
                        )
                        MicroLabel(p.id.uppercase())
                    }
                    if (selected) {
                        MicroLabel("使用中", color = QL.Seal)
                    }
                }
                HairHLine(color = QL.RuleSoft)
            }
        }
        SettingsNote("点一下立即换，状态栏一起换。")
    }
}

// ----------------------------------------------------------------- 07 桌面组件

/**
 * 桌面组件页。部分系统桌面（澎湃 / MIUI / ColorOS / OriginOS / 鸿蒙 / 类原生）对
 * requestPinAppWidget 经常既不弹窗也不报错，所以「钉到桌面」下面是常驻的结果说明 +
 * 通用的三步手动添加步骤，不再靠一个失败 toast 打发用户。
 */
@Composable
internal fun DesktopPage(state: AppState, onBack: () -> Unit) {
    SubPage(title = "桌面组件", onBack = onBack, latin = "WIDGET & TILE", glyph = GlyphKind.GRID) {
        GroupLabel("税单组件", "WIDGET")
        SettingsNote("把今天的税单放到桌面，不打开 App 也能看到欠税。")
        Spacer(Modifier.height(2.dp))
        ButtonRow {
            QlButton("钉到桌面", { state.requestPinWidget() }, Modifier.weight(1f), primary = true)
        }
        if (state.showPinGuide) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                NoticeBar(text = state.pinGuideText, tone = QL.Amber)
                Text(
                    "知道了",
                    style = QL.label.copy(color = QL.InkSoft),
                    modifier = Modifier
                        .qlClickable(onClick = { state.dismissPinGuide() })
                        .padding(vertical = 4.dp),
                )
            }
        }

        GroupLabel("手动添加", "MANUAL")
        SettingsNote("部分系统桌面不弹确认框，按下面三步手动添加。")
        DesktopStep("01", "长按桌面空白处")
        DesktopStep("02", "选「小部件 / 添加小部件 / 桌面工具」")
        DesktopStep("03", "找到「打扰税 · 今日税单」，拖到桌面")
        Spacer(Modifier.height(10.dp))
        GuideFigure(
            id = R.drawable.guide_widget_preview,
            caption = "桌面上的税单",
        )
        Spacer(Modifier.height(8.dp))
        GuideFigure(
            id = R.drawable.guide_widget_picker,
            caption = "小部件选择器里搜「打扰税」",
            maxWidthFraction = 0.55f,
        )
        Spacer(Modifier.height(8.dp))
        GuideFigure(
            id = R.drawable.guide_widget_search,
            caption = "没有搜索框就点「安卓小部件」",
            maxWidthFraction = 0.45f,
        )
        Spacer(Modifier.height(6.dp))

        GroupLabel("快捷磁贴", "TILE")
        SettingsNote("下拉快捷设置，把「静默 1 小时」加进面板；点它只挡提醒，照常记账。")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(96.dp)) {
                Image(
                    painter = painterResource(R.drawable.guide_tile_silence),
                    contentDescription = "静默磁贴",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(96.dp)
                        .height(96.dp)
                        .border(1.dp, QL.Rule),
                )
                Spacer(Modifier.height(4.dp))
                MicroLabel("静默磁贴")
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                TileStep("01", "下拉打开快捷设置")
                TileStep("02", "点编辑，或长按磁贴区")
                TileStep("03", "把「静默 1 小时」拖到可见位置")
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 桌面组件配图（v1.9.0 重做）：按图片自己的宽高比铺满可用宽度，不再裁成统一的 4:3。
 *
 * 旧的 4:3 + Crop 会把「小部件选择器」这类竖图两边切掉、把横图上下切掉，
 * 用户看不到完整的系统界面。现在：
 *  - 横图（桌面税单）整宽铺开，高度随比例；
 *  - 竖图（小部件选择器）用 [maxWidthFraction] 收窄并居中，既完整又不至于占满一屏。
 * 只在图片大于可用宽度时等比缩小，因此永远不裁切。
 */
@Composable
private fun GuideFigure(
    @DrawableRes id: Int,
    caption: String,
    maxWidthFraction: Float = 1f,
) {
    val painter = painterResource(id)
    val width = painter.intrinsicSize.width
    val height = painter.intrinsicSize.height
    // 极少数图片 intrinsicSize 可能为 0/NaN：退回 4:3，至少不会因为 aspectRatio 崩掉。
    val ratio = if (width > 0f && height > 0f) width / height else 4f / 3f
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painter,
            contentDescription = caption,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth(maxWidthFraction.coerceIn(0.3f, 1f))
                .aspectRatio(ratio)
                .border(1.dp, QL.Rule),
        )
        Spacer(Modifier.height(4.dp))
        MicroLabel(caption)
    }
}

/** 手动步骤的一行：编号方块 + 一句动作。 */
@Composable
private fun DesktopStep(index: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndexTag(index)
        Spacer(Modifier.width(10.dp))
        Text(text, style = QL.body, modifier = Modifier.weight(1f))
    }
}

/** 磁贴步骤：排在磁贴图右侧，不再重复页面的左右 20dp 边距。 */
@Composable
private fun TileStep(index: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndexTag(index)
        Spacer(Modifier.width(8.dp))
        Text(text, style = QL.bodySoft, modifier = Modifier.weight(1f))
    }
}

// ----------------------------------------------------------------- 08 版本与更新

@Composable
internal fun UpdatePage(state: AppState, onBack: () -> Unit) {
    val phase = state.updatePhase
    SubPage(title = "版本与更新", onBack = onBack, latin = "VERSION & UPDATE", glyph = GlyphKind.DOWNLOAD) {
        GroupLabel("版本", "VERSION")
        SettingRow(
            title = "当前版本",
            value = "v${state.currentVersionName}",
        )
        SettingRow(
            title = "最新版本",
            value = state.latestUpdate?.let { "v${it.versionName}" } ?: "未检查",
            valueColor = if (phase == UpdatePhase.AVAILABLE) QL.Seal else QL.InkSoft,
            note = if (phase == UpdatePhase.AVAILABLE) "有新版本" else null,
        )
        state.updateMessage?.let {
            Text(
                text = it,
                style = QL.monoSmall.copy(
                    color = if (phase == UpdatePhase.ERROR) QL.Seal else QL.InkSoft,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        state.latestUpdate?.takeIf { it.versionCode > state.currentVersionCode }?.notes
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                GroupLabel("更新内容", "RELEASE NOTES")
                Text(
                    notes,
                    style = QL.bodySoft,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        if (phase == UpdatePhase.DOWNLOADING) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                MeterBar(state.updateProgress / 100f)
                Spacer(Modifier.height(4.dp))
                MicroLabel("下载中 ${state.updateProgress}%")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QlButton(
                label = if (phase == UpdatePhase.CHECKING) "检查中…" else "检查更新",
                onClick = { state.checkUpdate(manual = true) },
                modifier = Modifier.weight(1f),
                enabled = phase != UpdatePhase.CHECKING && phase != UpdatePhase.DOWNLOADING,
            )
            when (phase) {
                UpdatePhase.AVAILABLE -> QlButton(
                    label = "下载更新",
                    onClick = { state.downloadUpdate() },
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
                UpdatePhase.DOWNLOADED -> QlButton(
                    label = "安装更新",
                    onClick = { state.installUpdate() },
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
                UpdatePhase.DOWNLOADING -> QlButton(
                    label = "下载中",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    enabled = false,
                )
                UpdatePhase.ERROR -> if (state.latestUpdate != null) QlButton(
                    label = "重试下载",
                    onClick = { state.downloadUpdate() },
                    modifier = Modifier.weight(1f),
                )
                else -> Unit
            }
        }

        GroupLabel("自动更新", "AUTO UPDATE")
        SettingToggleRow(
            title = "WiFi 下自动下载",
            note = "下载完成后，点「安装更新」继续",
            checked = state.autoUpdateWifi,
            onChange = { state.applyAutoUpdatePref(it) },
        )
    }
}

// ----------------------------------------------------------------- 09 关于

@Composable
internal fun AboutPage(state: AppState, onBack: () -> Unit) {
    SubPage(title = "关于", onBack = onBack, latin = "ABOUT", glyph = GlyphKind.INFO) {
        GroupLabel("应用", "APP")
        SettingRow(
            title = "使用说明",
            note = "再看一遍首次引导",
            onClick = { state.openGuide() },
        )
        SettingRow(
            title = "官网",
            value = "ql.130923.xyz",
            note = "打扰税是什么",
            onClick = { state.openWebsite() },
        )

        GroupLabel("本机", "THIS DEVICE")
        SettingRow(
            title = "设备编号",
            value = state.deviceId,
            note = "这台手机的名字",
            trailing = {
                QlButton(
                    label = "复制",
                    onClick = { state.copyText(state.deviceId, "设备编号") },
                    compact = true,
                )
            },
        )
        SettingRow(
            title = "规则版本",
            value = state.tariff.version,
            note = "${state.tariff.unitSeconds} 秒/条，免税额 ${state.tariff.exemptionPerDay} 秒/天",
        )

        GroupLabel("危险操作", "DANGER")
        SettingsNote("只删本机账本与凭证，已同步的账单不受影响。")
        Spacer(Modifier.height(6.dp))
        DangerButton("清空本机数据") { state.requestResetLocalData() }

        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            MicroLabel("通知摘要只留在本机")
            Spacer(Modifier.height(4.dp))
            MicroLabel("打扰税 v${state.currentVersionName}  北京时间结算")
        }
        Spacer(Modifier.height(10.dp))
    }
}
