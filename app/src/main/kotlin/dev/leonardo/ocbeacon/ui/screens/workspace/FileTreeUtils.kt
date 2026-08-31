package dev.leonardo.ocbeacon.ui.screens.workspace

import dev.leonardo.ocbeacon.domain.model.FileType
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
): List<Pair<FileTreeNode, Int>> = buildList {
    // L-12：buildList + addAll 累积——原实现用 `+` 递归拼接每层拷贝整棵扁平列表（O(n²)）
    for (treeNode in nodes) {
        if (!showIgnored && treeNode.node.ignored) continue
        add(treeNode to depth)
        if (treeNode.node.isDirectory() &&
            treeNode.node.path in expandedDirs &&
            treeNode.children != null
        ) {
            addAll(flattenTree(treeNode.children, expandedDirs, showIgnored, depth + 1))
        }
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
/**
 * #276 终验 V4（DSH 目录惰性探测的协议级补偿）：把 [path] 处的节点改标为
 * 文件（叶）。
 *
 * DSH host.listDirectory 条目无类型判别（仅 {name,path,hidden}，活体样本
 * 04），客户端缺省全部按 directory 可展开；对非目录路径展开会得到闭集错误码
 * directory-unreadable——这一次失败的展开即探测信号，把该节点转标 file
 * （children=emptyList，与已加载文件叶同构）。转标结果随 rootNodes 缓存已
 * 解析类型：节点不再可展开，避免重复探测。传输层/其他服务端错误不走此路径
 * （真实目录的瞬时失败不得被误降级，见 WorkspaceViewModel 失败分支的错误码
 * 判别）。
 */
internal fun List<FileTreeNode>.demoteToFile(path: String): List<FileTreeNode> = map { treeNode ->
    when {
        treeNode.node.path == path -> treeNode.copy(
            node = treeNode.node.copy(type = FileType.FILE),
            children = emptyList(),
        )
        treeNode.node.isDirectory() && treeNode.children != null ->
            treeNode.copy(children = treeNode.children.demoteToFile(path))
        else -> treeNode
    }
}

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
