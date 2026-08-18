package dev.leonardo.ocbeacon.ui.screens.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提问卡答案的**应用级**存储（E2E-C 向量1/2 修复，2026-08-18）。
 *
 * 为什么不用 ChatViewModel：hiltViewModel<ChatViewModel>() 作用域是聊天页
 * NavBackStackEntry——BACK pop 销毁 entry（VM onCleared）、Activity recreate
 * 重建 VM，两条路径上 VM 级缓存都活不过去（终验实证 9f66bacd 双 FAIL）。
 * 应用级单例（同 SessionScrollSignal 模式）跨导航条目与 recreate 存活。
 *
 * 生命周期：答案已消费（提交/拒绝）时移除；容量防御：仅保留最近
 * [MAX_ENTRIES] 条（正常场景同时 pending 的问题极少超过个位数）。
 */
@Singleton
class QuestionAnswerStore @Inject constructor() {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<List<String>>>()

    /** QuestionCard 初始化时读取（恢复优先级：store > saveable > initialAnswers）。 */
    fun get(questionId: String): List<List<String>>? = cache[questionId]

    /** QuestionCard SideEffect 双写目标之一。 */
    fun put(questionId: String, answers: List<List<String>>) {
        if (cache.size >= MAX_ENTRIES && !cache.containsKey(questionId)) {
            // 防御：异常场景下（如大量未消费问题）限制容量，丢弃最旧
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[questionId] = answers
    }

    /** 答案已消费（提交/拒绝）——ChatViewModel 调用，防串新卡。 */
    fun consume(questionId: String) {
        cache.remove(questionId)
    }

    private companion object {
        const val MAX_ENTRIES = 16
    }
}
