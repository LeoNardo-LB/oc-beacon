package dev.leonardo.ocbeacon.ui.screens.viewer

import org.junit.Test
import org.junit.Assert.assertTrue

class RenderHtmlBuilderTest {

    private val darkBg = "#1A1A1A"
    private val darkFg = "#E0E0E0"
    private val lightBg = "#FFFFFF"
    private val lightFg = "#1A1A1A"

    @Test
    fun `CSV build produces table with header row`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "name,age\nAlice,30\nBob,25", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain <table>", html.contains("<table"))
        assertTrue("should contain <th>name</th>", html.contains("<th>name</th>"))
        assertTrue("should contain <th>age</th>", html.contains("<th>age</th>"))
        assertTrue("should contain <td>Alice</td>", html.contains("<td>Alice</td>"))
        assertTrue("should contain <td>30</td>", html.contains("<td>30</td>"))
    }

    @Test
    fun `CSV with TSV uses tab delimiter`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a\tb\n1\t2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue(html.contains("<th>a</th>"))
        assertTrue(html.contains("<th>b</th>"))
        assertTrue(html.contains("<td>1</td>"))
    }

    @Test
    fun `CSV handles quoted fields with commas`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "\"na,me\",val\n\"Ali,ce\",30", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue(html.contains("<th>na,me</th>"))
        assertTrue(html.contains("<td>Ali,ce</td>"))
    }

    @Test
    fun `CSV empty content produces empty table`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should still contain <table>", html.contains("<table"))
    }

    @Test
    fun `JSON build produces pretty-printed pre`() {
        val html = RenderHtmlBuilder.build(FileType.JSON, "{\"b\":2,\"a\":1}", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain <pre>", html.contains("<pre"))
        assertTrue("should contain key a", html.contains("\"a\""))
        assertTrue("should contain key b", html.contains("\"b\""))
        assertTrue("should be indented", html.contains("  "))
    }

    @Test
    fun `JSON array produces pretty-printed pre`() {
        val html = RenderHtmlBuilder.build(FileType.JSON, "[1,2,3]", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue(html.contains("<pre"))
        assertTrue(html.contains("1"))
    }

    @Test
    fun `JSON invalid produces error message`() {
        val html = RenderHtmlBuilder.build(FileType.JSON, "{invalid json}", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain error text", html.contains("Invalid JSON") || html.contains("error") || html.contains("Error"))
    }

    @Test
    fun `SVG build embeds svg content directly`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><circle r=\"50\"/></svg>"
        val html = RenderHtmlBuilder.build(FileType.SVG, svg, isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should embed svg tag", html.contains("<svg"))
        assertTrue("should contain circle", html.contains("circle"))
    }

    @Test
    fun `dark theme produces dark background CSS`() {
        val html = RenderHtmlBuilder.build(FileType.JSON, "{}", isDark = true, bgHex = darkBg, fgHex = darkFg)
        assertTrue("should contain dark bg color", html.contains(darkBg))
    }

    @Test
    fun `light theme produces light background CSS`() {
        val html = RenderHtmlBuilder.build(FileType.JSON, "{}", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain light bg color", html.contains(lightBg))
    }

    @Test
    fun `all HTML contains viewport meta tag`() {
        listOf(FileType.CSV, FileType.JSON, FileType.SVG).forEach { ft ->
            val content = when (ft) {
                FileType.CSV -> "a\n1"
                FileType.JSON -> "{}"
                FileType.SVG -> "<svg/>"
                else -> ""
            }
            val html = RenderHtmlBuilder.build(ft, content, isDark = false, bgHex = lightBg, fgHex = lightFg)
            assertTrue("$ft should have viewport meta", html.contains("viewport"))
        }
    }

    @Test
    fun `CSV build wraps table in scrollable container`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a,b\n1,2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain table-wrapper", html.contains("table-wrapper"))
    }

    @Test
    fun `CSV build adds dynamic cell cap css based on column count`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a,b\n1,2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain column-aware max-width", html.contains("max-width: max(calc((100vw - 32px) / 2), 120px)"))
    }

    @Test
    fun `CSV build enables word breaking in cells`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a,b\n1,2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain overflow-wrap anywhere", html.contains("overflow-wrap: anywhere"))
    }
}
