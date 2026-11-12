package com.duykhai.gaming

import org.junit.Assert.*
import org.junit.Test

class MonitorParsingTest {
    @Test fun parsesCpuClustersAndCpuThermalOnly() {
        val input = """
            CLK|policy4|2112000
            CLK|policy0|576000
            TEMP|battery|45000
            TEMP|cpu-therm|51500
            TEMP|gpu-therm|89000
        """.trimIndent()
        val (clocks, temp) = PerformanceMonitor.readSensorData(input)
        assertEquals(listOf("policy0" to 576, "policy4" to 2112), clocks)
        assertEquals(51.5f, temp!!, 0.01f)
    }

    @Test fun estimatesFpsFromRecentlyPresentedFrames() {
        val now = 8_000_000_000L
        val latency = buildString {
            appendLine("16666667")
            for (i in 0..9) {
                val ts = 7_700_000_000L + i * 16_666_667L
                appendLine("0 0 " + ts)
            }
        }
        val fps = PerformanceMonitor.calculateFps(latency, now)
        assertNotNull(fps)
        assertEquals(60f, fps!!, 0.2f)
        assertNull(PerformanceMonitor.calculateFps(latency, 20_000_000_000L))
    }

    @Test fun rejectsUnrealisticTemperatures() {
        assertNull(PerformanceMonitor.normalizeTemperature(150000))
        assertEquals(43.5f, PerformanceMonitor.normalizeTemperature(43500)!!, 0.1f)
    }
}
