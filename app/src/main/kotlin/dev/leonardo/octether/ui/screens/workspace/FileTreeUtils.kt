package dev.leonardo.octether.ui.screens.workspace

import dev.leonardo.octether.domain.model.isDirectory

/**
 * Flattens the file tree into a (node, depth) list for [androidx.compose.foundation.lazy.LazyColumn] rendering.
 *
 * Only descends into directories whose [dev.leonardo.octether.domain.model.FileNode.path] is in [expandedDirs].
 * Ignored nodes are filtered out unless [showIgnored] is true.
 *
 * @param nodes       tree root (or sub-tree) to flatten
 * @param expandedDirs set of directory paths that are currently expanded
 * @param showIgnored if false, nodes with [dev.leonardo.octether.domain.model.FileNode.ignored] = true are skipped
 * @param depth       current indentation depth (0 for root)
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
 * Returns a new tree where the node at [path] has its [children] replaced.
 *
 * Recursively searches all directories that already have non-null children.
 * Returns the original list unchanged if [path] is not found or lies behind
 * a node whose children have not been loaded yet (null).
 *
 * @param path     the [dev.leonardo.octether.domain.model.FileNode.path] of the target directory node
 * @param children new children list to assign
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
