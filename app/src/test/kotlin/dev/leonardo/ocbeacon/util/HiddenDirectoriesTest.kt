package dev.leonardo.ocbeacon.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenDirectoriesTest {

    // ===== isHidden =====

    @Test
    fun `empty patterns hide nothing`() {
        assertFalse(HiddenDirectories.isHidden("/tmp/opencode/x", emptyList()))
    }

    @Test
    fun `prefix star hides directory and all descendants`() {
        val patterns = listOf("/tmp/*")
        assertTrue(HiddenDirectories.isHidden("/tmp", patterns))
        assertTrue(HiddenDirectories.isHidden("/tmp/", patterns))
        assertTrue(HiddenDirectories.isHidden("/tmp/opencode", patterns))
        assertTrue(HiddenDirectories.isHidden("/tmp/opencode/ez-omo-bench/run1/task/workspace", patterns))
        assertFalse(HiddenDirectories.isHidden("/home/user/project", patterns))
        assertFalse(HiddenDirectories.isHidden("/tmpfile", patterns))
    }

    @Test
    fun `backslash paths are normalized before matching`() {
        assertTrue(HiddenDirectories.isHidden("C:\\Users\\x\\AppData\\Local\\Temp\\scratch", listOf("C:/Users/x/AppData/Local/Temp/*")))
        assertTrue(HiddenDirectories.isHidden("/tmp/opencode/x", listOf("\\tmp\\*")))
    }

    @Test
    fun `trailing slash on pattern is normalized`() {
        assertTrue(HiddenDirectories.isHidden("/tmp/opencode", listOf("/tmp/*/")))
    }

    @Test
    fun `question mark matches single character`() {
        assertTrue(HiddenDirectories.isHidden("/tmp/a", listOf("/tmp/?")))
        assertFalse(HiddenDirectories.isHidden("/tmp/ab", listOf("/tmp/?")))
    }

    @Test
    fun `exact path pattern matches only itself`() {
        assertTrue(HiddenDirectories.isHidden("/tmp/only-this", listOf("/tmp/only-this")))
        assertFalse(HiddenDirectories.isHidden("/tmp/only-this/child", listOf("/tmp/only-this")))
    }

    @Test
    fun `regex metacharacters in pattern are literal`() {
        // '.' 与 '+' 等元字符不应被当作正则解释
        assertTrue(HiddenDirectories.isHidden("/data/app.name+v2", listOf("/data/app.name+v2")))
        assertFalse(HiddenDirectories.isHidden("/data/appXnameYv2", listOf("/data/app.name+v2")))
    }

    @Test
    fun `comment and blank patterns are ignored`() {
        assertFalse(HiddenDirectories.isHidden("/tmp/x", listOf("#", "#/tmp/*", "  ")))
        assertTrue(HiddenDirectories.isHidden("/tmp/x", listOf("#note", "/tmp/*")))
    }

    @Test
    fun `empty target directory is never hidden`() {
        assertFalse(HiddenDirectories.isHidden("", listOf("/*")))
        assertFalse(HiddenDirectories.isHidden("  ", listOf("*")))
    }

    // ===== normalize =====

    @Test
    fun `normalize keeps root slash`() {
        assertEquals("/", HiddenDirectories.normalize("/"))
        assertEquals("/", HiddenDirectories.normalize("/ "))
    }

    @Test
    fun `normalize strips trailing separators`() {
        assertEquals("/tmp/opencode", HiddenDirectories.normalize("/tmp/opencode/"))
        assertEquals("/tmp/opencode", HiddenDirectories.normalize("/tmp\\opencode\\"))
    }

    // ===== parsePatterns =====

    @Test
    fun `parsePatterns drops blank and comment lines`() {
        assertEquals(
            listOf("/tmp/*", "/var/folders/*"),
            HiddenDirectories.parsePatterns("  /tmp/*  \n\n# comment\n/var/folders/*\n#\n"),
        )
    }

    @Test
    fun `parsePatterns of empty text is empty`() {
        assertTrue(HiddenDirectories.parsePatterns("").isEmpty())
        assertTrue(HiddenDirectories.parsePatterns("\n \n"))
    }
}
