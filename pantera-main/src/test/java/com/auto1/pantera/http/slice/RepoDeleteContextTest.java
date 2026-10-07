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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.api.v1.StorageMetaCache;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.test.RecordingIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request correlation and refusal auditing of the repository-path delete
 * ({@link RepoDeleteSlice}) and the proxy eviction ({@link ProxyEvictSlice}).
 *
 * <p>The storage used here leaves another request's trace id in the MDC of
 * the thread that completes every storage operation -- what a pooled
 * storage worker does. A log emitted from a continuation therefore carries
 * the deleting request's trace id only when the request context is bound
 * inside that continuation; binding once at the slice's entry is not
 * enough.</p>
 *
 * @since 2.2.10
 */
final class RepoDeleteContextTest {

    /**
     * Trace id of the deleting request.
     */
    private static final String TRACE = "trace-of-the-delete";

    /**
     * Client IP of the deleting request.
     */
    private static final String CLIENT = "10.1.2.3";

    /**
     * Trace id a pooled thread still holds from another request.
     */
    private static final String STALE = "trace-of-another-request";

    /**
     * Repository name.
     */
    private static final String REPO = "my-files";

    /**
     * Captured loggers.
     */
    private List<Capture> captures;

    @BeforeEach
    void setUp() {
        ThreadContext.clearMap();
        this.captures = List.of(
            Capture.of("artifact.audit"),
            Capture.of("com.auto1.pantera.settings"),
            Capture.of("com.auto1.pantera.http.slice")
        );
    }

    @AfterEach
    void tearDown() {
        this.captures.forEach(Capture::close);
        ThreadContext.clearMap();
    }

