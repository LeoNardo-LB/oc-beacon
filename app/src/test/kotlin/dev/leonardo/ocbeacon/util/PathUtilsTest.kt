package dev.leonardo.ocbeacon.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PathUtilsTest {

    @Test
    fun `joinPath joins base and relative with slash`() {
        assertEquals("/home/user/project/src/Foo.kt",
            PathUtils.joinPath("/home/user/project", "src/Foo.kt"))
    }

    @Test
    fun `joinPath handles trailing slash on base`() {
        assertEquals("/home/user/project/src/Foo.kt",
            PathUtils.joinPath("/home/user/project/", "src/Foo.kt"))
    }

    @Test
    fun `joinPath handles leading slash on relative`() {
        assertEquals("/home/user/project/src/Foo.kt",
            PathUtils.joinPath("/home/user/project", "/src/Foo.kt"))
    }

    @Test
    fun `joinPath handles trailing backslash on base`() {
        assertEquals("C:\\Users\\project/src/Foo.kt",
            PathUtils.joinPath("C:\\Users\\project\\", "src/Foo.kt"))
    }

    @Test
    fun `joinPath returns relative when base is blank`() {
        assertEquals("src/Foo.kt",
            PathUtils.joinPath("", "src/Foo.kt"))
    }

    @Test
    fun `joinPath returns base when relative is blank`() {
        assertEquals("/home/user/project",
            PathUtils.joinPath("/home/user/project", ""))
    }
}
