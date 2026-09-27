package io.github.xtratter.droidtop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesTest {
    @Test
    fun keepsLastValuesOldestFirst() {
        val s = Series(3)
        s.add(1f); s.add(2f)
        assertArrayEquals(floatArrayOf(1f, 2f), s.toArray(), 0f)
        s.add(3f); s.add(4f); s.add(5f)
        assertEquals(3, s.size)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), s.toArray(), 0f)
    }
}
