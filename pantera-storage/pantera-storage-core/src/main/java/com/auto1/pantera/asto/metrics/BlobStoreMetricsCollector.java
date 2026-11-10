/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.asto.metrics;

/**
 * Dependency-inversion seam for {@code BlobStore}-tier metrics (WS1.6, spec
 * {@code WS1-storage-for-scale.md} &sect;3.G): {@code
 * com.auto1.pantera.asto.blob.MeteredBlobStore} (this module) records every
 * GET/HEAD/PUT/DELETE/LIST call here; the real recorder -- which bridges to
 * {@code MicrometerMetrics} -- lives above this module (in {@code
 * pantera-core}) and installs itself via {@link #setRecorder(Recorder)}
 * exactly like the existing {@link StorageMetricsCollector} does for {@code
 * FileStorage}. Kept separate from {@link StorageMetricsCollector} rather
 * than reusing it: that collector's {@code operation}/{@code result}
 * dimensions are the {@link com.auto1.pantera.asto.Storage} interface's
 * verbs ({@code exists}/{@code value}/{@code save}/...) and a plain
 * success/failure flag, whereas the blob-store tier needs the {@code
 * BlobStore} interface's own verbs ({@code get}/{@code head}/{@code
 * put}/{@code delete}/{@code list}) plus a THREE-way outcome (throttling is
 * a distinct, alertable signal from a hard error) and a bounded {@code
 * backend} dimension (S3 vs a future native GCS/Azure implementation) --
 * mixing these two shapes under one collector would blur both dashboards.
 *
 * @since 2.3.0
 */
public final class BlobStoreMetricsCollector {

    /**
     * Bounded outcome: the call completed without error.
     */
    public static final String OUTCOME_SUCCESS = "success";

    /**
     * Bounded outcome: the backend signalled rate-limiting/throttling
     * (best-effort classification -- see {@code MeteredBlobStore}).
     */
    public static final String OUTCOME_THROTTLED = "throttled";

    /**
     * Bounded outcome: any other failure.
     */
    public static final String OUTCOME_ERROR = "error";

    /**
     * Installed recorder (optional -- {@code null} until a higher module
     * installs one, e.g. at boot).
     */
    private static volatile Recorder recorder;

    private BlobStoreMetricsCollector() {
        // Utility class.
    }

    /**
     * Install the metrics recorder implementation. Called once during
     * application startup, above this module.
     *
     * @param metricsRecorder Recorder implementation, or {@code null} to
     *  disable recording (reverts to a no-op).
     */
    public static void setRecorder(final Recorder metricsRecorder) {
        recorder = metricsRecorder;
    }

    /**
     * Record one {@code BlobStore} call. A no-op if no recorder has been
     * installed (e.g. metrics disabled).
     *
     * @param backend Bounded backend kind (e.g. {@code "s3"}).
     * @param operation Bounded {@code BlobStore} verb (e.g. {@code "get"}).
     * @param outcome One of {@link #OUTCOME_SUCCESS}, {@link
     *  #OUTCOME_THROTTLED}, {@link #OUTCOME_ERROR}.
     * @param durationNs Call duration in nanoseconds.
     */
    public static void record(
        final String backend,
        final String operation,
        final String outcome,
        final long durationNs
    ) {
        final Recorder rec = recorder;
        if (rec != null) {
            rec.recordOperation(backend, operation, outcome, durationNs);
        }
    }

    /**
     * Implemented above this module (in {@code pantera-core}) to bridge into
     * {@code MicrometerMetrics}.
     *
     * @since 2.3.0
     */
    public interface Recorder {

        /**
         * Record one {@code BlobStore} call.
         *
         * @param backend Bounded backend kind.
         * @param operation Bounded {@code BlobStore} verb.
         * @param outcome Bounded outcome.
         * @param durationNs Call duration in nanoseconds.
         */
        void recordOperation(String backend, String operation, String outcome, long durationNs);
    }
}
