package dev.leonardo.octether.ui.screens.viewer

import dev.leonardo.octether.domain.model.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnnotationManagerTest {

    private lateinit var manager: AnnotationManager

    private val sampleContent = """
        package dev.leonardo.octether

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)
            }
        }
    """.trimIndent()

    @Before
    fun setup() {
        manager = AnnotationManager(sampleContent)
    }

    @Test
    fun `add first annotation gets index 0`() {
        val pos = sampleContent.indexOf("import android.os.Bundle")
        val ann = manager.add("import android.os.Bundle", pos, pos + 25, "Should use alias")
        assertEquals(0, ann.index)
        assertEquals(1, manager.getAll().size)
    }

    @Test
    fun `add second annotation gets index 1`() {
        val p1 = sampleContent.indexOf("import android.os.Bundle")
        manager.add("import android.os.Bundle", p1, p1 + 25, "note1")
        val p2 = sampleContent.indexOf("setContentView")
        val ann2 = manager.add("setContentView", p2, p2 + 13, "note2")
        assertEquals(1, ann2.index)
    }

    @Test
    fun `delete middle annotation re-numbers remaining consecutively`() {
        manager.add("t1", 0, 5, "n1")
        manager.add("t2", 10, 15, "n2")
        manager.add("t3", 20, 25, "n3")
        val firstId = manager.getAll()[0].id
        manager.delete(firstId)
        val remaining = manager.getAll()
        assertEquals(2, remaining.size)
        assertEquals(0, remaining[0].index)
        assertEquals(1, remaining[1].index)
    }

    @Test
    fun `delete last annotation does not re-number others`() {
        manager.add("t1", 0, 5, "n1")
        manager.add("t2", 10, 15, "n2")
        manager.delete(manager.getAll()[1].id)
        val remaining = manager.getAll()
        assertEquals(1, remaining.size)
        assertEquals(0, remaining[0].index)
    }

    @Test
    fun `delete only annotation results in empty list`() {
        manager.add("t1", 0, 5, "n1")
        manager.delete(manager.getAll()[0].id)
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `add after delete gets correct index`() {
        manager.add("t1", 0, 5, "n1")
        manager.add("t2", 10, 15, "n2")
        manager.delete(manager.getAll()[0].id)
        val ann3 = manager.add("t3", 20, 25, "n3")
        assertEquals(1, ann3.index)
    }

    @Test
    fun `update changes note only`() {
        val ann = manager.add("t1", 0, 5, "original")
        manager.update(ann.id, "updated")
        val updated = manager.getAll().find { it.id == ann.id }!!
        assertEquals("updated", updated.note)
        assertEquals(ann.startChar, updated.startChar)
    }

    @Test
    fun `getForLine returns intersecting annotations`() {
        val importStart = sampleContent.indexOf("import androidx")
        manager.add("import androidx", importStart, importStart + 14, "note")
        val result = manager.getForLine(3) // 0-based line 4
        assertEquals(1, result.size)
    }

    @Test
    fun `getForLine returns empty for non-intersecting`() {
        manager.add("package", 0, 7, "note")
        assertTrue(manager.getForLine(5).isEmpty())
    }

    @Test
    fun `clear removes all`() {
        manager.add("t1", 0, 2, "n1")
        manager.add("t2", 5, 7, "n2")
        manager.clear()
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `add computes correct line col from offsets`() {
        val importStart = sampleContent.indexOf("import android.os.Bundle")
        val ann = manager.add("import android.os.Bundle", importStart, importStart + 25, "note")
        assertEquals(3, ann.startLine)
        assertEquals(1, ann.startCol)
    }

    @Test
    fun `overlapping annotations both returned by getForLine`() {
        val pos1 = sampleContent.indexOf("class MainActivity")
        val pos2 = sampleContent.indexOf("MainActivity")
        manager.add("class MainActivity", pos1, pos1 + 17, "n1")
        manager.add("MainActivity", pos2, pos2 + 12, "n2")
        assertEquals(2, manager.getForLine(5).size) // 0-based line 6
    }
}
