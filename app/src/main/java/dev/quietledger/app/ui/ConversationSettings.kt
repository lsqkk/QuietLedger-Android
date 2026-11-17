package dev.quietledger.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.quietledger.app.core.ConversationClass
import dev.quietledger.app.core.ConversationRule
import dev.quietledger.app.core.ConversationSource

private val ConversationClass.glyph: GlyphKind
    get() = when (this) {
        ConversationClass.DEFAULT -> GlyphKind.PERCENT
        ConversationClass.WORK -> GlyphKind.LIST
        ConversationClass.FAMILY -> GlyphKind.USER
        ConversationClass.FRIENDS -> GlyphKind.BELL
        ConversationClass.CASUAL -> GlyphKind.MEGAPHONE
        ConversationClass.FREE -> GlyphKind.SILENT
    }

@Composable
internal fun ConversationSettingsPage(state: AppState, onBack: () -> Unit) {
    LaunchedEffect(state.todayEvents) { state.loadConversationRules() }
    val overview = state.conversationOverview
    var pending by rememberSaveable { mutableStateOf(true) }
    var byApp by rememberSaveable { mutableStateOf(true) }
    val pendingScroll = rememberLazyListState()
    val appScroll = rememberLazyListState()
    val categoryScroll = rememberLazyListState()
    val entrance = rememberEntranceWindow()
    val rules = overview?.rules.orEmpty()
    val groups = remember(rules, byApp) {
        if (byApp) rules.groupBy { it.source.pkg }.values.toList()
        else ConversationClass.entries.map { kind -> rules.filter { it.kind == kind } }.filter { it.isNotEmpty() }
    }
    Column(Modifier.fillMaxSize()) {
        SubPageHeader(title = "会话分类", onBack = onBack, glyph = GlyphKind.BELL)
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().border(1.dp, QL.RuleSoft)) {
            ConversationView("待分类", overview?.pending?.size, GlyphKind.BELL, pending, Modifier.weight(1f)) {
                pending = true
            }
            ConversationView("已设置", overview?.rules?.size, GlyphKind.LIST, !pending, Modifier.weight(1f)) {
                pending = false
            }
        }
        Crossfade(
            targetState = overview != null,
            animationSpec = Motion.standard(Motion.ContentSwap),
            label = "conversation overview",
        ) { ready ->
            if (!ready || overview == null) {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.conversationLoadFailed) {
                        SettingRow("读取失败，重试", onClick = state::loadConversationRules)
                    } else {
                        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth(), height = 42.dp) }
                    }
                }
            } else if (pending) {
                LazyColumn(state = pendingScroll, contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        GroupLabel("近期常收到的会话")
                        ConversationHint("今天至少 2 条，或近 7 天至少 3 条")
                    }
                    if (overview.pending.isEmpty()) {
                        item {
                            GroupLabel("暂无待分类会话")
                            ConversationHint("也可以在明细里打开一条聊天通知设置分类。")
                        }
                    }
                    itemsIndexed(overview.pending, key = { _, item -> item.source.key }) { index, item ->
                        val summary = if (item.today > 0) "今天 ${item.today} 条 / 近 7 天 ${item.week} 条"
                        else "近 7 天 ${item.week} 条"
                        SettingRow(
                            title = item.source.name,
                            note = "${item.source.app} / $summary",
                            leading = { AppIcon(item.source.pkg, 24.dp, item.source.app) },
                            onClick = { state.classifyConversation(item.source) },
                            modifier = Modifier.animateItem().staggerIn(index, entrance),
                        )
                    }
                }
            } else {
                Column {
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().border(1.dp, QL.RuleSoft)) {
                        ConversationView("按应用", null, GlyphKind.GRID, byApp, Modifier.weight(1f)) { byApp = true }
                        ConversationView("按类别", null, GlyphKind.LIST, !byApp, Modifier.weight(1f)) { byApp = false }
                    }
                    LazyColumn(state = if (byApp) appScroll else categoryScroll, contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (rules.isEmpty()) {
                            item {
                                GroupLabel("还没有设置")
                                ConversationHint("从待分类或明细选择会话。")
                            }
                        }
                        var start = 0
                        for (group in groups) {
                            val first = group.first()
                            val offset = start
                            start += group.size
                            item(key = "group:${if (byApp) first.source.pkg else first.kind.wire}") {
                                ConversationGroup(first, group.size, byApp)
                            }
                            itemsIndexed(group, key = { _, rule -> rule.source.key }) { index, rule ->
                                SettingRow(
                                    title = rule.source.name,
                                    note = if (byApp) rule.kind.label else rule.source.app,
                                    leading = {
                                        if (byApp) Glyph(rule.kind.glyph, size = 20.dp, color = QL.InkSoft)
                                        else AppIcon(rule.source.pkg, 24.dp, rule.source.app)
                                    },
                                    onClick = { state.classifyConversation(rule.source) },
                                    modifier = Modifier.animateItem().staggerIn(offset + index, entrance),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationHint(text: String) {
    Text(text, style = QL.bodySoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun ConversationGroup(first: ConversationRule, count: Int, byApp: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(QL.RuleSoft).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (byApp) AppIcon(first.source.pkg, 18.dp, first.source.app)
        else Glyph(first.kind.glyph, size = 18.dp, color = QL.InkSoft)
        Text(if (byApp) first.source.app else first.kind.label, style = QL.h3, modifier = Modifier.weight(1f))
        MonoText("$count 个")
    }
}

@Composable
private fun ConversationView(
    label: String,
    count: Int?,
    icon: GlyphKind,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val color = if (active) QL.Paper else QL.InkSoft
    Row(
        modifier.background(if (active) QL.Ink else QL.Paper)
            .semantics { role = Role.Tab; selected = active }
            .qlClickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, size = 14.dp, color = color)
        Text(label, style = QL.label.copy(color = color))
        if (count != null) MonoText(count.toString(), style = QL.monoSmall, color = color)
    }
}

@Composable
internal fun ConversationClassSheet(state: AppState, source: ConversationSource) {
    val rules = state.conversationOverview?.rules
    val current = rules?.firstOrNull { it.source.key == source.key }?.kind
    QlSheet(
        title = "设置会话分类",
        subtitle = source.name,
        onClose = state::dismissConversationClass,
        scrollable = true,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(source.pkg, 20.dp, source.app)
            MicroLabel(source.app)
        }
        MicroLabel(if (state.savingConversationRule) "正在保存" else "下条消息生效，历史账单不变")
        if (rules == null) {
            if (state.conversationLoadFailed) {
                SettingRow("读取失败，重试", onClick = state::loadConversationRules)
            } else {
                SkeletonBlock(Modifier.fillMaxWidth())
            }
        } else {
            for (kind in ConversationClass.entries) {
                val selected = kind == current
                SettingRow(
                    title = kind.label,
                    note = if (selected) "当前设置" else null,
                    value = kind.rateLabel,
                    valueColor = if (selected) QL.Seal else QL.InkSoft,
                    leading = { Glyph(kind.glyph, size = 18.dp, color = if (selected) QL.Seal else QL.InkSoft) },
                    onClick = if (state.savingConversationRule) null else {
                        { state.saveConversationClass(source, kind) }
                    },
                    showChevron = false,
                )
            }
        }
    }
}
