package dev.leonardo.ocbeacon.domain.model

/**
 * 斜杠命令执行反馈行状态（#323）——DSH 转录 log-only 事件 command/run|done 经
 * commandId 配对折叠出的单卡状态。
 *
 * - [done] == null：进行中（run 建卡，spinner 语义行）；
 * - [done] 非空：终态（done 原位终态化同一张卡——同 commandId 单卡刷新，非两行，
 *   DshJobTimelineCard runId 同宿主原位更新同款语义）。
 * - [localAccepted] == true 且 [done] == null：#365 本地受理占位——
 *   commands/execute 受理成功即建（不等服务器事件，「受理即知」层）；
 *   DSH 原生命令的 command/run 到达后同名占位原位升级转正（见 Folder.onRun）。
 *
 * 折叠纯函数见 [CommandFeedbackFolder]（handler 只做容器写）。
 */
data class CommandFeedback(
    /** 配对键（wire commandId——run/done 两事件唯一可共享的连接键；本地占位为 local- 前缀合成 id）。 */
    val commandId: String,
    /** 命令名（wire name；orphan done 无 run 前驱时为空串，UI 回退通用标签）。 */
    val name: String,
    /** 命令原始入参（wire args——recordInput=false 的命令缺席为 null）。 */
    val args: String? = null,
    /** run 信封 seq（列表插入序键——事件按 seq 升序追加）。 */
    val seq: Long = 0L,
    /** run 信封 time（卡时间戳）。 */
    val startedAt: Long = 0L,
    /** 终态（null = 进行中）。 */
    val done: Done? = null,
    /** #365：本地受理占位（受理即知——命令通道无消息语义，本地合成行先占位）。 */
    val localAccepted: Boolean = false,
) {
    /** 结算态：kind 词汇开放（success|error|…，dsh-commands 契约）。 */
    data class Done(
        val kind: String,
        /** 结算文本（缺席为 null；error 常带失败原因，success 可带摘要）。 */
        val text: String? = null,
        /** success 结算引用的源事件 seq（缺席为 null；保真透传，暂无 UI 消费）。 */
        val sourceEventSeq: Long? = null,
        /** done 信封 time（终态时间戳）。 */
        val time: Long = 0L,
    ) {
        val isSuccess: Boolean get() = kind == "success"
        val isError: Boolean get() = kind == "error"
    }
}

/**
 * #323 配对纯函数：commandId 配对原位更新。
 *
 * - run：同 commandId 原位替换（重放幂等），否则按到达序（seq 升序）追加；
 *   #365 增补：追加前先找最近的同名未转正本地受理占位（localAccepted 且未终态）
 *   原位升级——wire 无「execute → run」的连接键（commands/execute 不回
 *   commandId），同名+最近占位是唯一可用配对；占位转正后即普通卡。
 * - done：同 commandId 原位终态化（保位、保 name/args——run 建的卡刷新为终态，
 *   不产生第二行）；无 run 前驱的 orphan done 自建终态卡（name 空串回退）。
 * - localAcceptance（#365）：executeCommand 受理成功即追加占位（受理即知，
 *   不依赖服务器事件——skill 类命令无 command/run|done，占位即最终形态）。
 */
object CommandFeedbackFolder {

    fun onLocalAcceptance(
        states: List<CommandFeedback>,
        name: String,
        args: String?,
        now: Long,
    ): List<CommandFeedback> = states + CommandFeedback(
        commandId = "local-" + java.util.UUID.randomUUID(),
        name = name,
        args = args,
        startedAt = now,
        localAccepted = true,
    )

    /**
     * #365：派发失败终态化——同名最近的未终态本地占位翻为 error 终态
     * （受理即知在派发时插入；RPC 失败/异常时占位不留悬空「已受理」）。
     */
    fun onLocalFailure(states: List<CommandFeedback>, name: String): List<CommandFeedback> {
        val index = states.indexOfLast { it.localAccepted && it.done == null && it.name == name }
        if (index < 0) return states
        return states.toMutableList().apply {
            set(index, states[index].copy(done = CommandFeedback.Done(kind = "error")))
        }
    }

    fun onRun(states: List<CommandFeedback>, event: SseEvent.CommandRunStarted): List<CommandFeedback> {
        val state = CommandFeedback(
            commandId = event.commandId,
            name = event.name,
            args = event.args,
            seq = event.seq,
            startedAt = event.time,
        )
        val index = states.indexOfFirst { it.commandId == event.commandId }
        if (index >= 0) return states.toMutableList().apply { set(index, state) }
        // #365：同名最近的未转正本地占位原位升级（保位——受理行变 Running 行，非两行）
        val localIndex = states.indexOfLast {
            it.localAccepted && it.done == null && it.name == event.name
        }
        return if (localIndex < 0) {
            states + state
        } else {
            states.toMutableList().apply { set(localIndex, state) }
        }
    }

    fun onDone(states: List<CommandFeedback>, event: SseEvent.CommandDone): List<CommandFeedback> {
        val done = CommandFeedback.Done(
            kind = event.kind,
            text = event.text,
            sourceEventSeq = event.sourceEventSeq,
            time = event.time,
        )
        val index = states.indexOfFirst { it.commandId == event.commandId }
        return if (index < 0) {
            // orphan done：run 缺席（如 run 追加失败 Loud 路径的反向场景/重放截断）——
            // 自建终态卡，name 空串（UI 回退通用标签），不丢弃结算事实。
            states + CommandFeedback(
                commandId = event.commandId,
                name = "",
                seq = event.seq,
                startedAt = event.time,
                done = done,
            )
        } else {
            states.toMutableList().apply { set(index, states[index].copy(done = done)) }
        }
    }
}
