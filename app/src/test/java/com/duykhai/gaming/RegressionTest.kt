package com.duykhai.gaming

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RegressionTest {
    @Test fun highFpsSelectionsRoundTrip() {
        assertEquals(144, FpsOptions.values[6])
        assertEquals(165, FpsOptions.values[7])
        for (fps in FpsOptions.values) assertEquals(fps, FpsOptions.values[FpsOptions.values.indexOf(fps)])
    }

    @Test fun cpuProfilesRespectAvailableFrequenciesAndReportReadback() {
        val root = Files.createTempDirectory("cpu-policies").toFile()
        try {
            val policy = root.resolve("policy0").apply { mkdir() }
            policy.resolve("cpuinfo_max_freq").writeText("3000000")
            policy.resolve("cpuinfo_min_freq").writeText("1000000")
            policy.resolve("scaling_min_freq").writeText("1000000")
            policy.resolve("scaling_max_freq").writeText("3000000")
            policy.resolve("scaling_available_frequencies").writeText("1000000 1800000 2400000 3000000")
            for ((mode, expected) in listOf(1 to "1800000", 2 to "2400000", 3 to "3000000")) {
                val script = CpuProfiles.command(mode).replace("/sys/devices/system/cpu/cpufreq", root.absolutePath)
                val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.waitFor())
                assertEquals(expected, policy.resolve("scaling_max_freq").readText().trim())
                assertTrue(output.contains("actual=$expected"))
                assertEquals(if (mode == 3) "1800000" else "1000000", policy.resolve("scaling_min_freq").readText().trim())
            }
        } finally { root.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCpuMode() { CpuProfiles.command(4) }

    @Test fun processDrainsLargeOutputWithoutDeadlock() {
        val result = ShellProcess.execute(listOf("sh", "-c", "i=0; while [ \$i -lt 5000 ]; do echo abcdefghijklmnopqrstuvwxyz; i=\$((i+1)); done"))
        assertEquals("0", result[0])
        assertEquals(16000, result[1].length)
    }
}
