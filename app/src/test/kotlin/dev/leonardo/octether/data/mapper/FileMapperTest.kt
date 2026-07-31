package dev.leonardo.octether.data.mapper

import dev.leonardo.octether.data.dto.response.FileContentDto
import dev.leonardo.octether.data.dto.response.FileNodeDto
import dev.leonardo.octether.domain.model.ContentType
import dev.leonardo.octether.domain.model.FileContent
import dev.leonardo.octether.domain.model.FileNode
import dev.leonardo.octether.domain.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Test

class FileMapperTest {

    @Test
    fun `FileNodeDto type=file maps to FileType FILE`() {
        val dto = FileNodeDto(
            name = "OpenCodeApi.kt",
            path = "data/api/OpenCodeApi.kt",
            type = "file",
            absolute = "/home/opencode/project/data/api/OpenCodeApi.kt",
            ignored = false,
            size = 8192,
            modified = 1718000000L
        )
        val result = FileMapper.toDomain(dto)
        assertEquals(FileNode(
            name = "OpenCodeApi.kt",
            path = "data/api/OpenCodeApi.kt",
            absolute = "/home/opencode/project/data/api/OpenCodeApi.kt",
            type = FileType.FILE,
            ignored = false,
            size = 8192,
            modified = 1718000000L
        ), result)
    }

    @Test
    fun `FileNodeDto type=directory maps to FileType DIRECTORY`() {
        val dto = FileNodeDto(
            name = "mapper",
            path = "data/mapper",
            type = "directory",
            absolute = "/home/opencode/project/data/mapper"
        )
        val result = FileMapper.toDomain(dto)
        assertEquals(FileNode(
            name = "mapper",
            path = "data/mapper",
            absolute = "/home/opencode/project/data/mapper",
            type = FileType.DIRECTORY,
            ignored = false
        ), result)
    }

    @Test
    fun `FileNodeDto unknown type falls back to FILE`() {
        val dto = FileNodeDto(
            name = "Cargo.toml",
            path = "Cargo.toml",
            type = "symlink",
            absolute = "/home/opencode/project/Cargo.toml"
        )
        val result = FileMapper.toDomain(dto)
        assertEquals(FileType.FILE, result.type)
    }

    @Test
    fun `FileNodeDto absolute=null maps to empty string`() {
        val dto = FileNodeDto(
            name = ".gitignore",
            path = ".gitignore",
            type = "file",
            absolute = null
        )
        val result = FileMapper.toDomain(dto)
        assertEquals("", result.absolute)
    }

    @Test
    fun `FileContentDto type=text maps to ContentType TEXT`() {
        val dto = FileContentDto(
            type = "text",
            content = "package dev.leonardo.octether.data.api\n\nimport io.ktor.client.*\n"
        )
        val result = FileMapper.toDomain(dto, "data/api/OpenCodeApi.kt")
        assertEquals(FileContent(
            path = "data/api/OpenCodeApi.kt",
            type = ContentType.TEXT,
            content = "package dev.leonardo.octether.data.api\n\nimport io.ktor.client.*\n"
        ), result)
    }

    @Test
    fun `FileContentDto type=binary maps to ContentType BINARY with mimeType`() {
        val dto = FileContentDto(
            type = "binary",
            content = "",
            mimeType = "image/png"
        )
        val result = FileMapper.toDomain(dto, "assets/icon.png")
        assertEquals(FileContent(
            path = "assets/icon.png",
            type = ContentType.BINARY,
            content = "",
            mimeType = "image/png"
        ), result)
    }
}
