package com.duykhai.gaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexPerformanceToolsTest {
    private val display = DexDisplayControl.Output(
        displayId = 2,
        name = "HDMI Test",
        activeWidth = 1920,
        activeHeight = 1080,
        activeHz = 60f,
        supported = listOf(
            DexDisplayControl.OutputMode(1920, 1080, 60f),
            DexDisplayControl.OutputMode(1920, 1080, 119.88f),
            DexDisplayControl.OutputMode(2560, 1440, 120f),
            DexDisplayControl.OutputMode(2560, 1440, 60f)
        )
    )

    @Test fun selectsMatchingNativeResolutionWhenPossible() {
        val result = DexDisplayControl.chooseMode(display, 120)
        assertEquals(1920, result?.width)
        assertEquals(1080, result?.height)
        assertEquals(119.88f, result?.hz ?: 0f, 0.001f)
    }

    @Test fun refusesUnlistedRefreshRate() {
        assertNull(DexDisplayControl.chooseMode(display, 144))
        assertNull(DexDisplayControl.chooseMode(display, 999))
    }

    @Test fun fallsBackToAdvertisedAlternativeResolution() {
        val alternative = display.copy(
            supported = listOf(DexDisplayControl.OutputMode(2560, 1440, 120f))
        )
        assertEquals(2560, DexDisplayControl.chooseMode(alternative, 120)?.width)
    }
}
