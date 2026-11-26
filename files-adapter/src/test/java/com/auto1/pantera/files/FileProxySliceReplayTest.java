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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromRemoteCache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.cache.StreamThroughCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.ReasonSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Troubleshoot replay asks a file proxy what a client would get. It
 * must leave the proxy as it found it: a file the upstream has is handed
 * on, not stored, and no event for the search index is written. A replay
 * that cached the file changed what the group index says, and with it the
 * answers of the requests that followed.
 *
 * @since 2.2.15
 */
final class FileProxySliceReplayTest {

    /**
     * Requested file.
     */
    private static final String PATH = "/tools/cli-1.0.tar.gz";

    /**
     * Its key in the proxy's storage.
     */
    private static final Key KEY = new Key.From("tools", "cli-1.0.tar.gz");

    /**
     * What the upstream has.
     */
    private static final byte[] BODY = "file bytes".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @EnumSource(Wiring.class)
    @Timeout(120)
    void replayAnswersWhatTheUpstreamHasAndStoresNothing(final Wiring wiring) throws Exception {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final AtomicInteger upstream = new AtomicInteger();
        final Slice slice = wiring.slice(
            FileProxySliceReplayTest.upstream(upstream, FileProxySliceReplayTest.BODY),
            storage, events
        );
        final Response replay = FileProxySliceReplayTest.replay(slice);
        MatcherAssert.assertThat(
            "the replay says the file is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and hands on what the upstream sent",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(FileProxySliceReplayTest.BODY)
        );
        // A write would come from a callback after the body: give one
        // that should not exist the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay stores nothing", FileProxySliceReplayTest.keys(storage),
                new IsEqual<>(List.of())
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        MatcherAssert.assertThat(
            "and writes no event for the search index", events.size(), new IsEqual<>(0)
        );
        final Response real = FileProxySliceReplayTest.get(slice, Headers.EMPTY);
        MatcherAssert.assertThat(
            "a client's request after it is served from the upstream",
            real.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(FileProxySliceReplayTest.BODY)
        );
        // The save follows the stream: wait for it, do not time it.
        for (
            int idx = 0;
            idx < 400 && !storage.exists(FileProxySliceReplayTest.KEY).join();
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "and stored as before",
            storage.value(FileProxySliceReplayTest.KEY).join().asBytesFuture().join(),
            new IsEqual<>(FileProxySliceReplayTest.BODY)
        );
        MatcherAssert.assertThat(
            "with its event for the search index", events.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.get(), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void replayHandsALargeFileOnAndWritesNothingToDisk(@TempDir final Path dir)
        throws Exception {
        final byte[] large = new byte[3 * 1024 * 1024 + 17];
        for (int idx = 0; idx < large.length; idx += 1) {
            large[idx] = (byte) (idx % 251);
        }
        final Storage storage = new FileStorage(dir);
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final AtomicInteger upstream = new AtomicInteger();
        final Slice slice = Wiring.CACHE_FIRST.slice(
            FileProxySliceReplayTest.upstream(upstream, large), storage, events
        );
        final Response replay = FileProxySliceReplayTest.replay(slice);
        MatcherAssert.assertThat(
            "the replay hands the whole file on",
            Arrays.equals(replay.body().asBytesFuture().get(60, TimeUnit.SECONDS), large),
            new IsEqual<>(true)
        );
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay writes no file, finished or not",
                FileProxySliceReplayTest.files(dir), new IsEqual<>(List.of())
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        MatcherAssert.assertThat(
            "and writes no event for the search index", events.size(), new IsEqual<>(0)
        );
        final Response real = FileProxySliceReplayTest.get(slice, Headers.EMPTY);
        MatcherAssert.assertThat(
            "a client's request after it gets the whole file",
            Arrays.equals(real.body().asBytesFuture().get(60, TimeUnit.SECONDS), large),
            new IsEqual<>(true)
        );
        for (
            int idx = 0;
            idx < 400 && !storage.exists(FileProxySliceReplayTest.KEY).join();
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "and the file is stored as before",
            Arrays.equals(
                storage.value(FileProxySliceReplayTest.KEY).join().asBytesFuture().join(),
                large
            ),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(120)
    void replayIsServedAFileTheProxyHolds() throws Exception {
        final byte[] held = "the stored copy".getBytes(StandardCharsets.UTF_8);
        final Storage storage = new InMemoryStorage();
        storage.save(FileProxySliceReplayTest.KEY, new Content.From(held)).join();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final AtomicInteger upstream = new AtomicInteger();
        final Response replay = FileProxySliceReplayTest.replay(
            Wiring.CACHE_FIRST.slice(
                FileProxySliceReplayTest.upstream(upstream, FileProxySliceReplayTest.BODY),
                storage, events
            )
        );
        MatcherAssert.assertThat(
            "the replay is answered", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "with the copy the proxy holds",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(held)
        );
        MatcherAssert.assertThat(
            "the upstream is not asked", upstream.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "no event is written", events.size(), new IsEqual<>(0)
        );
    }

    @Test
    @Timeout(120)
    void replayThroughARemoteFirstCacheLeavesItsCopyAlone() throws Exception {
        final byte[] held = "the stored copy".getBytes(StandardCharsets.UTF_8);
        final Storage storage = new InMemoryStorage();
        storage.save(FileProxySliceReplayTest.KEY, new Content.From(held)).join();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final AtomicInteger upstream = new AtomicInteger();
        final Response replay = FileProxySliceReplayTest.replay(
            Wiring.REMOTE_FIRST.slice(
                FileProxySliceReplayTest.upstream(upstream, FileProxySliceReplayTest.BODY),
                storage, events
            )
        );
        MatcherAssert.assertThat(
            "the replay gets what a client would get: the upstream's file",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(FileProxySliceReplayTest.BODY)
        );
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the copy the proxy holds is not replaced",
                storage.value(FileProxySliceReplayTest.KEY).join().asBytesFuture().join(),
                new IsEqual<>(held)
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        MatcherAssert.assertThat(
            "no event is written", events.size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the upstream was asked, as for a client", upstream.get(), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void replayReadsTheCopyItDoesNotServeToItsEnd() throws Exception {
        final AtomicInteger read = new AtomicInteger();
        final Cache cache = (key, remote, control) -> remote.get().thenApply(
            got -> {
                final Optional<? extends Content> res;
                if (got.isPresent()) {
                    res = got;
                } else {
                    res = Optional.of(
                        new Content.From(
                            Flowable.just(
                                ByteBuffer.wrap("the stored copy".getBytes(StandardCharsets.UTF_8))
                            ).doOnComplete(read::incrementAndGet)
                        )
                    );
                }
                return res;
            }
        );
        final Response replay = FileProxySliceReplayTest.replay(
            new FileProxySlice(
                FileProxySliceReplayTest.upstream(
                    new AtomicInteger(), FileProxySliceReplayTest.BODY
                ),
                cache
            )
        );
        MatcherAssert.assertThat(
            "the replay gets the upstream's file",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(FileProxySliceReplayTest.BODY)
        );
        MatcherAssert.assertThat(
            "the copy the cache answered with instead is read to its end, not left open",
            read.get(), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void replayIsServedTheCopyARemoteFirstCacheFallsBackTo() throws Exception {
        final byte[] held = "the stored copy".getBytes(StandardCharsets.UTF_8);
        final Storage storage = new InMemoryStorage();
        storage.save(FileProxySliceReplayTest.KEY, new Content.From(held)).join();
        final Response replay = FileProxySliceReplayTest.replay(
            Wiring.REMOTE_FIRST.slice(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.internalError().build()
                ),
                storage, new ConcurrentLinkedQueue<>()
            )
        );
        MatcherAssert.assertThat(
            "the upstream failed and the proxy holds the file: it is served",
            replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "from the copy, as for a client",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(held)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 401, 500, 503})
    @Timeout(120)
    void replayOfAFileTheUpstreamDoesNotGiveIsAnsweredAsAClientIs(final int status)
        throws Exception {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new ReasonSlice(
            Wiring.CACHE_FIRST.slice(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.from(RsStatus.byCode(status)).build()
                ),
                storage, events
            )
        );
        final Response client = FileProxySliceReplayTest.get(slice, Headers.EMPTY);
        client.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        final Response replay = FileProxySliceReplayTest.replay(slice);
        replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the replay answers the status a client gets",
            replay.status(), new IsEqual<>(client.status())
        );
        MatcherAssert.assertThat(
            "for the reason a client gets it",
            new AnswerReason(replay).token(), new IsEqual<>(new AnswerReason(client).token())
        );
        MatcherAssert.assertThat(
            "and the reason is the upstream's answer",
            new AnswerReason(replay).token(),
            new IsEqual<>(Optional.of(String.format("upstream_status_%d", status)))
        );
        MatcherAssert.assertThat(
            "nothing is stored", FileProxySliceReplayTest.keys(storage),
            new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "no event is written", events.size(), new IsEqual<>(0)
        );
    }

    @Test
    @Timeout(120)
    void replayIsBlockedByCooldownAsAClientIs() throws Exception {
        CooldownResponseRegistry.instance().register(new FileProxyCooldownResponseFactory());
        final Storage storage = new InMemoryStorage();
        final AtomicInteger upstream = new AtomicInteger();
        final Cooldown cooldown = new Cooldown(true);
        final Response replay = FileProxySliceReplayTest.replay(
            new FileProxySlice(
                FileProxySliceReplayTest.upstream(upstream, FileProxySliceReplayTest.BODY),
                new FromStorageCache(storage), Optional.of(new ConcurrentLinkedQueue<>()),
                "files_proxy", "file-proxy", cooldown, "http://upstream.invalid/",
                Optional.of(storage)
            )
        );
        replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the replay is refused as a client is",
            replay.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "with the cooldown marker",
            replay.headers().values(CooldownResponseFactory.HEADER),
            new IsEqual<>(List.of("blocked"))
        );
        MatcherAssert.assertThat(
            "cooldown was evaluated once", cooldown.evaluated(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the file is not fetched", upstream.get(), new IsEqual<>(0)
        );
    }

    @Test
    @Timeout(120)
    void replayOfAnAllowedFileEvaluatesCooldown() throws Exception {
        final Storage storage = new InMemoryStorage();
        final Cooldown cooldown = new Cooldown(false);
        final Response replay = FileProxySliceReplayTest.replay(
            new FileProxySlice(
                FileProxySliceReplayTest.upstream(
                    new AtomicInteger(), FileProxySliceReplayTest.BODY
                ),
                new FromStorageCache(storage), Optional.of(new ConcurrentLinkedQueue<>()),
                "files_proxy", "file-proxy", cooldown, "http://upstream.invalid/",
                Optional.of(storage)
            )
        );
        MatcherAssert.assertThat(
            "the replay gets the file",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(FileProxySliceReplayTest.BODY)
        );
        MatcherAssert.assertThat(
            "after the cooldown evaluation a client's request gets",
            cooldown.evaluated(), new IsEqual<>(1)
        );
    }

    /**
     * Upstream that has one file and counts the requests for it.
     * @param calls Counter of the requests
     * @param bytes The file, sent in several chunks
     * @return Upstream
     */
    private static Slice upstream(final AtomicInteger calls, final byte[] bytes) {
        return (line, headers, body) -> {
            calls.incrementAndGet();
            final List<byte[]> chunks = new ArrayList<>(64);
            for (int from = 0; from < bytes.length; from += 65_536) {
                chunks.add(
                    Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + 65_536))
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "application/gzip")
                    .body(
                        new Content.From(
                            Flowable.fromIterable(chunks).map(ByteBuffer::wrap)
                        )
                    )
                    .build()
            );
        };
    }

    /**
     * The request of a Troubleshoot replay.
     * @param slice Proxy
     * @return Its answer
     * @throws Exception On error
     */
    private static Response replay(final Slice slice) throws Exception {
        final String trace = RequestTrace.instance().open();
        try {
            return FileProxySliceReplayTest.get(
                slice, Headers.from(RequestTrace.HEADER, trace)
            );
        } finally {
            RequestTrace.instance().close(trace);
        }
    }

    /**
     * A GET of the file.
     * @param slice Proxy
     * @param headers Request headers
     * @return Its answer
     * @throws Exception On error
     */
    private static Response get(final Slice slice, final Headers headers) throws Exception {
        return slice.response(
            new RequestLine(RqMethod.GET, FileProxySliceReplayTest.PATH), headers, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
    }

    /**
     * Every key of a storage.
     * @param storage Storage
     * @return Keys
     */
    private static List<String> keys(final Storage storage) {
        return storage.list(Key.ROOT).join().stream().map(Key::string).toList();
    }

    /**
     * Every file under a directory, temporary ones included.
     * @param dir Directory
     * @return Paths
     * @throws Exception On error
     */
    private static List<Path> files(final Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * The ways a file proxy is put together: which cache stores the file,
     * and whether the proxy looks into its storage before anything else.
     */
    private enum Wiring {

        /**
         * As {@code FileProxy} of pantera-main builds it: the storage is
         * read first, then the cache stores what the upstream sends.
         */
        CACHE_FIRST {
            @Override
            Slice slice(
                final Slice upstream, final Storage storage, final Queue<ArtifactEvent> events
            ) {
                return Wiring.proxy(
                    upstream, new FromStorageCache(storage), events, Optional.of(storage)
                );
            }
        },

        /**
         * The public constructors with a storage: stream-through cache.
         */
        STREAM_THROUGH {
            @Override
            Slice slice(
                final Slice upstream, final Storage storage, final Queue<ArtifactEvent> events
            ) {
                return Wiring.proxy(
                    upstream, new StreamThroughCache(storage), events, Optional.of(storage)
                );
            }
        },

        /**
         * A cache that asks the upstream first and keeps what it sends.
         */
        FROM_REMOTE {
            @Override
            Slice slice(
                final Slice upstream, final Storage storage, final Queue<ArtifactEvent> events
            ) {
                return Wiring.proxy(
                    upstream, new FromRemoteCache(storage), events, Optional.of(storage)
                );
            }
        },

        /**
         * A storing cache alone: the proxy does not read the storage
         * itself, the cache asks the upstream first.
         */
        REMOTE_FIRST {
            @Override
            Slice slice(
                final Slice upstream, final Storage storage, final Queue<ArtifactEvent> events
            ) {
                return Wiring.proxy(
                    upstream, new StreamThroughCache(storage), events, Optional.empty()
                );
            }
        },

        /**
         * A storing cache alone that reads its storage first.
         */
        CACHE_ALONE {
            @Override
            Slice slice(
                final Slice upstream, final Storage storage, final Queue<ArtifactEvent> events
            ) {
                return Wiring.proxy(
                    upstream, new FromStorageCache(storage), events, Optional.empty()
                );
            }
        };

        /**
         * The proxy.
         * @param upstream Upstream
         * @param storage Storage of the proxy
         * @param events Queue of the artifact events
         * @return Proxy
         */
        abstract Slice slice(Slice upstream, Storage storage, Queue<ArtifactEvent> events);

        private static Slice proxy(
            final Slice upstream, final Cache cache, final Queue<ArtifactEvent> events,
            final Optional<Storage> storage
        ) {
            return new FileProxySlice(
                upstream, cache, Optional.of(events), "files_proxy", "file-proxy",
                NoopCooldownService.INSTANCE, "http://upstream.invalid/", storage
            );
        }
    }

    /**
     * Cooldown that blocks everything or nothing, and counts.
     */
    private static final class Cooldown implements CooldownService {

        /**
         * Whether a request is blocked.
         */
        private final boolean blocks;

        /**
         * Evaluations made.
         */
        private final AtomicInteger count;

        Cooldown(final boolean blocks) {
            this.blocks = blocks;
            this.count = new AtomicInteger();
        }

        int evaluated() {
            return this.count.get();
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            this.count.incrementAndGet();
            final CooldownResult result;
            if (this.blocks) {
                result = CooldownResult.blocked(
                    new CooldownBlock(
                        request.repoType(), request.repoName(), request.artifact(),
                        request.version(), CooldownReason.FRESH_RELEASE,
                        Instant.now(), Instant.now().plus(Duration.ofHours(72)), List.of()
                    )
                );
            } else {
                result = CooldownResult.allowed();
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName,
            final String artifact, final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
