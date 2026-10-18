package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.StreamingMarkdownState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes.ATX_1
import org.intellij.markdown.MarkdownElementTypes.ATX_2
import org.intellij.markdown.MarkdownElementTypes.ATX_3
import org.intellij.markdown.MarkdownElementTypes.ATX_4
import org.intellij.markdown.MarkdownElementTypes.ATX_5
import org.intellij.markdown.MarkdownElementTypes.ATX_6
import org.intellij.markdown.MarkdownElementTypes.BLOCK_QUOTE
import org.intellij.markdown.MarkdownElementTypes.CODE_BLOCK
import org.intellij.markdown.MarkdownElementTypes.CODE_FENCE
import org.intellij.markdown.MarkdownElementTypes.IMAGE
import org.intellij.markdown.MarkdownElementTypes.ORDERED_LIST
import org.intellij.markdown.MarkdownElementTypes.PARAGRAPH
import org.intellij.markdown.MarkdownElementTypes.SETEXT_1
import org.intellij.markdown.MarkdownElementTypes.SETEXT_2
import org.intellij.markdown.MarkdownElementTypes.UNORDERED_LIST
import org.intellij.markdown.MarkdownTokenTypes.Companion.EOL
import org.intellij.markdown.MarkdownTokenTypes.Companion.HORIZONTAL_RULE
import org.intellij.markdown.MarkdownTokenTypes.Companion.TEXT
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE

// ============================================================================
// #552 根修（2026-10-19）：流式 markdown 渲染的元素级文本保留（O(N²)→O(N)）。
//
// 缺陷（mikepenz 0.45.0，r1036 真机定罪）：库 StreamingMarkdownSuccess 把
// **全文档 StringBuilder** 作为 content 传给每个元素；MarkdownElementInternal
// 内 `remember(node, content, typography) { content.toString() }` 为每个毕业
// 稳定元素物化并持有「毕业时刻的全文档快照」——N 个元素的保留内存
// Σ O(N²)：r1036 实测 2480 元素 × ~99K 字符 × 2B(UTF-16) ≈ 491MB →
// 512MB largeHeap 主线程 OOM（栈顶即该 toString；静态路径不受累——
// String.toString() 返回 this 零拷贝，仅流式 StringBuilder 路径成灾）。
//
// 修法：app 侧自备 success 槽（Markdown(streamingMarkdownState=…) 重载原生
// 支持），复刻库 Column+元素派发结构，但 MarkdownComponentModel 携带
// **元素自身文本**（content.subSequence(startOffset, endOffset)）+ **偏移
// 重基节点**（[ShiftedASTNode]：start/end/children 平移 -startOffset，
// parent=null=重基坐标系根语义）。保留内存降为 Σ 元素文本 = O(总文本) 线性。
//
// 失效语义与库同构：remember 键仍为 node 实例——稳定 AST 节点跨 append
// 实例复用（pilot 契约）→ 命中（元素文本毕业即冻结，永不过期）；不稳定
// 尾每次重解析产生新实例 → miss 重提取（瞬态成本同步从全文档降为元素
// 文本）。组件侧零改造：库/自研组件均经 getTextInNode(model.content) 等
// 绝对偏移取文本——重基后坐标系自洽（app 无 .parent 消费方，grep 证伪）。
//
// 上游备档：库正当修法=元素级文本记忆，已录 #146 候选线。
// ============================================================================

/**
 * 偏移重基节点：委托 [delegate] 全部结构，start/endOffset 与 children 平移
 * `-shift`（顶层元素 shift=自身 startOffset → 重基后从 0 起）。
 *
 * - children 惰性单例缓存（派发/注解会多次遍历，免每次新包装实例）。
 * - parent=null：重基坐标系中本子树即根（app/库组件无 parent 偏移消费方）。
 */
