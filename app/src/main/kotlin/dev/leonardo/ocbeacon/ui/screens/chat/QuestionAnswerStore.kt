package dev.leonardo.ocbeacon.ui.screens.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/**
 * 提问卡答案快照——**提交载荷与"保留未勾选"的自定义内容分离**
 * （2026-08-18 用户反馈修复：单选选了其他选项时，自定义答案应
 * "保留内容，但取消勾选"，而非一直处于勾选态）。
 *
 * @param answers 每题的选中集（提交载荷：选项 + 已勾选的自定义，至多 1 条自定义）
 * @param parkedCustoms 每题保留未勾选的自定义内容（null=无）；不参与提交，
 *   仅用于 UI 恢复"可再勾选"的草稿行
 */
@Serializable
data class QuestionAnswersSnapshot(
    val answers: List<List<String>> = emptyList(),
    val parkedCustoms: List<String?> = emptyList()
)

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

    private val cache = java.util.concurrent.ConcurrentHashMap<String, QuestionAnswersSnapshot>()

    /** QuestionCard 初始化时读取（恢复优先级：store > saveable > initialAnswers）。 */
    fun get(questionId: String): QuestionAnswersSnapshot? = cache[questionId]

    /** QuestionCard SideEffect 双写目标之一。 */
    fun put(questionId: String, snapshot: QuestionAnswersSnapshot) {
        if (cache.size >= MAX_ENTRIES && !cache.containsKey(questionId)) {
            // 防御：异常场景下（如大量未消费问题）限制容量，丢弃最旧
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[questionId] = snapshot
    }

    /** 答案已消费（提交/拒绝）——ChatViewModel 调用，防串新卡。 */
    fun consume(questionId: String) {
        cache.remove(questionId)
    }

    private companion object {
        const val MAX_ENTRIES = 16
    }
}
