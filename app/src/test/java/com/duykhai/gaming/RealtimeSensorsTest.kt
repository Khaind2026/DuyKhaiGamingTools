package com.duykhai.gaming

import org.junit.Assert.*
import org.junit.Test

class RealtimeSensorsTest {
    @Test fun temperatureConversionsAreExplicitAndBounded() {
        assertEquals(42.7f, RealtimeSensors.normalizeTemperature(42700L)!!, 0.01f)
        assertEquals(52.5f, RealtimeSensors.normalizeTemperature(525L)!!, 0.01f)
        assertEquals(39f, RealtimeSensors.normalizeTemperature(39L)!!, 0.01f)
        assertNull(RealtimeSensors.normalizeTemperature(174000L))
        assertNull(RealtimeSensors.normalizeTemperature(-200L))
    }

    @Test fun cpufreqSupportsKhzAndVendorHzButNotInventedValues() {
        assertEquals(2112, RealtimeSensors.normalizeFrequency(2112000L))
        assertEquals(2112, RealtimeSensors.normalizeFrequency(2112000000L))
        assertNull(RealtimeSensors.normalizeFrequency(100L))
        assertNull(RealtimeSensors.normalizeFrequency(100000000000L))
    }

    @Test fun excludesBatteryAndGpuFromAutoCpuTemperature() {
        val sensors = listOf(
            ThermalProbe("battery", 44f, "thermal_zone7"),
            ThermalProbe("gpu-therm", 95f, "thermal_zone8"),
            ThermalProbe("cpu-therm", 50f, "thermal_zone1"),
            ThermalProbe("cpuss-0", 52f, "thermal_zone2"),
            ThermalProbe("soc-therm", 47f, "thermal_zone3")
        )
        assertEquals("cpuss-0", RealtimeSensors.chooseCpu(sensors, null)?.id)
        assertEquals("battery", RealtimeSensors.chooseCpu(sensors, "battery")?.id)
        assertNull(RealtimeSensors.chooseCpu(listOf(sensors[0], sensors[1]), null))
    }

    @Test fun parsesNamedSensorsAndPrefersHardwareReportedClock() {
        val response = """
            CLK|policy0|576000|scaling_cur_freq
            CLK|policy4|2112000|cpuinfo_cur_freq
            TEMP|cpu-therm|49500|thermal_zone2
            TEMP|battery|95000|thermal_zone5
        """.trimIndent()
        val sampled = RealtimeSensors.parseRows(response, "root")
        assertEquals(2, sampled.frequencies.size)
        assertEquals(2112, sampled.frequencies[1].mhz)
        assertEquals("cpuinfo_cur_freq", sampled.frequencies[1].source)
        assertEquals(49.5f, sampled.selectedCpu!!.celsius, 0.01f)
        assertEquals("thermal_zone2", sampled.selectedCpu!!.source)
    }

    @Test fun noCpuLabelMeansUnknownInsteadOfGuessing() {
        val sampled = RealtimeSensors.parseRows(
            "TEMP|tsens_tz_sensor0|40100|thermal_zone0\nTEMP|battery|38000|thermal_zone3",
            "root")
        assertNull(sampled.selectedCpu)
        assertEquals(40.1f, RealtimeSensors.chooseCpu(sampled.temperatures, "tsens_tz_sensor0")!!.celsius, 0.01f)
    }
}
