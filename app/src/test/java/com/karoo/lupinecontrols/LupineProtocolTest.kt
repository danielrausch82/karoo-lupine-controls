package com.karoo.lupinecontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LupineProtocolTest {
    @Test
    fun `initialization frames match captured pairing sequence`() {
        assertEquals(
            listOf(
                LupineBleProfile.INIT_ENABLE_UPLINK,
                LupineBleProfile.INIT_SESSION,
            ),
            LupineProtocol.buildInitializationFrames(),
        )
    }

    @Test
    fun `status request uses captured session frame`() {
        assertEquals(LupineBleProfile.INIT_SESSION, LupineProtocol.buildStatusRequest())
    }

    @Test
    fun `captured off snapshot parses as off`() {
        val snapshot = LupineProtocol.parseStatusSnapshot(LupineBleProfile.KNOWN_OFF_STATUS_SNAPSHOT)

        assertNotNull(snapshot)
        assertEquals(LupineLampOutputTarget.OFF, snapshot?.outputTarget)
        assertFalse(snapshot?.isEco ?: true)
        assertEquals(LupineBleProfile.KNOWN_OFF_STATUS_SNAPSHOT, snapshot?.rawHex)
    }

    @Test
    fun `non lupine frame is ignored`() {
        assertNull(LupineProtocol.parseStatusSnapshot("AA010203"))
    }

    @Test
    fun `non zero status snapshot remains unknown until decrypted capture proves mapping`() {
        val snapshot = LupineProtocol.parseStatusSnapshot("4201020304")

        assertNotNull(snapshot)
        assertEquals(LupineLampOutputTarget.UNKNOWN, snapshot?.outputTarget)
        assertFalse(snapshot?.isEco ?: true)
    }
}