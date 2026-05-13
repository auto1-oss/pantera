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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.FailedCompletionStage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test case for {@link CachedProxySlice}.
 */
final class CachedProxySliceTest {

    /**
     * Artifact events queue.
     */
    private Queue<ProxyArtifactEvent> events;

    /**
     * Fresh in-memory storage for each call site. CachedProxySlice now
     * requires non-empty raw storage by construction (Track 3 always-verify
     * fix); tests use a throwaway {@link InMemoryStorage} so they exercise
     * the same construction path as production.
     */
    private static Optional<Storage> testStorage() {
        return Optional.of(new InMemoryStorage());
    }

    /**
     * Upstream test fixture that serves {@code data} for primary artifact
     * requests and the matching SHA-1 hex for {@code .sha1} sidecar
     * requests. Required since Track 3 made {@link CachedProxySlice} verify
     * primaries against upstream {@code .sha1} on every cache-miss; a naive
     * upstream that serves the same body for both paths now fails the
     * integrity check.
     */
    private static com.auto1.pantera.http.Slice serveBodyWithSha1(final byte[] data) {
        final String sha1Hex = sha1Hex(data);
        return (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.endsWith(".sha1")) {
                return ResponseBuilder.ok()
                    .body(sha1Hex.getBytes(StandardCharsets.UTF_8))
                    .completedFuture();
            }
            return ResponseBuilder.ok().body(data).completedFuture();
        };
    }

    private static String sha1Hex(final byte[] data) {
        try {
            final java.security.MessageDigest md =
                java.security.MessageDigest.getInstance("SHA-1");
            final byte[] digest = md.digest(data);
            final StringBuilder out = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
    }

    @BeforeEach
    void init() {
        this.events = new LinkedList<>();
        // Ensure the maven-proxy type has a 403 factory registered so that
        // buildForbiddenResponse (called by evaluateCooldownOrProceed) does not
        // throw IllegalStateException in the cooldown-gating regression tests.
        CooldownResponseRegistry.instance().register("maven-proxy", new MavenCooldownResponseFactory());
    }

    @Test
    void loadsCachedContent() {
        final byte[] data = "cache".getBytes(StandardCharsets.UTF_8);
        final String path = "/com/example/pkg/1.0/pkg-1.0.jar";
        final InMemoryStorage storage = new InMemoryStorage();
        final CachedArtifactMetadataStore store = new CachedArtifactMetadataStore(storage);
        final Key key = new Key.From(path.substring(1));
        store.save(
            key,
            Headers.from("Content-Length", String.valueOf(data.length)),
            new CachedArtifactMetadataStore.ComputedDigests(data.length, Map.of())
        ).join();
        final AtomicBoolean upstream = new AtomicBoolean(false);
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                upstream.set(true);
                return CompletableFuture.failedFuture(new AssertionError("Upstream should not be hit on cache hit"));
            },
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(
                Optional.of(new Content.From(data))
            ),
            Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE,
            noopInspector(),
            Optional.of(storage)
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat("Upstream should not be called on cache hit", upstream.get(), Matchers.is(false));
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.OK));
        assertArrayEquals(data, response.body().asBytes());
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @Test
    void returnsServiceUnavailableOnRemoteError() {
        // Track 1 fix: upstream 5xx no longer collapses to 404 — operators
        // need to distinguish "upstream is broken" from "artifact doesn't
        // exist". Maps to 503 with the body drained to release the upstream
        // connection.
        MatcherAssert.assertThat(
            new CachedProxySlice(
                new SliceSimple(ResponseBuilder.internalError().build()),
                (key, supplier, control) -> supplier.get(),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                testStorage()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.SERVICE_UNAVAILABLE),
                new RequestLine(RqMethod.GET, "/any")
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @Test
    void returnsServiceUnavailableOnRemoteAndCacheError() {
        // Track 1 fix: upstream 5xx → 503 even when the local cache is
        // unavailable (no stale fallback possible).
        MatcherAssert.assertThat(
            new CachedProxySlice(
                new SliceSimple(ResponseBuilder.internalError().build()),
                (key, supplier, control)
                    -> new FailedCompletionStage<>(new RuntimeException("Any error")),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                testStorage()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.SERVICE_UNAVAILABLE),
                new RequestLine(RqMethod.GET, "/abc")
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/com/pantera/asto/1.5/asto-1.5.jar",
        "/com/pantera/asto/1.0-SNAPSHOT/asto-1.0-20200520.121003-4.jar",
        "/org/apache/commons/3.6/commons-3.6.pom",
        "/org/test/test-app/0.95/test-app-3.6.war"
    })
    void loadsOriginAndAdds(final String path) {
        final byte[] data = "remote".getBytes();
        MatcherAssert.assertThat(
            new CachedProxySlice(
                serveBodyWithSha1(data),
                (key, supplier, control) -> supplier.get(),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                testStorage()
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(data)
                ),
                new RequestLine(RqMethod.GET, path)
            )
        );
        MatcherAssert.assertThat("Events queue has one item", this.events.size() == 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Classified primaries (sources/javadoc) route through the
        // always-verify path but the event builder filters them out so the
        // queue stays empty.
        "/com/pantera/asto/1.5/asto-1.5-sources.jar",
        "/org/apache/commons/3.6/commons-3.6-javadoc.pom",
        // Metadata bypasses the verify path entirely (Track 1+3 unchanged).
        "/org/test/test-app/maven-metadata.xml"
        // Pure sidecar passthrough (e.g. {@code *.jar.sha1}) is now covered
        // end-to-end by ChecksumProxySliceErrorPropagationTest and isn't
        // re-tested here.
    })
    void loadsOriginAndDoesNotAddToEvents(final String path) {
        final byte[] data = "remote".getBytes();
        // Sources/javadoc artifacts route through the always-verify path
        // (Track 3) — they're {@code .jar}/{@code .pom} primaries and need a
        // matching {@code .sha1} from upstream. Sidecars and metadata don't
        // route through verification but they DO read back from the cache
        // post-write, so we need a real storage-backed cache pair (the dumb
        // {@code (key, supplier, control) -> supplier.get()} lambda returns
        // empty content for the {@code Remote.EMPTY} supplier the SUCCESS
        // path uses).
        final Storage storage = new InMemoryStorage();
        MatcherAssert.assertThat(
            new CachedProxySlice(
                serveBodyWithSha1(data),
                new com.auto1.pantera.asto.cache.FromStorageCache(storage),
                Optional.of(this.events), "*", "https://repo.maven.apache.org/maven2", "maven-proxy",
                NoopCooldownService.INSTANCE,
                noopInspector(),
                Optional.of(storage)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(data)
                ),
                new RequestLine(RqMethod.GET, path)
            )
        );
        MatcherAssert.assertThat("Events queue is empty", this.events.isEmpty());
    }

    /**
     * Regression test for WI-07 enqueue gap: when the ProxyCacheWriter path
     * handles a primary artifact (jar/pom) fetch on cache-miss, a
     * ProxyArtifactEvent MUST be offered to the events queue so that
     * MavenProxyPackageProcessor can write the DB-index row.
     *
     * <p>Before the fix, {@code fetchVerifyAndCache} returned directly to
     * {@code serveFromCache} without calling {@code enqueueEventForWriter},
     * so the queue was always empty for maven-proxy and gradle-proxy repos.</p>
     */
    @Test
    void fetchVerifyAndCacheEnqueuesEventOnSuccess() {
        final byte[] artifactBytes = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
        final String path = "/com/example/mylib/1.0/mylib-1.0.jar";
        final InMemoryStorage storage = new InMemoryStorage();
        final LinkedBlockingQueue<ProxyArtifactEvent> queue = new LinkedBlockingQueue<>();
        // Upstream returns the artifact bytes on the primary request;
        // the only eagerly fetched sidecar (.sha1) returns 404 so the writer
        // skips verification and commits the primary only. Phase 7 perf:
        // .md5/.sha256/.sha512 are no longer registered for eager fetching.
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                final String reqPath = line.uri().getPath();
                if (reqPath.endsWith(".jar")) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok().body(artifactBytes).build()
                    );
                }
                // Sidecar request (.sha1 only since Phase 7): 404 so writer skips
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            },
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(queue),
            "gradle_proxy",
            "https://repo.maven.apache.org/maven2",
            "maven-proxy",
            NoopCooldownService.INSTANCE,
            noopInspector(),
            Optional.of(storage)
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Response must be 200 OK after successful ProxyCacheWriter write",
            response.status(),
            Matchers.is(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Events queue must receive exactly one ProxyArtifactEvent after "
                + "ProxyCacheWriter succeeds — regression for WI-07 enqueue gap",
            queue.size(),
            Matchers.is(1)
        );
        final ProxyArtifactEvent event = queue.poll();
        MatcherAssert.assertThat(
            "Event repo name must match the slice's repository name",
            event.repoName(),
            Matchers.is("gradle_proxy")
        );
    }

    /**
     * Regression: the ProxyCacheWriter path (preProcess → verifyAndServePrimary)
     * MUST gate through cooldown evaluation. A blocked version inside the
     * cooldown window must return 403; upstream must NOT be contacted and
     * storage must remain empty.
     *
     * <p>Before the fix, preProcess short-circuited directly to
     * verifyAndServePrimary → fetchVerifyAndCache without ever calling
     * evaluateCooldownAndFetch, so a freshly-released or admin-blocked version
     * was fetched from upstream and cached with no 403 ever firing.</p>
     */
    @Test
    void verifyAndServePrimaryBlocksCooldownedVersion() {
        final String path = "/com/example/mylib/1.0/mylib-1.0.jar";
        final InMemoryStorage storage = new InMemoryStorage();
        final AtomicBoolean upstreamCalled = new AtomicBoolean(false);
        final CooldownBlock block = new CooldownBlock(
            "maven-proxy", "maven_proxy", "com/example/mylib", "1.0",
            CooldownReason.FRESH_RELEASE,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3600),
            List.of()
        );
        final CooldownService blockingService = new CooldownService() {
            @Override
            public CompletableFuture<CooldownResult> evaluate(
                final CooldownRequest request, final CooldownInspector inspector
            ) {
                return CompletableFuture.completedFuture(CooldownResult.blocked(block));
            }
            @Override
            public CompletableFuture<Void> unblock(
                final String rt, final String rn, final String art,
                final String ver, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<Void> unblockAll(
                final String rt, final String rn, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<List<CooldownBlock>> activeBlocks(
                final String rt, final String rn
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                upstreamCalled.set(true);
                return CompletableFuture.failedFuture(
                    new AssertionError("Upstream must not be called for a blocked version")
                );
            },
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(this.events),
            "maven_proxy",
            "https://repo.maven.apache.org/maven2",
            "maven-proxy",
            blockingService,
            noopInspector(),
            Optional.of(storage),
            ProxyCacheConfig.withCooldown(),
            null
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Blocked version must return 403 — regression for WI-07 cooldown bypass",
            response.status(),
            Matchers.is(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "Upstream must NOT be contacted for a blocked version",
            upstreamCalled.get(),
            Matchers.is(false)
        );
        final Key key = new Key.From(path.substring(1));
        final boolean cached = storage.exists(key).join();
        MatcherAssert.assertThat(
            "Storage must be empty — blocked version must not be cached",
            cached,
            Matchers.is(false)
        );
    }

    /**
     * Track 5 Phase 1A: cache-hit path is pure-local. A cooldown block
     * applied AFTER the version was cached must NOT take effect on
     * subsequent reads — cooldown evaluation now runs only on cache miss /
     * refresh. The admin's tool for blocking an already-cached version is
     * cache eviction.
     *
     * <p>Why this is the right trade-off: pre-Track-5 every cache hit ran
     * {@code evaluateCooldownOrProceed} which threaded through the publish-date
     * inspector chain, falling through to {@code MavenHeadSource} HEAD on
     * Maven Central on L1+L2 miss. Under Cloudflare-backed Maven Central
     * rate limiting that path generated the 429 storm that motivated
     * Track 5. The new contract: cache hit = ZERO upstream I/O, ever.</p>
     */
    @Test
    void verifyAndServePrimaryCacheHitIsLocalEvenWhenBlockIsActive() {
        final String path = "/com/example/mylib/1.0/mylib-1.0.jar";
        final byte[] cachedBytes = "already-cached-jar".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From(path.substring(1));
        storage.save(key, new Content.From(cachedBytes)).join();
        final AtomicBoolean upstreamCalled = new AtomicBoolean(false);
        final AtomicBoolean cooldownEvaluated = new AtomicBoolean(false);
        final CooldownBlock block = new CooldownBlock(
            "maven-proxy", "maven_proxy", "com/example/mylib", "1.0",
            CooldownReason.FRESH_RELEASE,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3600),
            List.of()
        );
        final CooldownService blockingService = new CooldownService() {
            @Override
            public CompletableFuture<CooldownResult> evaluate(
                final CooldownRequest request, final CooldownInspector inspector
            ) {
                cooldownEvaluated.set(true);
                return CompletableFuture.completedFuture(CooldownResult.blocked(block));
            }
            @Override
            public CompletableFuture<Void> unblock(
                final String rt, final String rn, final String art,
                final String ver, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<Void> unblockAll(
                final String rt, final String rn, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<List<CooldownBlock>> activeBlocks(
                final String rt, final String rn
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                upstreamCalled.set(true);
                return CompletableFuture.failedFuture(
                    new AssertionError("Upstream must not be called on cache hit")
                );
            },
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(this.events),
            "maven_proxy",
            "https://repo.maven.apache.org/maven2",
            "maven-proxy",
            blockingService,
            noopInspector(),
            Optional.of(storage),
            ProxyCacheConfig.withCooldown(),
            null
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Cache hit serves the cached bytes with 200 — cooldown is not evaluated",
            response.status(),
            Matchers.is(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Cooldown must NOT be evaluated on cache hit (Track 5 contract)",
            cooldownEvaluated.get(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "Upstream must NOT be contacted on cache hit",
            upstreamCalled.get(),
            Matchers.is(false)
        );
        final byte[] body = response.body().asBytes();
        MatcherAssert.assertThat(
            "Cache hit returns the cached bytes verbatim",
            new String(body, StandardCharsets.UTF_8),
            Matchers.is("already-cached-jar")
        );
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }

    /**
     * Regression: when a {@link CooldownMetadataService} is provided,
     * {@code handleMetadata()} MUST pass the upstream {@code maven-metadata.xml}
     * bytes through it before responding. Before the wiring fix, the service
     * existed in {@code CooldownAdapterRegistry} but was never invoked on the
     * Maven proxy path — a {@code latest.release} resolve through
     * {@code gradle_group} resolved to fresh-within-cooldown versions
     * (reproduced with {@code com.google.guava:guava:latest.release} picking
     * up {@code 33.6.0-jre} 8 days after release against a 14-day cooldown).
     *
     * <p>The test substitutes a recording stub service; the stub asserts that
     * the filter is invoked with (repoType, repoName, packageName, rawBytes)
     * and returns a deterministic "filtered" body. The response body must
     * equal that body — not the upstream bytes — proving the filter ran.</p>
     */
    @Test
    void handleMetadataInvokesCooldownFilterWhenServiceProvided() throws Exception {
        final byte[] upstreamXml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<metadata>"
            + "<groupId>com.google.guava</groupId>"
            + "<artifactId>guava</artifactId>"
            + "<versioning><latest>33.6.0-jre</latest><release>33.6.0-jre</release>"
            + "<versions><version>33.5.0-jre</version><version>33.6.0-jre</version></versions>"
            + "</versioning></metadata>"
        ).getBytes(StandardCharsets.UTF_8);
        final byte[] filteredXml = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<metadata>"
            + "<groupId>com.google.guava</groupId>"
            + "<artifactId>guava</artifactId>"
            + "<versioning><latest>33.5.0-jre</latest><release>33.5.0-jre</release>"
            + "<versions><version>33.5.0-jre</version></versions>"
            + "</versioning></metadata>"
        ).getBytes(StandardCharsets.UTF_8);
        final AtomicReference<String> capturedPackage = new AtomicReference<>();
        final AtomicReference<byte[]> capturedBytes = new AtomicReference<>();
        final CooldownMetadataService recordingService = new CooldownMetadataService() {
            @Override
            public <T> CompletableFuture<byte[]> filterMetadata(
                final String repoType, final String repoName, final String packageName,
                final byte[] rawMetadata, final MetadataParser<T> parser,
                final MetadataFilter<T> filter, final MetadataRewriter<T> rewriter,
                final Optional<CooldownInspector> inspector
            ) {
                capturedPackage.set(packageName);
                capturedBytes.set(rawMetadata);
                return CompletableFuture.completedFuture(filteredXml);
            }

            @Override
            public void invalidate(final String repoType, final String repoName, final String packageName) { }

            @Override
            public void invalidateAll(final String repoType, final String repoName) { }

            @Override
            public void clearAll() { }

            @Override
            public String stats() {
                return "recording";
            }
        };
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamXml).build()
            ),
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(this.events), "gradle_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(), testStorage(),
            ProxyCacheConfig.defaults(),
            new MetadataCache(Duration.ofMinutes(1)),
            recordingService
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/com/google/guava/guava/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Filter must be invoked with the DOTTED group/artifact coordinate "
                + "— MavenHeadSource splits on the last dot to derive groupId/"
                + "artifactId, so a slashed name yields an empty inspector "
                + "lookup and the filter silently fails open.",
            capturedPackage.get(),
            Matchers.is("com.google.guava.guava")
        );
        MatcherAssert.assertThat(
            "Filter must receive the raw upstream bytes",
            capturedBytes.get() != null && java.util.Arrays.equals(capturedBytes.get(), upstreamXml),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.OK));
        assertArrayEquals(
            filteredXml,
            response.body().asBytes(),
            "Response body must be the filter's output, not the raw upstream bytes — "
                + "if this fails, handleMetadata() is bypassing the cooldown filter and "
                + "Gradle's latest.release resolution will pick up too-fresh versions"
        );
    }

    /**
     * Absence of a {@link CooldownMetadataService} must preserve the
     * pre-fix pass-through behaviour — used by the legacy 11-arg constructor
     * path and by tests. Pairs with the test above to prove the guard on
     * {@code cooldownMetadata != null} is real, not unconditional.
     */
    @Test
    void handleMetadataPassesThroughWhenNoCooldownMetadataService() throws Exception {
        final byte[] upstreamXml = "<metadata><groupId>x</groupId><artifactId>y</artifactId></metadata>"
            .getBytes(StandardCharsets.UTF_8);
        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamXml).build()
            ),
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(this.events), "gradle_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(), testStorage(),
            ProxyCacheConfig.defaults(),
            new MetadataCache(Duration.ofMinutes(1))
            // no CooldownMetadataService → pass-through behaviour
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/x/y/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(response.status(), Matchers.is(RsStatus.OK));
        assertArrayEquals(upstreamXml, response.body().asBytes());
    }
}
