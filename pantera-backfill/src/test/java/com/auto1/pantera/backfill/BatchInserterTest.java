/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BatchInserter}.
 *
 * <p>These tests exercise dry-run counting, flush-threshold logic, and
 * close-flushes-remaining behavior. Full database integration tests
 * (PostgreSQL upsert, parameter binding, error fall-back) are deferred
 * to Task 12.</p>
 *
 * @since 1.20.13
 */
final class BatchInserterTest {

    /**
     * In dry-run mode, records are counted but nothing is written to the
     * database. The {@code insertedCount} reflects the number of records
     * that <em>would</em> have been inserted.
     */
    @Test
    void dryRunCountsWithoutDbInteraction() {
        try (BatchInserter inserter = new BatchInserter(null, 100, true)) {
            for (int idx = 0; idx < 5; idx++) {
                inserter.accept(sampleRecord(idx));
            }
            inserter.flush();
            MatcherAssert.assertThat(
                "Dry-run should count all accepted records",
                inserter.getInsertedCount(),
                Matchers.is(5L)
            );
            MatcherAssert.assertThat(
                "Dry-run should have zero skipped",
                inserter.getSkippedCount(),
                Matchers.is(0L)
            );
        }
    }

    /**
     * Verify that dry-run auto-flushes when the buffer reaches batchSize.
     */
    @Test
    void dryRunAutoFlushesAtBatchSize() {
        try (BatchInserter inserter = new BatchInserter(null, 3, true)) {
            inserter.accept(sampleRecord(1));
            inserter.accept(sampleRecord(2));
            MatcherAssert.assertThat(
                "Before reaching batchSize, insertedCount should be 0",
                inserter.getInsertedCount(),
                Matchers.is(0L)
            );
            inserter.accept(sampleRecord(3));
            MatcherAssert.assertThat(
                "After reaching batchSize, auto-flush should have counted 3",
                inserter.getInsertedCount(),
                Matchers.is(3L)
            );
        }
    }

    /**
     * Verify that close() flushes remaining records that haven't reached
     * batchSize yet.
     */
    @Test
    void closeFlushesRemainingRecords() {
        final BatchInserter inserter = new BatchInserter(null, 100, true);
        inserter.accept(sampleRecord(1));
        inserter.accept(sampleRecord(2));
        MatcherAssert.assertThat(
            "Before close, records should still be buffered",
            inserter.getInsertedCount(),
            Matchers.is(0L)
        );
        inserter.close();
        MatcherAssert.assertThat(
            "After close, remaining records should be flushed",
            inserter.getInsertedCount(),
            Matchers.is(2L)
        );
    }

    /**
     * Verify that multiple flushes accumulate the inserted count.
     */
    @Test
    void multipleFlushesAccumulateCount() {
        try (BatchInserter inserter = new BatchInserter(null, 2, true)) {
            inserter.accept(sampleRecord(1));
            inserter.accept(sampleRecord(2));
            MatcherAssert.assertThat(
                "First flush should count 2",
                inserter.getInsertedCount(),
                Matchers.is(2L)
            );
            inserter.accept(sampleRecord(3));
            inserter.accept(sampleRecord(4));
            MatcherAssert.assertThat(
                "Second flush should bring total to 4",
                inserter.getInsertedCount(),
                Matchers.is(4L)
            );
        }
    }

    /**
     * Verify that flushing an empty buffer does nothing.
     */
    @Test
    void flushEmptyBufferIsNoop() {
        try (BatchInserter inserter = new BatchInserter(null, 10, true)) {
            inserter.flush();
            MatcherAssert.assertThat(
                "Flushing empty buffer should leave count at 0",
                inserter.getInsertedCount(),
                Matchers.is(0L)
            );
        }
    }

    /**
     * Verify that in dry-run mode, DataSource is never touched (null is
     * safe).
     */
    @Test
    void dryRunAcceptsNullDataSource() {
        try (BatchInserter inserter = new BatchInserter(null, 5, true)) {
            for (int idx = 0; idx < 12; idx++) {
                inserter.accept(sampleRecord(idx));
            }
        }
    }

    /**
     * Verify counters start at zero.
     */
    @Test
    void countersStartAtZero() {
        try (BatchInserter inserter = new BatchInserter(null, 10, true)) {
            MatcherAssert.assertThat(
                "Initial insertedCount should be 0",
                inserter.getInsertedCount(),
                Matchers.is(0L)
            );
            MatcherAssert.assertThat(
                "Initial skippedCount should be 0",
                inserter.getSkippedCount(),
                Matchers.is(0L)
            );
        }
    }

    /**
     * Create a sample ArtifactRecord for testing.
     *
     * @param idx Unique index to distinguish records
     * @return Sample record
     */
    private static ArtifactRecord sampleRecord(final int idx) {
        return new ArtifactRecord(
            "maven", "repo", "art-" + idx, "1.0." + idx,
            1024L, 1700000000L + idx, null, "system", null
        );
    }
}
