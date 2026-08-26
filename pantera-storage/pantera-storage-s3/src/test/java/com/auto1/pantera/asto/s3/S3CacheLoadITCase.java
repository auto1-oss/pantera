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
package com.auto1.pantera.asto.s3;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.number.OrderingComparison;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * WS1 release-gate load test (spec {@code WS1-storage-for-scale.md} §5
 * acceptance #8): the index-accelerated S3 cache ({@code cache.mode: index},
 * the real production {@code CachedBlobStorage} → {@code MeteredBlobStore} →
 * {@code S3Storage} stack built by {@link S3StorageFactory}) must sustain
 * <strong>≥1000 req/s reads AND ≥1000 req/s writes</strong> against a real
 * S3-compatible object store (a MinIO container).
 *
 * <p>This is deliberately an {@code *ITCase} (failsafe / {@code -Pitcase}),
 * not a unit test: it is the one place the project measures wall-clock
 * throughput, exactly as CLAUDE.md's testing doctrine carves out for the load
 * gate. The throughput floor is a conservative order-of-magnitude gate on the
 * WS1 design, not a micro-benchmark: reads are served from the local disk tier
 * via the in-memory index with zero blob-store round trips (proven
 * per-operation by {@code CachedBlobStorageTest}), and writes are acked from
 * local disk with the S3 upload draining asynchronously — so neither hot path
 * is bounded by MinIO round-trip latency. The {@code run-load-test.sh} wrapper
 * under {@code docs/slo/load-test/} runs this and records the numbers.</p>
 *
 * <p>Gated on {@code -Drun.load.test=true} (set by that wrapper): a wall-clock
 * throughput floor is fragile on shared/constrained CI runners, so the regular
 * {@code mvn install} build (which does not set the flag) skips it and it runs
 * only on demand as the release-gate demonstration.</p>
 *
 * @since 2.3.0
 */
@Testcontainers
@EnabledIfSystemProperty(named = "run.load.test", matches = "true")
final class S3CacheLoadITCase {

    /**
     * Read/write floor the WS1 design must clear (spec acceptance #8).
     */
    private static final double TARGET_OPS_PER_SEC = 1000.0;

    /**
     * Hot read set size (distinct keys pre-seeded and then read at random).
     */
    private static final int HOT_SET = 2000;

    /**
     * Object payload size — representative small-artifact/metadata size where
     * per-request overhead, not bandwidth, dominates.
     */
    private static final int PAYLOAD_BYTES = 8 * 1024;

    /**
     * Concurrency for each load phase (virtual threads, Java 21).
     */
    private static final int WORKERS = 64;

    /**
     * Duration of each measured load phase.
     */
    private static final Duration PHASE = Duration.ofSeconds(15);

    private static final String BUCKET = "pantera-bench";

    private static final String ACCESS_KEY = "minioadmin";

