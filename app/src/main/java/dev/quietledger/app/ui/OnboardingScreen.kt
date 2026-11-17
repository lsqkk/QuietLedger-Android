package dev.quietledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次引导：标题 → 通知使用权（主行动）→ 先看账本 → 一张税单样张 → 昵称 / 外观 → 可选权限。
 *
 * 约束（AGENTS.md §4 / docs/PLAN-v1.8.0.md §3.3）：
 * - 全部短句，不写解释性长文；不出现服务端字样和技术词；不强制开户。
 * - 隐私口径只写事实：原文只留在这台手机，默认只上送应用、时间、条数。
 * - 首屏 360×640 不滚动就能看到「开启通知使用权」和「跳过」两个出口；
 *   顶部「跳过」= 先看账本，昵称与外观放在主行动之后，不挡路。
 * - 页面整体可滚动，避让由 AppRoot 的 safeDrawing insets 统一处理。
 */
@Composable
fun OnboardingScreen(state: AppState) {
    val connected = state.listenerOn && state.listenerConnected
    val connecting = state.listenerOn && !connected && !state.listenerTrouble
    // 首屏只给两张纸卡做错峰进入，其余元素静止。
    val entrance = rememberEntranceWindow()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        // -- 头部：标题 + 跳过（首屏固定可见） ------------------------------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                MicroLabel("静默税局 / QUIETLEDGER")
                Spacer(Modifier.height(6.dp))
                Text("你的注意力，值得记一笔账", style = QL.h1)
            }
            // 首屏就能跳过：引导不是关卡，不逼用户滑到底。
            QlButton("跳过", { state.dismissGuide() }, compact = true)
        }
        Spacer(Modifier.height(6.dp))
        Text("每条打扰，记一笔税。每天一张税单。", style = QL.body)
        Spacer(Modifier.height(16.dp))

        // -- 开始记账：通知使用权（必需，首屏主行动） ------------------------
        ReceiptPaper(modifier = Modifier.staggerIn(0, entrance)) {
            MicroLabel("开始记账", color = QL.Seal)
            Spacer(Modifier.height(5.dp))
            Text("通知使用权", style = QL.h2)
            Spacer(Modifier.height(4.dp))
            Text("原文只留在这台手机。默认只上送应用、时间、条数。", style = QL.bodySoft)
            Spacer(Modifier.height(2.dp))
            Text("本机先记，联网后自动结算；不开同步也能完整用。", style = QL.bodySoft)
            Spacer(Modifier.height(10.dp))
            if (state.listenerOn) {
                MicroLabel(
                    when {
                        connected -> "监听已连接"
                        connecting -> "已授权，正在连接"
                        else -> "已授权，监听已断开"
                    },
                    color = if (connected) QL.Green else QL.Amber,
                )
                Spacer(Modifier.height(8.dp))
            }
            QlButton(
                label = when {
                    connected -> "查看今日税单"
                    connecting -> "等待连接…"
                    state.listenerOn -> "重新连接监听"
                    else -> "开启通知使用权"
                },
                onClick = {
                    when {
                        connected -> state.dismissGuide()
                        state.listenerOn -> state.reconnectListener()
                        else -> state.openListenerSettings()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connecting,
                primary = true,
            )
            Spacer(Modifier.height(6.dp))
            Text("北京时间结算，免税额每天重置", style = QL.bodySoft)
        }
        Spacer(Modifier.height(10.dp))
        // 不强制：任何时候都能先看账本；和顶部「跳过」等价。
        QlButton("先看看账本", { state.dismissGuide() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))

        // -- 一张税单：只给样张，不写定义句 --------------------------------
        ReceiptPaper(modifier = Modifier.staggerIn(1, entrance)) {
            MicroLabel("一张税单", color = QL.Seal)
            Spacer(Modifier.height(5.dp))
            Text("每天的打扰，出一张税单", style = QL.h2)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniAmount("应缴", "168")
                MiniAmount("免税额", "240")
                MiniAmount("结余", "72")
            }
        }
        Spacer(Modifier.height(20.dp))

        // -- 03 昵称（不是步骤，去掉编号，避免「04 步」暗示必须走完）-----------
        MicroLabel("怎么称呼你")
        Spacer(Modifier.height(8.dp))
        QlTextInput(
            value = state.nickname,
            onValueChange = { state.saveNicknameQuiet(it) },
            placeholder = "昵称（可选）",
            uppercase = false,
            maxLength = 24,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(5.dp))
        MicroLabel("会出现在分享的收据上")
        Spacer(Modifier.height(20.dp))

        // -- 04 外观（同上：设置项，不是步骤）---------------------------------
        MicroLabel("选个外观")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QL.palettes.forEach { p ->
                val selected = p.id == state.themeId
                Column(
                    Modifier
                        .weight(1f)
                        .border(1.dp, if (selected) QL.Seal else QL.Rule)
                        .qlClickable(onClick = { state.setTheme(p.id) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniReceiptSwatch(
                        paper = p.paper,
                        ink = p.ink,
                        seal = p.seal,
                        rule = p.rule,
                        modifier = Modifier.size(width = 34.dp, height = 22.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        p.name,
                        style = QL.micro.copy(
                            fontSize = 9.5.sp,
                            letterSpacing = 0.sp,
                            color = if (selected) QL.Ink else QL.InkFaint,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // -- 可选权限 ------------------------------------------------------
        MicroLabel("可选")
        Spacer(Modifier.height(2.dp))
        GuidePermissionRow(
            title = "通知权限",
            subtitle = "接收每日税单",
            granted = state.notificationsOn,
            action = if (state.notificationsOn) null else "允许",
            onAction = { state.requestNotificationPermission() },
        )
        GuidePermissionRow(
            title = "免打扰访问",
            subtitle = "使用静默和手动添加的磁贴",
            granted = state.dndAccess,
            action = if (state.dndAccess) null else "去授权",
            onAction = { state.openDndSettings() },
        )
        GuidePermissionRow(
            title = "电池白名单",
            subtitle = "减少后台监听中断",
            granted = state.batteryWhitelisted,
            action = if (state.batteryWhitelisted) null else "去设置",
            onAction = { state.requestBatteryWhitelist() },
        )
        Spacer(Modifier.height(18.dp))
        MicroLabel("都不影响本机记账，随时可在设置里改")
        Spacer(Modifier.height(10.dp))
    }
}

/** 迷你税单里的一个数字：小标签 + 等宽金额。 */
@Composable
private fun MiniAmount(label: String, value: String) {
    Column {
        MicroLabel(label)
        Spacer(Modifier.height(3.dp))
        Text("$value 秒", style = QL.monoStrong.copy(fontSize = 17.sp))
    }
}

@Composable
private fun GuidePermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (granted) QL.Green else QL.Rule))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = QL.h3)
            Spacer(Modifier.height(1.dp))
            MicroLabel(subtitle)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            QlButton(action, onAction, compact = true)
        } else {
            MicroLabel("已开启", color = QL.Green)
        }
    }
    HairHLine(color = QL.RuleSoft)
}
