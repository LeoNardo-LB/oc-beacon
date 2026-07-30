package dev.leonardo.ocremoteplus.ui.screens.sessions.util

import dev.leonardo.ocremoteplus.domain.model.Project
import dev.leonardo.ocremoteplus.domain.model.Session
import dev.leonardo.ocremoteplus.ui.screens.sessions.SessionItem

/**
 * A group of sessions belonging to a single project (or an unmatched directory).
 *
 * Project-aware grouping improves over raw directory grouping by aggregating
 * sessions whose [Session.projectId] maps to the same [Project], even when they
 * live in different worktree paths. Sessions without a matching project form
 * their own per-directory groups.
 */
data class ProjectSessionGroup(
    val projectId: String,
    val projectName: String,
    val directory: String,
    val sessions: List<SessionItem>,
    /** Per-session tilde-path labels (sessionId -> tildePath) for flat display. */
    val sessionDirLabels: Map<String, String> = emptyMap(),
)

/**
 * Resolve the [Project] a [session] belongs to.
 *
 * Matching priority:
 *  1. Exact [Session.projectId] match against [Project.id].
 *  2. Longest matching worktree/path prefix fallback — handles sessions that live
 *     inside a project worktree whose `projectId` was not populated by the server.
 *
 * Returns `null` when no project matches.
 */
internal fun projectForSession(session: Session, projects: List<Project>): Project? {
    // 1. Exact projectId match.
    projects.firstOrNull { it.id.isNotBlank() && it.id == session.projectId }?.let { return it }
    // 2. Longest worktree/path prefix fallback.
    val directory = normalizedPath(session.directory)
    return projects
        .filter { project ->
            val root = projectRoot(project)
            root.isNotEmpty() && (directory == root || directory.startsWith("$root/"))
        }
        .maxByOrNull { projectRoot(it).length }
}

/** Sort sessions within a group: newest activity first. */
internal fun sortSessionItems(items: List<SessionItem>): List<SessionItem> =
    items.sortedByDescending { it.session.time.updated }

/**
 * Build project-aware session groups from a flat list of [SessionItem]s.
 *
 * Sessions are grouped by their owning project (see [projectForSession]); sessions
 * with no matching project each become an independent group keyed by directory.
 * Groups are ordered by latest activity (descending), then by name.
 *
 * @param homeDir Optional server home directory used to render tilde-relative
 *  labels in [ProjectSessionGroup.sessionDirLabels]. Pass `null` to keep absolute paths.
 */
internal fun buildProjectSessionGroups(
    sessions: List<SessionItem>,
    projects: List<Project>,
    homeDir: String?,
): List<ProjectSessionGroup> {
    fun displayPath(path: String): String {
        val dir = normalizedPath(path)
        val home = homeDir?.let(::normalizedPath)
        return if (home != null && home.isNotEmpty() && (dir == home || dir.startsWith("$home/"))) {
            "~" + dir.removePrefix(home)
        } else {
            dir
        }
    }

    return sessions
        .groupBy { item ->
            val project = projectForSession(item.session, projects)
            project?.id?.takeIf { it.isNotBlank() } ?: "directory:${normalizedPath(item.session.directory)}"
        }
        .map { (key, items) ->
            val sorted = sortSessionItems(items)
            val project = projectForSession(sorted.first().session, projects)
            val directory = normalizedPath(
                project?.worktree?.takeIf { it.isNotBlank() }
                    ?: project?.path?.takeIf { it.isNotBlank() }
                    ?: sorted.first().session.directory,
            )
            ProjectSessionGroup(
                projectId = project?.id ?: key,
                projectName = project?.displayName
                    ?: directory.substringAfterLast('/').ifEmpty { "/" },
                directory = directory,
                sessions = sorted,
                sessionDirLabels = sorted.associate { it.session.id to displayPath(it.session.directory) },
            )
        }
        .sortedWith(
            compareByDescending<ProjectSessionGroup> { group ->
                group.sessions.maxOfOrNull { it.session.time.updated } ?: 0
            }.thenBy { it.projectName.lowercase() }
        )
}

/** Normalize a remote path: unify separators, trim trailing slash, default to root. */
private fun normalizedPath(path: String): String =
    path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }

/** Canonical project root: worktree if present, otherwise legacy path. */
private fun projectRoot(project: Project): String =
    normalizedPath(project.worktree.ifBlank { project.path })
