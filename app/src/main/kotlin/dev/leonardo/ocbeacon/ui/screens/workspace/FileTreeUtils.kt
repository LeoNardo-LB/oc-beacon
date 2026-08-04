package dev.leonardo.ocbeacon.ui.screens.workspace

import dev.leonardo.ocbeacon.domain.model.isDirectory

/**
 * 将文件树扁平化为 (node, depth) 列表，用于 [androidx.compose.foundation.lazy.LazyColumn] 渲染。
 *
 * 仅下钻到 [dev.leonardo.ocbeacon.domain.model.FileNode.path] 位于 [expandedDirs] 中的目录。
 * 除非 [showIgnored] 为 true，否则被忽略的节点会被过滤掉。
 *
 * @param nodes         需要扁平化的树根（或子树）
 * @param expandedDirs  当前已展开的目录路径集合
 * @param showIgnored   若为 false，跳过 [dev.leonardo.ocbeacon.domain.model.FileNode.ignored] = true 的节点
 * @param depth         当前缩进深度（根节点为 0）
 */
internal fun flattenTree(
    nodes: List<FileTreeNode>,
    expandedDirs: Set<String>,
    showIgnored: Boolean,
    depth: Int = 0
): List<Pair<FileTreeNode, Int>> =
    nodes.filter { showIgnored || !it.node.ignored }.flatMap { treeNode ->
        listOf(treeNode to depth) +
            if (treeNode.node.isDirectory() &&
                treeNode.node.path in expandedDirs &&
                treeNode.children != null
            ) {
                flattenTree(treeNode.children, expandedDirs, showIgnored, depth + 1)
            } else {
                emptyList()
            }
    }

/**
 * 返回一棵新树，其中 [path] 处的节点其 [children] 已被替换。
 *
 * 递归搜索所有已具有非空 children 的目录。
 * 若未找到 [path]，或 [path] 位于 children 尚未加载（null）的节点之后，
 * 则原样返回原始列表。
 *
 * @param path     目标目录节点的 [dev.leonardo.ocbeacon.domain.model.FileNode.path]
 * @param children 要赋给目标节点的新 children 列表
 */
internal fun List<FileTreeNode>.withChildren(
    path: String,
    children: List<FileTreeNode>
): List<FileTreeNode> = map { treeNode ->
    when {
        treeNode.node.path == path -> treeNode.copy(children = children)
        treeNode.node.isDirectory() && treeNode.children != null ->
            treeNode.copy(children = treeNode.children.withChildren(path, children))
        else -> treeNode
    }
}
