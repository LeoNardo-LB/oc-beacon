package dev.leonardo.ocbeacon.ui.screens.chat

/**
 * R4-B3 步2 终收口：列表差量更新纯函数（rawMessages 与 displayItems 共用）——
 * SnapshotStateList 承载（index 级依赖：set(i) 只失效读 [i] 的作用域）。
 * 返回写入槽数（0=零失效快路径）。长度不同=结构变化 → 全量重置。
 */
internal fun <T> diffListInto(target: MutableList<T>, fresh: List<T>): Int {
    if (target.size != fresh.size) {
        target.clear()
        target.addAll(fresh)
        return fresh.size
    }
    var writes = 0
    for (i in fresh.indices) {
        if (target[i] != fresh[i]) {
            target[i] = fresh[i]
            writes++
        }
    }
    return writes
}