    @Test
    void fileDeleteLogCarriesTheRequestTrace() {
        final Storage storage = RepoDeleteContextTest.stale("lib/a.bin");
        MatcherAssert.assertThat(
            "the delete answers 204",
            RepoDeleteContextTest.send(RepoDeleteContextTest.hosted(storage), "/lib/a.bin"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the storage-delete log carries the request's trace id and client ip",
            RepoDeleteContextTest.correlation(this.single("artifact_delete", "application")),
            new IsEqual<>(List.of(RepoDeleteContextTest.TRACE, RepoDeleteContextTest.CLIENT))
        );
    }

    @Test
    void folderDeleteLogCarriesTheRequestTrace() {
        final Storage storage = RepoDeleteContextTest.stale("lib/1.0/a.bin", "lib/1.0/b.bin");
        MatcherAssert.assertThat(
            "the delete answers 204",
            RepoDeleteContextTest.send(RepoDeleteContextTest.hosted(storage), "/lib/1.0"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the folder-delete log carries the request's trace id",
            this.single("package_delete", "application").get(EcsMdc.TRACE_ID),
            new IsEqual<>(RepoDeleteContextTest.TRACE)
        );
    }

    @Test
    void failedDeleteLogCarriesTheRequestTrace() {
        final Storage storage = new FailingDelete(RepoDeleteContextTest.stale("lib/a.bin"));
        MatcherAssert.assertThat(
            "a storage failure answers 500",
            RepoDeleteContextTest.send(RepoDeleteContextTest.hosted(storage), "/lib/a.bin"),
            new IsEqual<>(500)
        );
        MatcherAssert.assertThat(
            "the failure log carries the request's trace id",
            this.single("artifact_delete", "application").get(EcsMdc.TRACE_ID),
            new IsEqual<>(RepoDeleteContextTest.TRACE)
        );
    }

    @Test
    void evictionLogCarriesTheRequestTrace() {
        final Storage cache = RepoDeleteContextTest.stale("dir/cached.bin");
        MatcherAssert.assertThat(
            "the eviction answers 204",
            RepoDeleteContextTest.send(RepoDeleteContextTest.proxy(cache), "/dir/cached.bin"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "the eviction log carries the request's trace id and client ip",
            RepoDeleteContextTest.correlation(this.single("proxy_cache_evict", "application")),
            new IsEqual<>(List.of(RepoDeleteContextTest.TRACE, RepoDeleteContextTest.CLIENT))
        );
    }

    @Test
    void refusedDeletesAreAudited() {
        final Storage storage = RepoDeleteContextTest.stale("lib/a.bin");
        final Slice slice = RepoDeleteContextTest.hosted(storage);
        MatcherAssert.assertThat(
            "the traversal is refused",
            RepoDeleteContextTest.send(slice, "/lib/../lib/a.bin"), new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "the root is refused",
            RepoDeleteContextTest.send(slice, "/"), new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "both refusals are audited as forbidden failures of the request",
            this.refusals(),
            new IsEqual<>(
                List.of(
                    List.of("lib/../lib/a.bin", "failure", "forbidden", RepoDeleteContextTest.TRACE),
                    List.of("/", "failure", "forbidden", RepoDeleteContextTest.TRACE)
                )
            )
        );
    }

    @Test
    void refusedEvictionsAreAudited() {
        final Slice slice = RepoDeleteContextTest.proxy(new InMemoryStorage());
        MatcherAssert.assertThat(
            "the traversal is refused",
            RepoDeleteContextTest.send(slice, "/dir/%2e%2e/x"), new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "the root is refused",
            RepoDeleteContextTest.send(slice, "/"), new IsEqual<>(400)
        );
        MatcherAssert.assertThat(
            "both refusals are audited as forbidden failures",
            this.refusals().stream().map(rec -> rec.subList(1, 3)).toList(),
            new IsEqual<>(List.of(List.of("failure", "forbidden"), List.of("failure", "forbidden")))
        );
    }

    /**
     * Audit records of the refusals: package.name, outcome, reason, trace id.
     * @return Records
     */
    private List<List<Object>> refusals() {
        final List<List<Object>> out = new ArrayList<>();
        for (final Map<String, Object> rec : this.records("artifact_delete", "audit")) {
            out.add(
                List.of(
                    String.valueOf(rec.get("package.name")),
                    String.valueOf(rec.get("event.outcome")),
                    String.valueOf(rec.get("event.reason")),
                    String.valueOf(rec.get("trace.id"))
                )
            );
        }
        return out;
    }

    /**
     * The one record of an action and log source.
     * @param action Event action
     * @param source Log source
     * @return Record
     */
    private Map<String, Object> single(final String action, final String source) {
        final List<Map<String, Object>> recs = this.records(action, source);
        MatcherAssert.assertThat(
            "exactly one " + source + " " + action + " record", recs.size(), new IsEqual<>(1)
        );
        return recs.get(0);
    }

    /**
     * Records of an action and log source.
     * @param action Event action
     * @param source Log source
     * @return Records
     */
    private List<Map<String, Object>> records(final String action, final String source) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final Capture capture : this.captures) {
            for (final Map<String, Object> rec : capture.events()) {
                if (action.equals(String.valueOf(rec.get("event.action")))
                    && source.equals(String.valueOf(rec.get("log.source")))) {
                    out.add(rec);
                }
            }
        }
        return out;
    }

    /**
     * Trace id and client IP of a record.
     * @param rec Record
     * @return Both
     */
    private static List<Object> correlation(final Map<String, Object> rec) {
        return List.of(
            String.valueOf(rec.get(EcsMdc.TRACE_ID)), String.valueOf(rec.get(EcsMdc.CLIENT_IP))
        );
    }

    /**
     * Hosted delete slice.
     * @param storage Storage
     * @return Slice
     */
    private static Slice hosted(final Storage storage) {
        return new RepoDeleteSlice(
            RepoDeleteContextTest.REPO, "file", storage,
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache())
        );
    }

    /**
     * Proxy eviction slice.
     * @param cache Cache storage
     * @return Slice
     */
    private static Slice proxy(final Storage cache) {
        return new ProxyEvictSlice(
            "files-remote", "file-proxy", Optional.of(cache),
            new ArtifactDeletion(new RecordingIndex(), new StorageMetaCache()),
            new ProxyPathCaches(
                "files-remote", "file-proxy", Optional.empty(), (type, pkg) -> { },
                repo -> Optional.empty()
            )
        );
    }

