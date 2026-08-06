package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 管理从 FileViewer / 子会话导航返回后用于恢复列表的已保存聊天滚动位置。
 *
 * 在 Phase 3 Task 1b 中提取。零依赖 —— 纯状态持有者。
 *
 * Compose [mutableStateOf] 状态由 ChatScreen 通过 ChatViewModel 门面 getter 读取。
 * 快照读取通过属性间接引用被正确跟踪，因此重组
 * 行为与之前的内联声明完全一致。
 *
 * 注意：[savedMessageId] 当前从未被赋值 —— 它从
 * 重构前的 ChatViewModel 原样保留为预先存在的死状态（非本次变更引入）。
 */
internal class ScrollPositionDelegate {

    /** 已保存的滚动位置，用于子会话导航后恢复。 */
    var savedMessageId by mutableStateOf<String?>(null)
        private set

    /** 保存时的原始 LazyColumn 索引 —— 用于无需索引运算的直接恢复。 */
    var savedLazyIndex by mutableStateOf(0)
        private set

    var savedScrollOffset by mutableStateOf(0)
        private set

    /** 保存时第一个可见项的 key —— 用于验证恢复准确性。 */
    var savedFirstVisibleKey by mutableStateOf<String?>(null)
        private set

    /**
     * 每次调用 [saveScrollPosition] 时递增，ChatScreen 恢复且有
     * 待恢复时 [bumpScrollRestoreIfPending] 再次递增。
     * ChatScreen 通过 LaunchedEffect 观察它以可靠地恢复滚动位置
     *（从 FileViewer / 子会话导航返回后）。使用 rememberLazyListState(initial...)
     * 不可靠，因为 `remember` 在首次组合时缓存初始状态，
     * 重组时忽略新值，导致滚动有时恢复有时不恢复。
     */
    var scrollRestoreVersion by mutableStateOf(0)
        private set

    /**
     * 当滚动位置已保存（通过 [saveScrollPosition]）但尚未恢复时为 true。
     * 由 [bumpScrollRestoreIfPending] 用于仅在从实际保存了位置的导航
     *（FileViewer / 子会话）返回时重新触发恢复，
     * 避免在普通后台→前台切换时进行虚假恢复，
     * 那会干扰用户当前的浏览位置。
     */
    private var hasPendingScrollRestore = false

    /** 公共只读标志：返回时需要恢复滚动位置时为 true。 */
    val pendingScrollRestore: Boolean get() = hasPendingScrollRestore

    fun clearPendingScrollRestore() {
        hasPendingScrollRestore = false
    }

    fun saveScrollPosition(lazyIndex: Int, offset: Int, firstVisibleKey: String? = null) {
        savedLazyIndex = lazyIndex
        savedScrollOffset = offset
        savedFirstVisibleKey = firstVisibleKey
        scrollRestoreVersion++
        hasPendingScrollRestore = true
    }

    /**
     * 在 ON_RESUME 时重新触发滚动位置恢复，但仅在有待保存时
     *（即用户从 FileViewer 或子会话返回时）。普通后台→前台
     * 切换被忽略，使用户当前的浏览位置不被干扰。
     */
    fun bumpScrollRestoreIfPending() {
        if (hasPendingScrollRestore) {
            scrollRestoreVersion++
        }
    }
}

