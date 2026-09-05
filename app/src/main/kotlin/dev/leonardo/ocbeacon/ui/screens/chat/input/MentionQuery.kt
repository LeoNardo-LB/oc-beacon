package dev.leonardo.ocbeacon.ui.screens.chat.input

/**
 * @ 提及 query 形态判定（#310⑤+#321）。
 *
 * web 先例（mod34）：@\" 引号形态（quoted）只拉文件候选、跳过会话域——
 * AT_MENTION_REGEX=@(\\S*)$ 的 group 天然含前导引号，故以引号前缀判定。
 */
internal fun isQuotedMentionQuery(query: String): Boolean = query.startsWith("\"")
