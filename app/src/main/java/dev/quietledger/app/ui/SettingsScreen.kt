package dev.quietledger.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 设置（设置 IA v2）：根页只放分类入口，具体操作进各自的二级页。
 *
 * 根页每行 = 标题 + 一行小注 + 右侧实时值 + 细线箭头（v1.8.0 去掉装饰性 01–09 编号，审计 P2-1）；
 * 二级页返回靠左上角「← 返回」，系统返回键同样回根页。
 * 文案遵守 AGENTS.md §4：短、口语、有动作；无 emoji；术语统一 应缴 / 免税额 / 欠税 / 结余。
 */
enum class SettingsRoute {
    ROOT,
    ACCOUNT,
    NOTIFY,
    SETTLE,
    TAX,
    CONVERSATIONS,
    DIGEST,
    APPEARANCE,
    DESKTOP,
    UPDATE,
    ABOUT,
}

/**
 * 设置页「列表 → 详情」的手写 Container Transform（docs/MOTION.md 阶段 2 的降级方案）。
 *
 * 为什么不直接用 `SharedTransitionLayout` + `sharedBounds`：
 *  - 根页每一行是「整屏宽 × 48dp」，二级页是「整屏宽 × 整页高」，宽高比差 30 倍以上，
 *    Compose 的 ScaleToBounds 会把整页文字纵向压扁成一条，实测不可用；
 *  - 行本身已经铺满整宽，横向也没有放大空间。
 * 所以只缩放「纸卡背景」（纯色，缩放不会压坏字形），页面内容用 alpha 跟随：
 * 视觉上就是点中的那一行像一张纸一样展开成整页，返回时再缩回那一行。
 * 全程只改 `graphicsLayer`（scaleY / translationY / alpha），不触发 layout。
 */
