/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProgressReporter}.
 *
 * @since 1.20.13
 */
final class ProgressReporterTest {

    @Test
    void incrementIncrementsScannedCount() {
        final ProgressReporter reporter = new ProgressReporter(1000);
        reporter.increment();
        reporter.increment();
        reporter.increment();
        MatcherAssert.assertThat(
            "Scanned count should reflect three increments",
            reporter.getScanned(),
            Matchers.is(3L)
        );
    }

    @Test
    void getScannedReturnsZeroInitially() {
        final ProgressReporter reporter = new ProgressReporter(100);
        MatcherAssert.assertThat(
            "Initial scanned count should be zero",
            reporter.getScanned(),
            Matchers.is(0L)
        );
    }

    @Test
    void recordErrorIncrementsErrorCount() {
        final ProgressReporter reporter = new ProgressReporter(100);
        reporter.recordError();
        reporter.recordError();
        MatcherAssert.assertThat(
            "Error count should reflect two errors",
            reporter.getErrors(),
            Matchers.is(2L)
        );
    }

    @Test
    void errorsStartAtZero() {
        final ProgressReporter reporter = new ProgressReporter(100);
        MatcherAssert.assertThat(
            "Initial error count should be zero",
            reporter.getErrors(),
            Matchers.is(0L)
        );
    }

    @Test
    void incrementAndErrorsAreIndependent() {
        final ProgressReporter reporter = new ProgressReporter(100);
        reporter.increment();
        reporter.increment();
        reporter.recordError();
        MatcherAssert.assertThat(
            "Scanned should be 2",
            reporter.getScanned(),
            Matchers.is(2L)
        );
        MatcherAssert.assertThat(
            "Errors should be 1",
            reporter.getErrors(),
            Matchers.is(1L)
        );
    }

    @Test
    void printFinalSummaryDoesNotThrow() {
        final ProgressReporter reporter = new ProgressReporter(10);
        for (int idx = 0; idx < 25; idx++) {
            reporter.increment();
        }
        reporter.recordError();
        reporter.printFinalSummary();
        MatcherAssert.assertThat(
            "Scanned should be 25 after summary",
            reporter.getScanned(),
            Matchers.is(25L)
        );
    }
}
