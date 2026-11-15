package com.duykhai.gaming

import org.junit.Assert.assertTrue
import org.junit.Test

class PcmGpuDtsReportTest {
    @Test fun parsesNamedGpuClockAndVoltageProperties() {
        val dts = "qcom,gpu-freq = <800000000>;\nopp-microvolt = <780000>;"
        val result = PcmGpuDtsReport.parse(dts)
        assertTrue(result.contains("qcom,gpu-freq"))
        assertTrue(result.contains("opp-microvolt"))
        assertTrue(result.contains("780000"))
    }

    @Test fun unknownFormatDoesNotClaimVoltageTable() {
        val result = PcmGpuDtsReport.parse("unrelated = <123>;")
        assertTrue(result.contains("Không nhận ra"))
    }

    @Test fun rejectsHugeUntrustedInput() {
        assertTrue(PcmGpuDtsReport.parse("x".repeat(4_000_001)).contains("quá lớn"))
    }
}
