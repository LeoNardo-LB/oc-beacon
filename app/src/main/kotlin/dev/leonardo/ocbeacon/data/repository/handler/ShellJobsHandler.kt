package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台 shell 事件处理器——将 session.shell.started/ended 写入 [ShellJobsStore]。
 *
 * #252 时间线化（2026-08-28，对齐官方客户端模式）：started 时**乐观插入**一条
 * 本地合成消息（id=msg_local_shell_<shellId>，带 Part.Shell 载荷）进消息流——
 * 通知卡即时出现在时间线位置，无需等待 REST 重拉。合成行仅存内存（SSE_PRIORITY
 * 对无 tokens 变更的消息不落 Room）；REST 刷新带来的真消息（id=msg_<服务器事件id>）
 * 才是持久权威。渲染层（buildChatEntries）对同 shellId 的 local 合成行在真消息
 * 入库后让位，避免双卡。ended 终态经 store 兜底渲染 + 既有的去抖 REST 刷新拉取。
 */
@Singleton
class ShellJobsHandler @Inject constructor(
    private val shellJobsStore: ShellJobsStore,
    private val messageHandler: MessageEventHandler
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean = when (event) {
        is SseEvent.ShellJobStarted -> {
            shellJobsStore.onShellStarted(event.info)
            upsertOptimisticShellMessage(event.info)
            true
        }
        is SseEvent.ShellJobEnded -> {
            shellJobsStore.onShellEnded(event.info, event.output)
            // ended 终态同步进乐观行（若该行仍在——真消息入库后本更新不命中
            // 也无碍：真消息自带终态，store 兜底渲染覆盖剩余窗口）
            upsertOptimisticShellMessage(event.info.copy(output = event.output ?: event.info.output))
            true
        }
        else -> false
    }

    private fun upsertOptimisticShellMessage(info: dev.leonardo.ocbeacon.domain.model.ShellJob) {
        val sid = info.sessionId ?: return
        if (sid.isEmpty() || info.id.isEmpty()) return
        val msgId = "msg_local_shell_" + info.id
        val message = Message.User(
            id = msgId,
            sessionId = sid,
            role = "shell",
            time = TimeInfo(created = info.startedAt ?: System.currentTimeMillis())
        )
        val part = Part.Shell(
            id = msgId + "_shell",
            sessionId = sid,
            messageId = msgId,
            shellId = info.id,
            command = info.command,
            status = info.status,
            exit = info.exit,
            output = info.output
        )
        messageHandler.upsertMessages(
            sid,
            listOf(MessageWithParts(info = message, parts = listOf(part))),
            MergeStrategy.SSE_PRIORITY
        )
    }

    /** 释放单会话 shell 任务（内存泄漏修复 #89，由 EventDispatcher.releaseSessionData 调用）。 */
    fun clearForSession(sessionId: String) {
        shellJobsStore.clearForSession(sessionId)
    }
}
