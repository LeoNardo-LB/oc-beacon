package dev.leonardo.ocbeacon.domain.model

import dev.leonardo.ocbeacon.domain.model.LinkClassifier.classify
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkClassifierTest {

    @Test
    fun `http URL classified as Web`() {
        assertTrue(classify("http://example.com") is LinkTarget.Web)
    }

    @Test
    fun `https URL classified as Web`() {
        assertTrue(classify("https://github.com/repo") is LinkTarget.Web)
    }

    @Test
    fun `ftp URL classified as Web`() {
        assertTrue(classify("ftp://server/file") is LinkTarget.Web)
    }

    @Test
    fun `mailto URL classified as Web`() {
        assertTrue(classify("mailto:test@example.com") is LinkTarget.Web)
    }

    @Test
    fun `Unix absolute path classified as AbsolutePath`() {
        val result = classify("/home/user/project/src/Foo.kt")
        assertTrue(result is LinkTarget.AbsolutePath)
        assertEquals("/home/user/project/src/Foo.kt", (result as LinkTarget.AbsolutePath).path)
    }

    @Test
    fun `Windows absolute path classified as AbsolutePath`() {
        assertTrue(classify("C:\\Users\\project\\src\\Foo.kt") is LinkTarget.AbsolutePath)
    }

    @Test
    fun `Windows absolute path with forward slash classified as AbsolutePath`() {
        assertTrue(classify("D:/projects/app/src/Main.kt") is LinkTarget.AbsolutePath)
    }

    @Test
    fun `relative path classified as RelativePath`() {
        val result = classify("src/Foo.kt")
        assertTrue(result is LinkTarget.RelativePath)
        assertEquals("src/Foo.kt", (result as LinkTarget.RelativePath).path)
    }

    @Test
    fun `relative path with dots classified as RelativePath`() {
        assertTrue(classify("../docs/api.md") is LinkTarget.RelativePath)
    }

    @Test
    fun `relative path with subdirectory classified as RelativePath`() {
        assertTrue(classify("./config/settings.yaml") is LinkTarget.RelativePath)
    }

    @Test
    fun `bare filename classified as RelativePath`() {
        assertTrue(classify("README.md") is LinkTarget.RelativePath)
    }

    @Test
    fun `file URI classified as AbsolutePath`() {
        val result = classify("file:///home/user/project/src/Foo.kt")
        assertTrue(result is LinkTarget.AbsolutePath)
        assertEquals("/home/user/project/src/Foo.kt", (result as LinkTarget.AbsolutePath).path)
    }

    @Test
    fun `uppercase HTTP scheme classified as Web`() {
        assertTrue(classify("HTTP://example.com") is LinkTarget.Web)
    }

    @Test
    fun `mixed case HTTPS scheme classified as Web`() {
        assertTrue(classify("Https://github.com/repo") is LinkTarget.Web)
    }

    @Test
    fun `path with slash is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("src/Main.kt"))
    }

    @Test
    fun `path with backslash is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("app\\build.gradle"))
    }

    @Test
    fun `directory path ending with slash is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("docs/specs/"))
    }

    @Test
    fun `bare filename with extension is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Main.kt"))
    }

    @Test
    fun `gradle filename is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("build.gradle"))
    }

    @Test
    fun `code snippet is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("val x = 1"))
    }

    @Test
    fun `import statement is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("import foo"))
    }

    @Test
    fun `single word is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("TODO"))
    }

    @Test
    fun `boolean literal is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("true"))
    }

    // === Whitelist behavior tests ===

    @Test
    fun `package name is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("com.example.foo"))
    }

    @Test
    fun `multi-segment package name is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("org.springframework.boot"))
    }

    @Test
    fun `version string is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("1.0.0-beta"))
    }

    @Test
    fun `unknown extension is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("unknown.xyz"))
    }

    @Test
    fun `Makefile is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Makefile"))
    }

    @Test
    fun `Dockerfile is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Dockerfile"))
    }

    @Test
    fun `gitignore is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath(".gitignore"))
    }

    @Test
    fun `yaml extension is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("config.yaml"))
    }

    @Test
    fun `toml extension is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Cargo.toml"))
    }

    @Test
    fun `path to unknown file is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("src/unknown"))
    }
}
