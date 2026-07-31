package dev.leonardo.ocbeacon.builder

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus

/**
 * Create a Session for tests with sensible defaults.
 */
fun aSession(
    id: String = randomId(),
    title: String = "Test Session",
    status: SessionStatus = SessionStatus.Idle,
    serverId: String = "server-1"
): Session = Session(
    id = id,
    title = title,
    directory = "/test/project",
    time = Session.Time(
        created = System.currentTimeMillis(),
        updated = System.currentTimeMillis()
    )
)