    /**
     * DELETE carrying the request-context headers EcsLoggingSlice stamps.
     * @param slice Slice
     * @param path Path
     * @return Status code
     */
    private static int send(final Slice slice, final String path) {
        return slice.response(
            new RequestLine(RqMethod.DELETE, path),
            new Headers()
                .add(EcsLoggingSlice.CTX_TRACE_ID_HEADER, RepoDeleteContextTest.TRACE)
                .add(EcsLoggingSlice.CTX_CLIENT_IP_HEADER, RepoDeleteContextTest.CLIENT),
            Content.EMPTY
        ).join().status().code();
    }

    /**
     * Storage holding the keys, whose every operation completes on a thread
     * whose MDC holds another request's correlation.
     * @param keys Keys
     * @return Storage
     */
    private static Storage stale(final String... keys) {
        final Storage storage = new InMemoryStorage();
        for (final String key : keys) {
            storage.save(new Key.From(key), new Content.From(new byte[] {1})).join();
        }
        return new StaleMdc(storage);
    }

    /**
     * Leave another request's correlation in the current thread's MDC.
     */
    private static void leaveStale() {
        ThreadContext.put(EcsMdc.TRACE_ID, RepoDeleteContextTest.STALE);
        ThreadContext.put(EcsMdc.CLIENT_IP, "192.0.2.99");
    }

    /**
     * Storage whose operations complete with another request's MDC on the
     * completing thread, as a pooled storage worker does.
     */
    private static final class StaleMdc extends Storage.Wrap {

        /**
         * Ctor.
         * @param origin Origin
         */
        StaleMdc(final Storage origin) {
            super(origin);
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return StaleMdc.stale(super.exists(key));
        }

        @Override
        public CompletableFuture<java.util.Collection<Key>> list(final Key prefix) {
            return StaleMdc.stale(super.list(prefix));
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return StaleMdc.stale(super.delete(key));
        }

        @Override
        public CompletableFuture<Void> deleteEmptyDirectories(final Key key) {
            return StaleMdc.stale(super.deleteEmptyDirectories(key));
        }

        /**
         * Complete with stale MDC on the completing thread.
         * @param origin Origin future
         * @param <T> Result type
         * @return Future
         */
        private static <T> CompletableFuture<T> stale(final CompletableFuture<T> origin) {
            return origin.whenComplete((res, err) -> RepoDeleteContextTest.leaveStale());
        }
    }

    /**
     * Storage whose delete fails.
     */
    private static final class FailingDelete extends Storage.Wrap {

        /**
         * Ctor.
         * @param origin Origin
         */
        FailingDelete(final Storage origin) {
            super(origin);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            RepoDeleteContextTest.leaveStale();
            return CompletableFuture.failedFuture(new IllegalStateException("disk gone"));
        }
    }

    /**
     * Captures the fields of one logger as the ECS layout writes them: the
     * payload overlaid with the thread context (MDC-owned keys win).
     */
    private static final class Capture extends AbstractAppender {

        /**
         * Captured payloads.
         */
        private final List<Map<String, Object>> captured =
            Collections.synchronizedList(new ArrayList<>());

        /**
         * Captured logger.
         */
        private final Logger logger;

        /**
         * Level of the logger before the capture.
         */
        private final Level before;

        /**
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("RepoDeleteContext-" + logger.getName(), null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.before = logger.getLevel();
        }

        /**
         * Start capturing a logger.
         * @param name Logger name
         * @return Capture
         */
        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
        }

        /**
         * Captured records.
         * @return Records
         */
        List<Map<String, Object>> events() {
            synchronized (this.captured) {
                return List.copyOf(this.captured);
            }
        }

        /**
         * Stop capturing.
         */
        void close() {
            this.logger.removeAppender(this);
            this.logger.setLevel(this.before);
            this.stop();
        }

        @Override
        public void append(final LogEvent event) {
            if (event.getMessage() instanceof MapMessage<?, ?> map) {
                final Map<String, Object> data = new HashMap<>();
                map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
                data.putAll(event.getContextData().toMap());
                this.captured.add(data);
            }
        }
    }
}
