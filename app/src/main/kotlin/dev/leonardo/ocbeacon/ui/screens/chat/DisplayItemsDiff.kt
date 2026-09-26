package dev.leonardo.ocbeacon.ui.screens.chat

/**
 * R4-B3 步2：displayItems 差量更新纯函数——生产承载 SnapshotStateList
 * （index 级依赖：set(i) 只失效读 [i] 的 item 重组作用域）。
 * 返回写入槽数（观测/测试用；0=零失效快路径）。
 * 长度不同=结构变化（分页/插拔）→ 全量重置（retains structural 语义）。
 */
internal fun diffDisplayItemsInto(
    target: MutableList<Pair<Int, ChatMessage>>,
    fresh: List<Pair<Int, ChatMessage>>,
): Int {
    if (target.size != fresh.size) {
        target.clear()
        target.addAll(fresh)
        return fresh.size
    }
    var writes = 0
    for (i in fresh.indices) {
        val old = target[i]
        val new = fresh[i]
        // ChatMessage 是 data class（含 parts 内容 equals）——内容级比较
        if (old.first != new.first || old.second != new.second) {
            target[i] = new
            writes++
        }
    }
    return writes
}