    private static final String SECRET_KEY = "minioadmin";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
        DockerImageName.parse(
            System.getProperty("pantera.minio.image", "minio/minio:latest")
        )
    )
        .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
        .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
        .withCommand("server", "/data")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static String endpoint;

    @BeforeAll
    static void createBucket() {
        endpoint = String.format("http://%s:%d", MINIO.getHost(), MINIO.getMappedPort(9000));
        try (S3Client admin = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY))
            )
            .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    static void report() {
        // Results are asserted below and echoed to stdout for run-load-test.sh
        // to capture into docs/slo/load-test/RESULTS.md.
    }

    @Test
    void sustainsAtLeast1000ReadsAndWritesPerSecond() throws Exception {
        // Own the cache dir lifecycle explicitly rather than via @TempDir: the
        // write-back uploader daemon threads keep writing to it after the
        // measured phases end, which races @TempDir's strict post-test
        // cleanup. Close the storage (stops the uploader pool) and delete the
        // tree tolerantly instead.
        final Path cacheDir = Files.createTempDirectory("ws1-load-cache");
        final Storage storage = new S3StorageFactory().newStorage(this.indexCacheConfig(cacheDir));
        try {
            this.measureLoad(storage);
        } finally {
            S3CacheLoadITCase.closeQuietly(storage);
            S3CacheLoadITCase.deleteTreeQuietly(cacheDir);
        }
    }

    private void measureLoad(final Storage storage) throws Exception {
        final byte[] payload = new byte[PAYLOAD_BYTES];
        ThreadLocalRandom.current().nextBytes(payload);

        // Seed the hot read set onto the local disk tier (each save() writes
        // disk + records the index entry; the S3 upload drains in the
        // background). Reads below then hit the index+disk with zero blob-store
        // round trips.
        for (int idx = 0; idx < HOT_SET; idx++) {
            storage.save(hotKey(idx), new Content.From(payload)).get(30, TimeUnit.SECONDS);
        }

        final Result reads = this.runPhase(
            "READ",
            worker -> storage.value(hotKey(ThreadLocalRandom.current().nextInt(HOT_SET)))
                .thenCompose(Content::asBytesFuture)
        );
        final Result writes = this.runPhase(
            "WRITE",
            worker -> storage.save(
                new Key.From("bench", "w", Long.toString(worker.next())),
                new Content.From(payload)
            )
        );

        System.out.printf(
            "%n=== WS1 LOAD RESULT (cache.mode: index → MinIO, %d B objects, %d workers) ===%n",
            PAYLOAD_BYTES, WORKERS
        );
        reads.print("READ ");
        writes.print("WRITE");

        MatcherAssert.assertThat(
            "read throughput must clear the WS1 ≥1000 req/s gate (got " + reads.opsPerSec() + "/s)",
            reads.opsPerSec() >= TARGET_OPS_PER_SEC, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "write throughput must clear the WS1 ≥1000 req/s gate (got " + writes.opsPerSec() + "/s)",
            writes.opsPerSec() >= TARGET_OPS_PER_SEC, new IsEqual<>(true)
        );
        MatcherAssert.assertThat("no read errors under load", reads.errors(), new IsEqual<>(0L));
        MatcherAssert.assertThat("no write errors under load", writes.errors(), new IsEqual<>(0L));
        MatcherAssert.assertThat(
            "read p99 latency stays sub-second at the target rate",
            reads.p99Millis(), OrderingComparison.lessThan(1000L)
        );
    }

    private YamlMapping indexCacheConfig(final Path cacheDir) throws Exception {
        Files.createDirectories(cacheDir);
        return Yaml.createYamlMappingBuilder()
            .add("bucket", BUCKET)
            .add("region", "us-east-1")
            .add("endpoint", endpoint)
            .add("path-style", "true")
            .add("multipart", "false")
            .add(
                "credentials",
                Yaml.createYamlMappingBuilder()
                    .add("type", "basic")
                    .add("accessKeyId", ACCESS_KEY)
                    .add("secretAccessKey", SECRET_KEY)
                    .build()
            )
            .add(
                "cache",
                Yaml.createYamlMappingBuilder()
                    .add("enabled", "true")
                    .add("mode", "index")
                    .add("path", cacheDir.toString())
                    .add("write-through", "false")
                    // Large admission window so a sustained multi-thousand
                    // ops/s write burst measures the local-disk ack throughput
                    // (the WS1 claim) rather than tripping the (correct)
                    // bounded-queue backpressure mid-phase; the uploader pool
                    // keeps draining to MinIO in the background.
                    .add("write-back-queue-capacity", "1000000")
                    .add("write-back-uploader-threads", "16")
                    .build()
            )
            .build();
    }

    private Result runPhase(final String label, final Op op) throws InterruptedException {
        final AtomicBoolean run = new AtomicBoolean(true);
        final AtomicLong ops = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        final CountDownLatch started = new CountDownLatch(WORKERS);
        final CountDownLatch done = new CountDownLatch(WORKERS);
        final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        final long phaseStart;
        try {
            for (int wid = 0; wid < WORKERS; wid++) {
                final Worker worker = new Worker(wid);
                pool.execute(() -> {
                    started.countDown();
                    try {
                        while (run.get()) {
                            final long begin = System.nanoTime();
                            try {
                                op.run(worker).get(30, TimeUnit.SECONDS);
                                latencies.add((System.nanoTime() - begin) / 1_000_000L);
                                ops.incrementAndGet();
                            } catch (final Exception ex) {
                                errors.incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            started.await();
            phaseStart = System.nanoTime();
            Thread.sleep(PHASE.toMillis());
            run.set(false);
            done.await();
        } finally {
            pool.shutdownNow();
        }
        final double elapsedSec = (System.nanoTime() - phaseStart) / 1e9;
        return new Result(label, ops.get(), errors.get(), elapsedSec, new ArrayList<>(latencies));
    }

    private static Key hotKey(final int idx) {
        return new Key.From("bench", "hot", Integer.toString(idx));
    }

    private static void closeQuietly(final Storage storage) {
        if (storage instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (final Exception ignored) {
                // best-effort: shutting the uploader pool down for cleanup
            }
        }
    }

    private static void deleteTreeQuietly(final Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            // walkFileTree (NOT Files.walk) tolerates a file vanishing mid-walk:
            // a write-back daemon can ATOMIC_MOVE/delete a .tmp staging file while
            // we descend, which makes Files.walk's lazy iterator throw an
            // UncheckedIOException (a RuntimeException the old IOException catch
            // missed). visitFileFailed returns CONTINUE so teardown never fails.
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                    final Path file, final java.nio.file.attribute.BasicFileAttributes attrs) {
                    S3CacheLoadITCase.deleteIfExistsQuietly(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(
                    final Path file, final java.io.IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(
                    final Path dir, final java.io.IOException exc) {
                    S3CacheLoadITCase.deleteIfExistsQuietly(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (final java.io.IOException ignored) {
            // Best-effort teardown of a /tmp cache dir; the OS sweep clears the rest.
        }
    }

    private static void deleteIfExistsQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final java.io.IOException ignored) {
            // A write-back daemon may still be writing here; leave the straggler.
        }
    }

    /** A load operation returning a future the driver awaits. */
    @FunctionalInterface
    private interface Op {
        java.util.concurrent.CompletableFuture<?> run(Worker worker);
    }

    /** Per-worker monotonic key source for write uniqueness. */
    private static final class Worker {
        private final int id;
        private long seq;

        Worker(final int id) {
            this.id = id;
        }

        long next() {
            this.seq += 1;
            return ((long) this.id << 40) | this.seq;
        }
    }

    /** Immutable phase result with derived throughput + latency percentiles. */
    private static final class Result {
        private final String label;
        private final long ops;
        private final long errors;
        private final double elapsedSec;
        private final List<Long> latencies;

        Result(
            final String label, final long ops, final long errors,
            final double elapsedSec, final List<Long> latencies
        ) {
            this.label = label;
            this.ops = ops;
            this.errors = errors;
            this.elapsedSec = elapsedSec;
            this.latencies = latencies;
            this.latencies.sort(Long::compareTo);
        }

        double opsPerSec() {
            return this.ops / this.elapsedSec;
        }

        long errors() {
            return this.errors;
        }

        long p99Millis() {
            return this.percentile(99);
        }

        private long percentile(final int pct) {
            if (this.latencies.isEmpty()) {
                return -1;
            }
            final int rank = (int) Math.ceil(pct / 100.0 * this.latencies.size()) - 1;
            return this.latencies.get(Math.max(0, Math.min(rank, this.latencies.size() - 1)));
        }

        void print(final String tag) {
            System.out.printf(
                "%s  %,10.0f ops/s   ops=%d errors=%d   p50=%dms p95=%dms p99=%dms   (%.1fs)%n",
                tag, this.opsPerSec(), this.ops, this.errors,
                this.percentile(50), this.percentile(95), this.percentile(99), this.elapsedSec
            );
        }
    }
}
