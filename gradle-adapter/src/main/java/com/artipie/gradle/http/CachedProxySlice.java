/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.DigestVerification;
import com.artipie.asto.cache.Remote;
import com.artipie.asto.ext.Digests;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResult;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.jcabi.log.Logger;
import io.reactivex.Flowable;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Gradle proxy slice with cache support.
 *
 * @since 1.0
 */
final class CachedProxySlice implements Slice {

    /**
     * Checksum header pattern.
     */
    private static final Pattern CHECKSUM_PATTERN =
        Pattern.compile("x-checksum-(sha1|sha256|sha512|md5)", Pattern.CASE_INSENSITIVE);

    /**
     * Translation of checksum headers to digest algorithms.
     */
    private static final Map<String, String> DIGEST_NAMES = Map.of(
        "sha1", "SHA-1",
        "sha256", "SHA-256",
        "sha512", "SHA-512",
        "md5", "MD5"
    );

    /**
     * Origin slice.
     */
    private final Slice client;

    /**
     * Cache.
     */
    private final Cache cache;

    /**
     * Proxy artifact events.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type.
     */
    private final String rtype;

    /**
     * Storage for persisting checksums.
     */
    private final Optional<Storage> storage;

    /**
     * Wraps origin slice with caching layer.
     *
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param storage Storage for persisting checksums (optional)
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final Optional<Storage> storage
    ) {
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        Logger.info(this, "Gradle proxy request: %s", path);
        if ("/".equals(path) || path.isEmpty()) {
            return this.handleRootPath(line);
        }
        final Key key = new KeyFromPath(path);
        final Optional<CooldownRequest> request = this.cooldownRequest(headers, path);
        if (request.isEmpty()) {
            Logger.info(this, "No cooldown check for path: %s (doesn't match artifact pattern)", path);
            return this.fetchThroughCache(line, key, headers);
        }
        return this.cooldown.evaluate(request.get(), this.inspector)
            .thenCompose(result -> this.afterCooldown(result, line, key, headers));
    }

    private CompletableFuture<Response> afterCooldown(
        final CooldownResult result,
        final RequestLine line,
        final Key key,
        final Headers headers
    ) {
        if (result.blocked()) {
            Logger.info(
                this,
                "Cooldown BLOCKED request for %s (reason: %s, until: %s)",
                key.string(),
                result.block().orElseThrow().reason(),
                result.block().orElseThrow().blockedUntil()
            );
            return CompletableFuture.completedFuture(
                CooldownResponses.forbidden(result.block().orElseThrow())
            );
        }
        Logger.debug(this, "Cooldown ALLOWED request for %s", key.string());
        return this.fetchThroughCache(line, key, headers);
    }

    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Key key,
        final Headers request
    ) {
        final AtomicReference<Headers> rshdr = new AtomicReference<>(Headers.EMPTY);
        final String owner = new Login(request).getValue();
        return new RepoHead(this.client)
            .head(line.uri().getPath()).thenCompose(
                head -> this.cache.load(
                    key,
                    new Remote.WithErrorHandling(
                        () -> {
                            final CompletableFuture<Optional<? extends Content>> promise =
                                new CompletableFuture<>();
                            this.client.response(line, Headers.EMPTY, Content.EMPTY)
                                .thenApply(resp -> {
                                    final CompletableFuture<Void> term = new CompletableFuture<>();
                                    if (resp.status().success()) {
                                        final Flowable<ByteBuffer> res =
                                            Flowable.fromPublisher(resp.body())
                                                .doOnError(term::completeExceptionally)
                                                .doOnTerminate(() -> term.complete(null));
                                        this.enqueueFromHeaders(resp.headers(), key, owner);
                                        promise.complete(Optional.of(new Content.From(res)));
                                        // Download and cache checksum files asynchronously
                                        this.cacheChecksumFiles(line, key);
                                    } else {
                                        promise.complete(Optional.empty());
                                    }
                                    rshdr.set(resp.headers());
                                    return term;
                                });
                            return promise;
                        }
                    ),
                    new CacheControl.All(
                        StreamSupport.stream(
                                head.orElse(Headers.EMPTY).spliterator(),
                                false
                            ).map(Header::new)
                            .map(CachedProxySlice::checksumControl)
                            .toList()
                    )
                ).handle(
                    (content, throwable) -> {
                        if (throwable == null && content.isPresent()) {
                            return ResponseBuilder.ok()
                                .headers(rshdr.get())
                                .body(content.get())
                                .build();
                        }
                        if (throwable != null) {
                            Logger.error(this, throwable.getMessage());
                        }
                        return ResponseBuilder.notFound().build();
                    }
                )
            ).toCompletableFuture();
    }

    private Optional<CooldownRequest> cooldownRequest(final Headers headers, final String path) {
        final Matcher matcher = GradleSlice.ARTIFACT.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String group = matcher.group("group");
        final String artifact = matcher.group("artifact");
        final String version = matcher.group("version");
        final String artifactName = String.format("%s.%s", group.replace('/', '.'), artifact);
        final String user = new Login(headers).getValue();
        Logger.info(
            this,
            "Gradle cooldown check for %s:%s (path=%s)",
            artifactName, version, path
        );
        return Optional.of(
            new CooldownRequest(
                this.rtype,
                this.rname,
                artifactName,
                version,
                user,
                Instant.now()
            )
        );
    }

    private void enqueueFromHeaders(final Headers headers, final Key key, final String owner) {
        Long lm = null;
        try {
            lm = StreamSupport.stream(headers.spliterator(), false)
                .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .map(val -> Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(val)).toEpochMilli())
                .orElse(null);
        } catch (final DateTimeParseException ignored) {
            // ignore invalid date header
        }
        this.addEventToQueue(key, owner, Optional.ofNullable(lm));
    }

    private void addEventToQueue(final Key key, final String owner, final Optional<Long> release) {
        if (this.events.isPresent()) {
            // Ensure path starts with / for pattern matching
            final String path = key.string().startsWith("/") ? key.string() : "/" + key.string();
            final Matcher matcher = GradleSlice.ARTIFACT.matcher(path);
            if (matcher.matches()) {
                final String group = matcher.group("group");
                final String artifact = matcher.group("artifact");
                final String version = matcher.group("version");
                this.events.get().add(
                    new ProxyArtifactEvent(
                        new Key.From(String.format("%s/%s/%s", group, artifact, version)),
                        this.rname,
                        owner,
                        release
                    )
                );
                Logger.debug(
                    this,
                    "Added Gradle proxy event for %s:%s:%s (owner=%s)",
                    group, artifact, version, owner
                );
            } else {
                Logger.debug(this, "Path %s did not match artifact pattern", path);
            }
        }
    }

    private static CacheControl checksumControl(final Header header) {
        final Matcher matcher = CachedProxySlice.CHECKSUM_PATTERN.matcher(header.getKey());
        final CacheControl res;
        if (matcher.matches()) {
            try {
                res = new DigestVerification(
                    new Digests.FromString(
                        CachedProxySlice.DIGEST_NAMES.get(
                            matcher.group(1).toLowerCase(Locale.US)
                        )
                    ).get(),
                    Hex.decodeHex(header.getValue().toCharArray())
                );
            } catch (final DecoderException err) {
                throw new IllegalStateException("Invalid digest hex", err);
            }
        } else {
            res = CacheControl.Standard.ALWAYS;
        }
        return res;
    }

    private CompletableFuture<Response> handleRootPath(final RequestLine line) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                if (resp.status().success()) {
                    return ResponseBuilder.ok()
                        .headers(resp.headers())
                        .body(resp.body())
                        .build();
                }
                return ResponseBuilder.notFound().build();
            });
    }

    /**
     * Download and save checksum files (.md5, .sha1, .sha256, .sha512) for an artifact.
     * Saves to storage for persistence, and also to cache if available for fast access.
     * This runs asynchronously and doesn't block the main response.
     * @param line Original request line
     * @param artifactKey Key of the main artifact
     */
    private void cacheChecksumFiles(final RequestLine line, final Key artifactKey) {
        // Only download checksums if storage is available
        if (this.storage.isEmpty()) {
            return;
        }
        
        final String artifactPath = line.uri().getPath();
        final String[] checksumExtensions = {".md5", ".sha1", ".sha256", ".sha512"};
        
        for (final String ext : checksumExtensions) {
            final String checksumPath = artifactPath + ext;
            final Key checksumKey = new Key.From(artifactKey.string() + ext);
            final RequestLine checksumLine = new RequestLine(
                line.method().value(),
                checksumPath,
                line.version()
            );
            
            // Download checksum from upstream
            this.client.response(checksumLine, Headers.EMPTY, Content.EMPTY)
                .thenCompose(resp -> {
                    if (resp.status().success()) {
                        // Use cache.load() to save to BOTH storage and cache in one operation
                        // This is more efficient than saving separately
                        return this.cache.load(
                            checksumKey,
                            () -> CompletableFuture.completedFuture(Optional.of(resp.body())),
                            CacheControl.Standard.ALWAYS
                        ).thenApply(content -> {
                            Logger.debug(
                                this,
                                "Saved checksum to storage and cache: %s",
                                checksumPath
                            );
                            return null;
                        });
                    } else {
                        Logger.debug(
                            this,
                            "Checksum file not available upstream: %s",
                            checksumPath
                        );
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .exceptionally(err -> {
                    Logger.debug(
                        this,
                        "Failed to save checksum %s: %s",
                        checksumPath,
                        err.getMessage()
                    );
                    return null;
                });
        }
    }
}
