package dev.leonardo.octether.ui.screens.viewer

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.*
import org.junit.Test

class HighlightBuilderTest {

    // ===== rememberLanguage =====

    @Test
    fun `kotlin extensions`() {
        assertEquals(SyntaxLanguage.KOTLIN, HighlightBuilder.rememberLanguage("Main.kt"))
        assertEquals(SyntaxLanguage.KOTLIN, HighlightBuilder.rememberLanguage("build.gradle.kts"))
    }

    @Test
    fun `major language extensions`() {
        assertEquals(SyntaxLanguage.JAVA, HighlightBuilder.rememberLanguage("App.java"))
        assertEquals(SyntaxLanguage.PYTHON, HighlightBuilder.rememberLanguage("script.py"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, HighlightBuilder.rememberLanguage("index.js"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, HighlightBuilder.rememberLanguage("app.tsx"))
        assertEquals(SyntaxLanguage.GO, HighlightBuilder.rememberLanguage("main.go"))
        assertEquals(SyntaxLanguage.RUST, HighlightBuilder.rememberLanguage("lib.rs"))
        assertEquals(SyntaxLanguage.SHELL, HighlightBuilder.rememberLanguage("deploy.sh"))
    }

    @Test
    fun `uppercase extension normalised`() {
        assertEquals(SyntaxLanguage.KOTLIN, HighlightBuilder.rememberLanguage("File.KT"))
        assertEquals(SyntaxLanguage.PYTHON, HighlightBuilder.rememberLanguage("SCRIPT.PY"))
    }

    @Test
    fun `unknown or missing extension returns DEFAULT`() {
        assertEquals(SyntaxLanguage.DEFAULT, HighlightBuilder.rememberLanguage("readme.unknown"))
        assertEquals(SyntaxLanguage.DEFAULT, HighlightBuilder.rememberLanguage("Makefile"))
        assertEquals(SyntaxLanguage.DEFAULT, HighlightBuilder.rememberLanguage(""))
    }

    @Test
    fun `full path with directories`() {
        assertEquals(
            SyntaxLanguage.KOTLIN,
            HighlightBuilder.rememberLanguage("/home/user/projects/App.kt")
        )
    }

    // ===== buildHighlights =====

    @Test
    fun `buildHighlights returns non-null result for kotlin code`() {
        val result = HighlightBuilder.buildHighlights(
            "val x = 42\nfun main() = println(x)",
            SyntaxLanguage.KOTLIN,
            isDark = true
        )
        assertNotNull(result)
    }

    @Test
    fun `buildHighlights works with light theme`() {
        val result = HighlightBuilder.buildHighlights(
            "print('hello')",
            SyntaxLanguage.PYTHON,
            isDark = false
        )
        assertNotNull(result)
    }

    @Test
    fun `buildHighlights handles empty content`() {
        val result = HighlightBuilder.buildHighlights("", SyntaxLanguage.DEFAULT, isDark = true)
        assertNotNull(result)
    }
}