@Composable
fun SettingsScreen(state: AppState) {
    // 进程/旋转重建后回到原来的二级页。
    var route by rememberSaveable { mutableStateOf(SettingsRoute.ROOT) }
    // 每一行在窗口里的位置：点哪一行，纸卡就从哪一行长出来。
    val rowBounds = remember { mutableStateMapOf<SettingsRoute, Rect>() }
    var origin by remember { mutableStateOf<Rect?>(null) }
    var pageTop by remember { mutableFloatStateOf(0f) }
    // 0 = 纸卡还贴着那一行，1 = 铺满整页。
    val card = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val backToRoot: () -> Unit = {
        if (!busy && route != SettingsRoute.ROOT) {
            busy = true
            scope.launch {
                card.animateTo(0f, Motion.emphasized(Motion.Container))
                route = SettingsRoute.ROOT
                busy = false
            }
        }
    }

    fun open(r: SettingsRoute) {
        if (busy) return
        busy = true
        origin = rowBounds[r]
        route = r
        scope.launch {
            if (origin == null) {
                // 旋转恢复、或从别的页面请求跳转时没有行坐标：直接铺满，不做假动画。
                card.snapTo(1f)
            } else {
                card.snapTo(0f)
                card.animateTo(1f, Motion.emphasized(Motion.Container))
            }
            busy = false
        }
    }

    // 系统返回手势：手势过程中纸卡跟着手指缩回那一行，松手取消则 spring 回弹。
    // PredictiveBackHandler 在 API < 33 会退化成普通返回，所以不用再叠一个 BackHandler。
    PredictiveBackHandler(enabled = route != SettingsRoute.ROOT) { flow ->
        var completed = false
        try {
            flow.collect { event ->
                card.snapTo((1f - event.progress).coerceIn(0f, 1f))
            }
            completed = true
            busy = true
            card.animateTo(0f, Motion.emphasized(Motion.Container))
            route = SettingsRoute.ROOT
            busy = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!completed) {
                scope.launch { card.animateTo(1f, Motion.springy()) }
            }
        }
    }

    // 旋转/进程重建后直接落在二级页：内容直接铺满。
    LaunchedEffect(Unit) {
        if (route != SettingsRoute.ROOT) card.snapTo(1f)
    }
    // 票据页「去更新」会切到设置并置位 scrollToUpdate：直接进二级页。
    // key 必须带上值：Pager 会预组合相邻 Tab 页，LaunchedEffect(Unit) 可能在本页可见前就把标记消费掉。
    LaunchedEffect(state.scrollToUpdate) {
        if (state.scrollToUpdate) {
            state.scrollToUpdate = false
            open(SettingsRoute.UPDATE)
        }
    }
    // 票据「调整我的税则」/ 结算页「计税规则」都会置位：消费后直接进计税规则页。
    LaunchedEffect(state.settingsTaxRequested) {
        if (state.settingsTaxRequested) {
            state.consumeTaxSettingsRequest()
            open(SettingsRoute.TAX)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { pageTop = it.positionInWindow().y },
    ) {
        // 根页常驻：二级页缩回去时它已经在下面，不会「啪」地重新出现，滚动位置也天然保留。
        SettingsRootPage(
            state = state,
            onOpen = ::open,
            rowModifier = { r ->
                Modifier.onGloballyPositioned { rowBounds[r] = it.boundsInWindow() }
            },
        )

        if (route != SettingsRoute.ROOT) {
            // 覆盖层吃掉点击，避免操作到下面的根页；内容自身的按钮优先命中。
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                // 纸卡：只缩放纯色背景，文字不参与缩放。
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val row = origin
                            if (row != null) {
                                val p = card.value
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                scaleY = lerp(
                                    (row.height / size.height).coerceIn(0.001f, 1f),
                                    1f,
                                    p,
                                )
                                translationY = lerp(row.top - pageTop, 0f, p)
                            }
                        }
                        .background(QL.Paper),
                )
                // 内容：跟随纸卡淡入，并被纸卡边界裁切，避免页面内容漏到还在展开的纸卡外面。
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = card.value.coerceIn(0f, 1f) }
                        .settingsCardMask(origin, pageTop) { card.value },
                ) {
                    when (route) {
                        SettingsRoute.ACCOUNT -> AccountPage(state, backToRoot)
                        SettingsRoute.NOTIFY -> NotifyPage(state, backToRoot)
                        SettingsRoute.SETTLE -> SettlePage(state, backToRoot)
                        SettingsRoute.TAX -> TaxPage(state, backToRoot)
                        SettingsRoute.CONVERSATIONS -> ConversationSettingsPage(state, backToRoot)
                        SettingsRoute.DIGEST -> DigestPage(state, backToRoot)
                        SettingsRoute.APPEARANCE -> AppearancePage(state, backToRoot)
                        SettingsRoute.DESKTOP -> DesktopPage(state, backToRoot)
                        SettingsRoute.UPDATE -> UpdatePage(state, backToRoot)
                        SettingsRoute.ABOUT -> AboutPage(state, backToRoot)
                        SettingsRoute.ROOT -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRootPage(
    state: AppState,
    onOpen: (SettingsRoute) -> Unit,
    rowModifier: @Composable (SettingsRoute) -> Modifier,
) {
    val connected = state.listenerOn && state.listenerConnected
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
    ) {
        ScreenHeader(
            title = "设置",
            latin = "ACCOUNT / NOTIFY / SYNC / TAX",
            right = { SyncBadge(state) },
        )
        state.settingsNotice?.let {
            NoticeBar(
                text = it,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                tone = if (state.lastError != null) QL.Amber else QL.Green,
            )
        }

        GroupLabel("分类", "SETTINGS")

        SettingRow(
            title = "账户",
            value = state.accountCode ?: if (state.syncEnabled) "未开户" else "仅本机",
            valueColor = when {
                state.accountCode != null -> QL.InkSoft
                state.syncEnabled -> QL.Amber
                else -> QL.InkSoft
            },
            note = when {
                state.accountCode != null && state.nickname.isNotBlank() ->
                    "${state.nickname} · 设备 ${state.deviceCount.coerceAtLeast(1)} 台"
                state.accountCode != null -> "设备 ${state.deviceCount.coerceAtLeast(1)} 台"
                state.syncEnabled -> "首次联网自动开户"
                else -> "只在这台手机记账"
            },
            leading = { SettingIcon(GlyphKind.USER) },
            onClick = { onOpen(SettingsRoute.ACCOUNT) },
            modifier = rowModifier(SettingsRoute.ACCOUNT),
        )

        SettingRow(
            title = "通知与打扰",
            value = when {
                !state.listenerOn -> "未开启"
                connected -> "已连接"
                state.listenerTrouble -> "已断开"
                else -> "连接中"
            },
            valueColor = when {
                !state.listenerOn -> QL.Amber
                connected -> QL.Green
                state.listenerTrouble -> QL.Seal
                else -> QL.InkSoft
            },
            note = buildString {
                append("跳过 ")
                append(state.filteredToday)
                append(" 条")
                if (state.dndActive && state.dndRemainingMin > 0) {
                    append("，静默剩 ${state.dndRemainingMin} 分")
                } else if (state.dndActive) {
                    append("，静默中")
                }
                if (state.systemDnd) append("，系统免打扰")
            },
            leading = { SettingIcon(GlyphKind.BELL) },
            onClick = { onOpen(SettingsRoute.NOTIFY) },
            modifier = rowModifier(SettingsRoute.NOTIFY),
        )

        SettingRow(
            title = "同步",
            value = when {
                !state.syncEnabled -> "仅本机"
                state.pending > 0 -> "待同步 ${state.pending}"
                else -> "已同步"
            },
            valueColor = when {
                !state.syncEnabled -> QL.InkFaint
                state.pending > 0 -> QL.Amber
                else -> QL.InkSoft
            },
            note = if (!state.syncEnabled) "仅本机记账，完整可用" else "上次 " + settingsSyncText(state.lastSyncAt),
            leading = { SettingIcon(GlyphKind.SYNC) },
            onClick = { onOpen(SettingsRoute.SETTLE) },
            modifier = rowModifier(SettingsRoute.SETTLE),
        )

        SettingRow(
            title = "计税规则",
            value = "${state.taxUnit} 秒/条，免税 ${state.taxExemption} 秒",
            valueColor = if (state.taxPending) QL.Amber else QL.InkSoft,
            note = if (state.taxPending) "等联网提交" else "单价与免税额可调",
            leading = { SettingIcon(GlyphKind.PERCENT) },
            onClick = { onOpen(SettingsRoute.TAX) },
            modifier = rowModifier(SettingsRoute.TAX),
        )

        SettingRow(
            title = "会话分类",
            note = "按联系人或群聊计税",
            leading = { SettingIcon(GlyphKind.BELL) },
            onClick = { onOpen(SettingsRoute.CONVERSATIONS) },
            modifier = rowModifier(SettingsRoute.CONVERSATIONS),
        )

        SettingRow(
            title = "日报",
            value = if (state.digestOn) settingsClock(state.digestHour, 0) else "已关",
            valueColor = if (state.digestOn) QL.InkSoft else QL.InkFaint,
            note = "每天发一份税单",
            leading = { SettingIcon(GlyphKind.SUN) },
            onClick = { onOpen(SettingsRoute.DIGEST) },
            modifier = rowModifier(SettingsRoute.DIGEST),
        )

        SettingRow(
            title = "外观",
            value = QL.paletteById(state.themeId).name,
            note = "四套配色",
            leading = { SettingIcon(GlyphKind.CONTRAST) },
            onClick = { onOpen(SettingsRoute.APPEARANCE) },
            modifier = rowModifier(SettingsRoute.APPEARANCE),
        )

        SettingRow(
            title = "桌面入口",
            note = "税单组件 / 快捷磁贴",
            leading = { SettingIcon(GlyphKind.GRID) },
            onClick = { onOpen(SettingsRoute.DESKTOP) },
            modifier = rowModifier(SettingsRoute.DESKTOP),
        )

        val newVersion = state.updatePhase == UpdatePhase.AVAILABLE && state.latestUpdate != null
        SettingRow(
            title = "版本与更新",
            value = "v${state.currentVersionName}",
            valueColor = if (newVersion) QL.Seal else QL.InkSoft,
            note = if (newVersion) "有新版本 v${state.latestUpdate?.versionName}" else "检查更新",
            leading = { SettingIcon(GlyphKind.DOWNLOAD) },
            onClick = { onOpen(SettingsRoute.UPDATE) },
            modifier = rowModifier(SettingsRoute.UPDATE),
        )

        SettingRow(
            title = "关于",
            note = "使用说明 / 清空本机数据",
            leading = { SettingIcon(GlyphKind.INFO) },
            onClick = { onOpen(SettingsRoute.ABOUT) },
            modifier = rowModifier(SettingsRoute.ABOUT),
        )

        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            // 版本号只在上面的「版本与更新」行出现一次：这里不再重复（v1.9.0 去冗余）。
            // 「本单不作报销凭证」属于票据语境的冷幽默（审计 P2-8），设置页不搬。
            MicroLabel("北京时间结算，免税额每天重置")
        }
    }
}

/**
 * 按「纸卡当前占的矩形」裁切内容：转场中页面内容只在纸卡范围内可见，
 * 不会漏到还在展开的纸卡外面。progress 在 draw 阶段读取，不触发重组 / layout。
 */
private fun Modifier.settingsCardMask(
    origin: Rect?,
    pageTop: Float,
    progress: () -> Float,
): Modifier {
    if (origin == null) return this
    return drawWithContent {
        val p = progress().coerceIn(0f, 1f)
        val top = lerp(origin.top - pageTop, 0f, p)
        val height = lerp(origin.height, size.height, p)
        clipRect(left = 0f, top = top, right = size.width, bottom = top + height) {
            this@drawWithContent.drawContent()
        }
    }
}
