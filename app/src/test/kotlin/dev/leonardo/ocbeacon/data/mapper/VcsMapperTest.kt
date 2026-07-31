package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.FileDiffDto
import dev.leonardo.ocbeacon.data.dto.response.VcsBranchDto
import dev.leonardo.ocbeacon.data.dto.response.VcsChangeDto
import dev.leonardo.ocbeacon.domain.model.VcsBranchInfo
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class VcsMapperTest {

    @Test
    fun `VcsChangeDto status=added maps to VcsStatus ADDED`() {
        val dto = VcsChangeDto(
            file = "data/mapper/VcsMapper.kt",
            additions = 45,
            deletions = 2,
            status = "added"
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals(VcsChange(
            file = "data/mapper/VcsMapper.kt",
            additions = 45,
            deletions = 2,
            status = VcsStatus.ADDED
        ), result)
    }

    @Test
    fun `VcsChangeDto status=deleted maps to VcsStatus DELETED`() {
        val dto = VcsChangeDto(
            file = "docs/legacy/README.md",
            deletions = 120,
            status = "deleted"
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals(VcsStatus.DELETED, result.status)
    }

    @Test
    fun `VcsChangeDto status=modified maps to VcsStatus MODIFIED`() {
        val dto = VcsChangeDto(
            file = "app/src/main/kotlin/dev/minios/ocremote/ui/theme/Theme.kt",
            additions = 12,
            deletions = 8,
            status = "modified"
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals(VcsStatus.MODIFIED, result.status)
    }

    @Test
    fun `VcsChangeDto status=null falls back to MODIFIED`() {
        val dto = VcsChangeDto(
            file = "gradle.properties",
            status = null
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals(VcsStatus.MODIFIED, result.status)
    }

    @Test
    fun `VcsBranchDto maps to VcsBranchInfo preserving nulls`() {
        val dto = VcsBranchDto(
            branch = "feat/workspace-phase1",
            defaultBranch = "master"
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals(VcsBranchInfo(
            branch = "feat/workspace-phase1",
            defaultBranch = "master"
        ), result)
    }

    @Test
    fun `FileDiffDto maps to VcsFileDiff with patch preserved`() {
        val patch = "@@ -1,3 +1,4 @@\n package dev.leonardo.ocbeacon.data.mapper\n+\n+import android.util.Log"
        val dto = FileDiffDto(
            file = "data/mapper/FileMapper.kt",
            patch = patch,
            additions = 2,
            deletions = 0,
            status = "modified"
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals(VcsFileDiff(
            file = "data/mapper/FileMapper.kt",
            patch = patch,
            additions = 2,
            deletions = 0,
            status = VcsStatus.MODIFIED
        ), result)
    }

    @Test
    fun `FileDiffDto file=null maps to empty string`() {
        val dto = FileDiffDto(
            file = null,
            patch = "@@ -0,0 +1 @@\n+new file",
            additions = 1,
            deletions = 0,
            status = "added"
        )
        val result = VcsMapper.toDomain(dto)
        assertEquals("", result.file)
    }
}
