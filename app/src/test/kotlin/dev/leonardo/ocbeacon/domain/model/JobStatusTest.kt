package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #284：JobStatus 枚举——fromWire 矩阵 + isTerminal/isActive 派生。 */
class JobStatusTest {

    @Test
    fun `fromWire maps known statuses`() {
        assertEquals(JobStatus.RUNNING, JobStatus.fromWire("running"))
        assertEquals(JobStatus.STOPPING, JobStatus.fromWire("stopping"))
        assertEquals(JobStatus.COMPLETED, JobStatus.fromWire("completed"))
        assertEquals(JobStatus.KILLED, JobStatus.fromWire("killed"))
        assertEquals(JobStatus.FAILED, JobStatus.fromWire("failed"))
    }

    @Test
    fun `fromWire unknown for novel server enum`() {
        assertEquals(JobStatus.UNKNOWN, JobStatus.fromWire("paused"))
        val jv = JobView(id = "1", kind = "bash", label = "l", status = "paused", startedAt = 0L)
        assertEquals(JobStatus.UNKNOWN, jv.statusKind)
        assertFalse(jv.statusKind.isTerminal)
        assertFalse(jv.statusKind.isActive)
    }

    @Test
    fun `derivations`() {
        assertTrue(JobStatus.RUNNING.isActive)
        assertTrue(JobStatus.STOPPING.isActive)
        assertFalse(JobStatus.COMPLETED.isActive)
        assertTrue(JobStatus.COMPLETED.isTerminal)
        assertTrue(JobStatus.KILLED.isTerminal)
        assertTrue(JobStatus.FAILED.isTerminal)
        assertFalse(JobStatus.RUNNING.isTerminal)
    }
}
