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
package com.auto1.pantera.db;

import com.auto1.pantera.scheduling.ArtifactEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DbConsumer} must never re-queue an unwritable event forever, and must
 * drop events a repository or path delete has superseded.
 *
 * <p>Runs against a recording in-process JDBC fake: no database.</p>
 *
 * @since 2.2.9
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class DbConsumerPoisonEventTest {

    /**
     * Audit logger name.
     */
    private static final String AUDIT = "artifact.audit";

    /**
     * Capturing appender name.
     */
    private static final String CAP = "DbConsumerPoisonEventTestAudit";

    /**
     * Upserts executed, per artifact name.
     */
    private final Map<String, AtomicInteger> executed = new ConcurrentHashMap<>();

    @TempDir
    private Path deadLetter;

    @Test
    void nulByteEventIsDeadLetteredAfterOneAttempt() {
        final IndexWriteFence fence = new IndexWriteFence(System::currentTimeMillis);
        final DbConsumer consumer = this.consumer(fence);
        consumer.accept(DbConsumerPoisonEventTest.insert("repo", "a\0b"));
        consumer.accept(DbConsumerPoisonEventTest.insert("repo", "good"));
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(this::deadLettered);
        this.drain(consumer, "sentinel-1");
        this.drain(consumer, "sentinel-2");
        MatcherAssert.assertThat(
            "a data exception cannot be fixed by a retry: exactly one attempt",
            this.count("a\0b"), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the other event of the batch is written",
            this.count("good"), new IsEqual<>(1)
        );
    }

    @Test
    void transientFailureIsRetriedABoundedNumberOfTimes() {
        final IndexWriteFence fence = new IndexWriteFence(System::currentTimeMillis);
        final DbConsumer consumer = this.consumer(fence);
        consumer.accept(DbConsumerPoisonEventTest.insert("repo", "flaky"));
        Awaitility.await().atMost(40, TimeUnit.SECONDS).until(this::deadLettered);
        this.drain(consumer, "sentinel-1");
        this.drain(consumer, "sentinel-2");
        MatcherAssert.assertThat(this.count("flaky"), new IsEqual<>(3));
    }

    @Test
    void eventsQueuedBeforeARepositoryDeleteAreDropped() {
        final IndexWriteFence fence = new IndexWriteFence(System::currentTimeMillis);
        final DbConsumer consumer = this.consumer(fence);
        final ArtifactEvent before = DbConsumerPoisonEventTest.insert("gone", "old");
        final ArtifactEvent other = DbConsumerPoisonEventTest.insert("kept", "sibling");
        fence.fenceRepository("gone");
        final ArtifactEvent after = DbConsumerPoisonEventTest.insert("gone", "recreated");
        consumer.accept(before);
        consumer.accept(other);
        consumer.accept(after);
        this.drain(consumer, "sentinel");
        MatcherAssert.assertThat(
            "an upload queued before the delete must not be indexed",
            this.count("old"), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "another repository is unaffected",
            this.count("sibling"), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "an upload after the delete (re-created repository) is indexed",
            this.count("recreated"), new IsEqual<>(1)
        );
    }

    @Test
    void eventsQueuedBeforeAPathDeleteAreDropped() {
        final IndexWriteFence fence = new IndexWriteFence(System::currentTimeMillis);
        final DbConsumer consumer = this.consumer(fence);
        final ArtifactEvent inside = DbConsumerPoisonEventTest.insert("repo", "lib/a.txt");
        final ArtifactEvent byPrefix = new ArtifactEvent(
            "file", "repo", "alice", "lib.b.txt", "UNKNOWN", 1L, 1L, null, "lib"
        );
        final ArtifactEvent sibling = DbConsumerPoisonEventTest.insert("repo", "lib-extra/b.txt");
        fence.fencePath("repo", "/lib/");
        consumer.accept(inside);
        consumer.accept(byPrefix);
        consumer.accept(sibling);
        this.drain(consumer, "sentinel");
        MatcherAssert.assertThat(
            "an artifact under the deleted path must not be indexed",
            this.count("lib/a.txt"), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "an artifact whose path prefix is the deleted path must not be indexed",
            this.count("lib.b.txt"), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "a sibling sharing the string prefix is indexed",
            this.count("lib-extra/b.txt"), new IsEqual<>(1)
        );
    }

    @Test
    void fencedUploadIsStillAuditedAsPublished() {
        final AuditCapture capture = new AuditCapture();
        capture.start();
        Configurator.setLevel(DbConsumerPoisonEventTest.AUDIT, Level.INFO);
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().addAppender(capture);
        ctx.getConfiguration().getLoggerConfig(DbConsumerPoisonEventTest.AUDIT)
            .addAppender(capture, null, null);
        ctx.updateLoggers();
        try {
            final IndexWriteFence fence = new IndexWriteFence(System::currentTimeMillis);
            final DbConsumer consumer = this.consumer(fence);
            final ArtifactEvent before = DbConsumerPoisonEventTest.insert("gone", "fenced-up");
            fence.fenceRepository("gone");
            consumer.accept(before);
            this.drain(consumer, "sentinel");
            MatcherAssert.assertThat(
                "the fenced upload must not be indexed",
                this.count("fenced-up"), new IsEqual<>(0)
            );
            MatcherAssert.assertThat(
                "the fenced upload happened: it still emits artifact_publish",
                capture.published("fenced-up"), new IsEqual<>(1L)
            );
        } finally {
            ctx.getConfiguration().getLoggerConfig(DbConsumerPoisonEventTest.AUDIT)
                .removeAppender(DbConsumerPoisonEventTest.CAP);
            capture.stop();
            ctx.updateLoggers();
        }
    }

    /**
     * A consumer that is subscribed: the consumer subscribes asynchronously,
     * and an event accepted before that is not seen. Probe until one lands.
     * @param fence Fence
     * @return Ready consumer
     */
    private DbConsumer consumer(final IndexWriteFence fence) {
        final DbConsumer consumer = new DbConsumer(
            this.dataSource(), 1, 50, Optional.empty(), fence, this.deadLetter
        );
        final AtomicInteger probes = new AtomicInteger();
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .until(
                () -> {
                    consumer.accept(
                        DbConsumerPoisonEventTest.insert(
                            "probe", "probe-" + probes.incrementAndGet()
                        )
                    );
                    return this.executed.keySet().stream()
                        .anyMatch(name -> name.startsWith("probe-"));
                }
            );
        return consumer;
    }

    /**
     * Send a sentinel and wait until it is written: every event accepted
     * before it has been through a batch by then.
     * @param consumer Consumer
     * @param name Sentinel name
     */
    private void drain(final DbConsumer consumer, final String name) {
        consumer.accept(DbConsumerPoisonEventTest.insert("sentinel", name));
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> this.count(name) >= 1);
    }

    private int count(final String name) {
        final AtomicInteger cnt = this.executed.get(name);
        return cnt == null ? 0 : cnt.get();
    }

    private boolean deadLettered() throws Exception {
        if (!Files.isDirectory(this.deadLetter)) {
            return false;
        }
        try (Stream<Path> files = Files.list(this.deadLetter)) {
            return files.findAny().isPresent();
        }
    }

    private static ArtifactEvent insert(final String repo, final String name) {
        return new ArtifactEvent("file", repo, "alice", name, "UNKNOWN", 1L, 1L);
    }

    /**
     * JDBC fake: an upsert of a name with a NUL byte fails with SQLSTATE
     * 22021, of "flaky" with 40001; every other upsert succeeds. Each
     * attempt is counted per name.
     * @return Data source
     */
    private DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if ("getConnection".equals(method.getName())) {
                    return this.connection();
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                final String name = method.getName();
                final Object res;
                if ("prepareStatement".equals(name)) {
                    res = this.statement((String) args[0]);
                } else if ("setSavepoint".equals(name)) {
                    res = Proxy.newProxyInstance(
                        Savepoint.class.getClassLoader(),
                        new Class<?>[]{Savepoint.class},
                        (sp, spm, spargs) -> null
                    );
                } else if ("isClosed".equals(name)) {
                    res = false;
                } else {
                    res = null;
                }
                return res;
            }
        );
    }

    private PreparedStatement statement(final String sql) {
        final String[] params = new String[16];
        final boolean upsert = sql.startsWith("INSERT INTO artifacts ");
        return (PreparedStatement) Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                final String name = method.getName();
                if ("setString".equals(name)) {
                    params[(Integer) args[0]] = (String) args[1];
                } else if ("execute".equals(name) && upsert) {
                    final String artifact = params[3];
                    this.executed.computeIfAbsent(artifact, key -> new AtomicInteger())
                        .incrementAndGet();
                    if (artifact.indexOf('\0') >= 0) {
                        throw new SQLException(
                            "invalid byte sequence for encoding \"UTF8\": 0x00", "22021"
                        );
                    }
                    if ("flaky".equals(artifact)) {
                        throw new SQLException("could not serialize access", "40001");
                    }
                    return true;
                } else if ("execute".equals(name)) {
                    return true;
                }
                return null;
            }
        );
    }

    /**
     * Appender recording audit events; written from the consumer thread.
     */
    private static final class AuditCapture extends AbstractAppender {

        /**
         * Captured messages.
         */
        private final List<Message> messages = new CopyOnWriteArrayList<>();

        AuditCapture() {
            super(DbConsumerPoisonEventTest.CAP, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            if (DbConsumerPoisonEventTest.AUDIT.equals(event.getLoggerName())) {
                this.messages.add(event.toImmutable().getMessage());
            }
        }

        /**
         * Count artifact_publish records of a package.
         * @param name Package name
         * @return Record count
         */
        long published(final String name) {
            return this.messages.stream()
                .filter(MapMessage.class::isInstance)
                .map(MapMessage.class::cast)
                .filter(msg -> "artifact_publish".equals(msg.get("event.action")))
                .filter(msg -> name.equals(msg.get("package.name")))
                .count();
        }
    }
}
