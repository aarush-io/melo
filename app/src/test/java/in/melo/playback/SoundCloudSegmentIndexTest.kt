package `in`.melo.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class SoundCloudSegmentIndexTest {
    private fun box(type: String, payload: Int): ByteArray =
        ByteBuffer.allocate(8 + payload).putInt(8 + payload).put(type.toByteArray(Charsets.US_ASCII)).array()

    private fun types(bytes: ByteArray): List<String> {
        val found = ArrayList<String>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val buffer = ByteBuffer.wrap(bytes, offset, 8)
            val size = buffer.int
            found += String(bytes, offset + 4, 4, Charsets.US_ASCII)
            offset += size
        }
        return found
    }

    @Test fun indexListsEverySegmentWithItsSizeAndDuration() {
        val sizes = longArrayOf(160_000, 158_500, 40_000)
        val durations = longArrayOf(9_984_000, 9_984_000, 2_500_000)
        val index = ByteBuffer.wrap(buildSegmentIndex(sizes, durations))

        assertEquals(40 + 12 * 3, index.int)
        assertEquals("sidx", String(ByteArray(4).also(index::get), Charsets.US_ASCII))
        assertEquals(1 shl 24, index.int) // version 1
        assertEquals(1, index.int) // reference id
        assertEquals(1_000_000, index.int) // timescale
        assertEquals(0L, index.long) // earliest presentation time
        assertEquals(0L, index.long) // first offset
        assertEquals(0, index.short.toInt()) // reserved
        assertEquals(3, index.short.toInt())
        sizes.indices.forEach { i ->
            assertEquals(sizes[i].toInt(), index.int) // reference type 0 (media) and size
            assertEquals(durations[i].toInt(), index.int)
            assertEquals(0x90000000.toInt(), index.int) // starts with a SAP of type 1
        }
        assertEquals(0, index.remaining())
    }

    @Test(expected = IllegalArgumentException::class)
    fun indexNeedsOneDurationPerSegment() {
        buildSegmentIndex(longArrayOf(1, 2), longArrayOf(1))
    }

    @Test fun segmentIndexIsHiddenButMediaIsUntouched() {
        val segment = box("styp", 8) + box("sidx", 44) + box("moof", 16) + box("mdat", 32)
        val original = segment.copyOf()
        neutralizeSegmentIndex(segment)
        assertEquals(listOf("styp", "free", "moof", "mdat"), types(segment))
        // Only the four type bytes of the index change.
        val changed = segment.indices.filter { segment[it] != original[it] }
        assertEquals(listOf(20, 21, 22, 23), changed)
    }

    @Test fun neutralizingStopsAtTheFirstMovieFragment() {
        val segment = box("moof", 16) + box("sidx", 44)
        val original = segment.copyOf()
        neutralizeSegmentIndex(segment)
        assertArrayEquals(original, segment)
    }

    @Test fun truncatedHeadersAreLeftAlone() {
        val window = box("styp", 8).copyOf(12)
        val original = window.copyOf()
        neutralizeSegmentIndex(window)
        assertArrayEquals(original, window)
    }
}
