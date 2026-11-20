package `in`.melo.presentation.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavLabelFitTest {
    private val base = TextStyle(fontSize = 12.sp, letterSpacing = 0.5.sp)

    @Test
    fun eachStepIsHalfASpSmallerWithTrackingInProportion() {
        val step = smallerLabel(base, base)!!
        assertEquals(11.5f, step.fontSize.value, 0f)
        assertEquals(0.5f * 11.5f / 12f, step.letterSpacing.value, 0.0001f)
        assertEquals(11f, smallerLabel(base, step)!!.fontSize.value, 0f)
    }

    @Test
    fun shrinkingStopsAtTheMinimumSize() {
        var style = base
        var steps = 0
        while (true) {
            style = smallerLabel(base, style) ?: break
            steps++
        }
        assertEquals(6f, style.fontSize.value, 0f)
        assertEquals(12, steps)
    }

    @Test
    fun theSharedSizeNeverEnlargesALabel() {
        assertEquals(base, labelAt(base, Float.MAX_VALUE))
        assertEquals(9f, labelAt(base, 9f).fontSize.value, 0f)
    }

    @Test
    fun aSizeNotInSpIsLeftAlone() {
        assertNull(smallerLabel(TextStyle(fontSize = TextUnit.Unspecified), TextStyle(fontSize = TextUnit.Unspecified)))
    }
}