internal class ShiftedASTNode(
    private val delegate: ASTNode,
    private val shift: Int,
) : ASTNode {
    override val type: IElementType get() = delegate.type
    override val startOffset: Int get() = delegate.startOffset - shift
    override val endOffset: Int get() = delegate.endOffset - shift
    override val parent: ASTNode? get() = null
    private val shiftedChildren: List<ASTNode> by lazy(
        LazyThreadSafetyMode.NONE,
    ) {
        if (delegate.children.isEmpty()) {
            emptyList()
        } else {
            delegate.children.map { ShiftedASTNode(it, shift) }
        }
    }

    override val children: List<ASTNode> get() = shiftedChildren
}

/**
 * #552：构造元素级 [MarkdownComponentModel]（纯函数，单测锚）。
 *
 * @param content 流式全文档（只增不缩的 StringBuilder；越界 coerce 防失配帧）
 */
internal fun boundedElementModel(
    content: CharSequence,
    node: ASTNode,
    typography: MarkdownTypography,
): MarkdownComponentModel {
    val end = node.endOffset.coerceAtMost(content.length)
    val start = node.startOffset.coerceAtMost(end)
    return MarkdownComponentModel(
        content = content.subSequence(start, end).toString(),
        node = ShiftedASTNode(node, start),
        typography = typography,
    )
}

/**
 * #552：流式 success 槽工厂（接入 `Markdown(streamingMarkdownState = …)` 的
 * success 参数）。结构复刻库 [com.mikepenz.markdown.compose.StreamingMarkdownSuccess]
 * ——同一 Column、同序（稳定区+不稳定尾）、同一元素派发表；唯一差异=元素
 * model 携带元素自身文本而非全文档快照。
 */
internal fun streamingSuccessSlot(
    state: StreamingMarkdownState,
): @Composable (StreamingMarkdownState.Snapshot, MarkdownComponents, Modifier) -> Unit =
    { snapshot, components, modifier ->
        Column(modifier) {
            val content = state.content
            snapshot.stableAst.forEach { node ->
                BoundedMarkdownElement(node, components, content)
            }
            snapshot.unstableAstTail.forEach { node ->
                BoundedMarkdownElement(node, components, content)
            }
        }
    }

/** 库 MarkdownElementInternal 的有界复刻（派发表逐案对齐 0.45.0 源码）。 */
@Composable
private fun BoundedMarkdownElement(
    node: ASTNode,
    components: MarkdownComponents,
    content: CharSequence,
    includeSpacer: Boolean = true,
) {
    val typography = LocalMarkdownTypography.current
    val model = remember(node, typography) { boundedElementModel(content, node, typography) }
    var handled = true
    if (includeSpacer) Spacer(Modifier.height(LocalMarkdownPadding.current.block))
    when (node.type) {
        TEXT -> components.text(model)
        EOL -> components.eol(model)
        CODE_FENCE -> components.codeFence(model)
        CODE_BLOCK -> components.codeBlock(model)
        ATX_1 -> components.heading1(model)
        ATX_2 -> components.heading2(model)
        ATX_3 -> components.heading3(model)
        ATX_4 -> components.heading4(model)
        ATX_5 -> components.heading5(model)
        ATX_6 -> components.heading6(model)
        SETEXT_1 -> components.setextHeading1(model)
        SETEXT_2 -> components.setextHeading2(model)
        BLOCK_QUOTE -> components.blockQuote(model)
        PARAGRAPH -> components.paragraph(model)
        ORDERED_LIST -> components.orderedList(model)
        UNORDERED_LIST -> components.unorderedList(model)
        IMAGE -> components.image(model)
        HORIZONTAL_RULE -> components.horizontalRule(model)
        TABLE -> components.table(model)
        else -> {
            handled = components.custom?.invoke(node.type, model) != null
        }
    }

    if (!handled) {
        // 未识别类型：递归子节点（与库同参传递 includeSpacer）。content 仍是
        // 全文档（子节点绝对偏移在本坐标系有效——提取端自带 coerce）。
        node.children.forEach { child ->
            BoundedMarkdownElement(child, components, content, includeSpacer)
        }
    }
}
