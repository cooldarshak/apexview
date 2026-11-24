package app.apexview.cast.local

import org.junit.Assert.*
import org.junit.Test

class NativeAudioControlTest {
    @Test fun selectionUsesOnlyCurrentLoadAndBoundedRepresentation() {
        val command = NativeAudioControl.command("active-load", 3)
        assertEquals(3, NativeAudioControl.decode(command, "active-load"))
        assertNull(NativeAudioControl.decode(command, "old-load"))
        assertNull(NativeAudioControl.decode(command.put("index", 6), "active-load"))
        assertEquals("pitwall-audio-0", NativeAudioControl.representationId(0))
        assertThrows(IllegalArgumentException::class.java) { NativeAudioControl.command("active", -1) }
    }
}
