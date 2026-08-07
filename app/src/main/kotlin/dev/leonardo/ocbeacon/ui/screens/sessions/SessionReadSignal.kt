package dev.leonardo.ocbeacon.ui.screens.sessions

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 跨屏幕已读信号（内存即时）。
 *
 * ChatViewModel 退出会话时**先**更新此内存态、**再**异步写 DataStore——
 * 会话列表立即感知已读，消除"退出瞬间 DataStore 写入未完成导致的红点
 * 闪烁"（2026-08-07：列表读到旧已读时间 + 新回复时间 → 红点闪一下再消失）。
 *
 * 内存态不跨进程持久化（DataStore 才是持久真相源）；会话 id 全局唯一，
 * 直接以 sessionId 为 key。
 */
@Singleton
class SessionReadSignal @Inject constructor() {
    private val _justRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val justRead: StateFlow<Map<String, Long>> = _justRead

    /** 标记会话已读（记录当前时刻）。 */
    fun markRead(sessionId: String, time: Long) {
        _justRead.update { it + (sessionId to time) }
    }

    /** 移除会话的已读记录（会话被删除时调用，防止残留 key 无界增长）。 */
    fun remove(sessionId: String) {
        _justRead.update { it - sessionId }
    }
}
