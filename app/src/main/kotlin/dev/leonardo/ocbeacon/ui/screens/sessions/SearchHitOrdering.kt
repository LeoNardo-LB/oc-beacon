package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchFilterValues
import dev.leonardo.ocbeacon.data.local.ContentSearchHit

/**
 * #393：搜索内容命中——单会话组内的排序（去重 + 角色交错），纯函数便于单测。
 *
 * 根因：逐命中行改造后组内仅按 FTS5 BM25 rank（越小越相关）排序；短文档偏置使
 * user 短提示恒居前，AI 命中被挤到 240dp 折叠区下方，用户在「全部」过滤下只见
 * 人类行。对策：两种角色各自保持 rank 序，按 u,a,u,a… 交错——只要会话同时有
 * 两种角色命中，首屏必然同时出现。
 *
 * 去重：FTS 行级命中（同消息多 part / 同步路径重复 partId）折叠为每消息一行
 * （保留 rank 最优的一条）。
 */
internal fun interleaveSearchHits(hits: List<ContentSearchHit>): List<ContentSearchHit> {
    val byRank = hits
        .sortedBy { it.rank ?: Double.MAX_VALUE }
        .distinctBy { it.messageId }
    val users = byRank.filter { it.role == ContentSearchFilterValues.ROLE_USER }
    val agents = byRank.filter { it.role != ContentSearchFilterValues.ROLE_USER }
    val out = ArrayList<ContentSearchHit>(byRank.size)
    val maxN = maxOf(users.size, agents.size)
    for (i in 0 until maxN) {
        users.getOrNull(i)?.let(out::add)
        agents.getOrNull(i)?.let(out::add)
    }
    return out
}
