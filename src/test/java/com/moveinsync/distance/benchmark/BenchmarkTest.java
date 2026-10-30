package com.moveinsync.distance.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs a small version of the benchmark on every build. The full 10,000 trips run from the command line.
@ExtendWith(OutputCaptureExtension.class)
class BenchmarkTest {

    @Test
    void smallRunFinishesWithNoAuditFailuresAndAgreeingResults(CapturedOutput output) throws Exception {
        BenchmarkRunner.main(new String[]{"300", "0"});
        String text = output.getOut();

        assertTrue(text.contains("Trips where the two runs disagree on the metres: 0"), text);
        assertTrue(text.contains("Different from the healthy run: 0"), text);
        assertTrue(text.contains("Trips failing the audit: 0"), text);
        assertFalse(text.matches("(?s).*Trips failing the audit: [1-9].*"), text);
    }
}
