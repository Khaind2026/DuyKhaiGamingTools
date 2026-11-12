package com.duykhai.gaming

import org.junit.Assert.*
import org.junit.Test

class SystemColorControlTest {
    @Test fun saturationClampsToSafeSurfaceFlingerRange() {
        assertEquals(0f, SystemColorControl.saturationFactor(-25), 0.001f)
        assertEquals(1f, SystemColorControl.saturationFactor(100), 0.001f)
        assertEquals(1.5f, SystemColorControl.saturationFactor(150), 0.001f)
        assertEquals(2f, SystemColorControl.saturationFactor(275), 0.001f)
    }

    @Test fun onlySuccessfulBinderParcelIsAccepted() {
        assertTrue(SystemColorControl.acceptedBinderReply("Result: Parcel(00000000 '....')"))
        assertFalse(SystemColorControl.acceptedBinderReply("Result: Parcel(ffffffff '....')"))
        assertFalse(SystemColorControl.acceptedBinderReply("Permission denied"))
        assertFalse(SystemColorControl.acceptedBinderReply(""))
    }
}
