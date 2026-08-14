package dev.leonardo.ocbeacon.data.local

/**
 * #97（H-6）：SSE delta 增量落盘 DTO——携带 UPSERT 所需元数据
 *（part 行不存在时 INSERT，存在时追加文本）。
 * @param type "text" / "reasoning"（Part.typeName 语义）
 */
data class PartDelta(
    val partId: String,
    val messageId: String,
    val sessionId: String,
    val type: String,
    val delta: String,
)