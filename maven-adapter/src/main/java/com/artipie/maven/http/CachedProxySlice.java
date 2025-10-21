/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
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
 * Maven proxy slice with cache support.
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
     * Wraps origin slice with caching layer.
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    CachedProxySlice(final Slice client, final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events, final String rname,
        final String rtype, final CooldownService cooldown, final CooldownInspector inspector) {
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    /**
     * Enqueue proxy artifact event using response headers.
     * @param headers Response headers
     * @param key Artifact key
     * @param owner Owner login
     */
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

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body) {
        final String path = line.uri().getPath();
        // Handle root path requests - don't try to cache them as they would use Key.ROOT
        if ("/".equals(path) || path.isEmpty()) {
            return this.handleRootPath(line);
        }
        final Key key = new KeyFromPath(path);
        final Optional<CooldownRequest> request = this.cooldownRequest(headers, key);
        if (request.isEmpty()) {
            return this.fetchThroughCache(line, key, headers);
        }
        return this.cooldown.evaluate(request.get(), this.inspector)
            .thenCompose(result -> this.afterCooldown(result, line, key, headers));
    }

    private CompletableFuture<Response> afterCooldown(
        final CooldownResult result, final RequestLine line, final Key key,
        final Headers headers
    ) {
        if (result.blocked()) {
            return CompletableFuture.completedFuture(
                CooldownResponses.forbidden(result.block().orElseThrow())
            );
        }
        return this.fetchThroughCache(line, key, headers);
    }

    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Key key,
        final Headers request
    ) {
        // Check if this is a maven-metadata.xml request
        final String path = key.string();
        final boolean isMetadata = path.contains("maven-metadata.xml");
        
        // For metadata files, bypass cache and fetch directly from upstream
        if (isMetadata) {
            final String owner = new Login(request).getValue();
            Logger.info(this, "Bypassing cache for maven-metadata.xml: %s", path);
            return this.client.response(line, Headers.EMPTY, Content.EMPTY)
                .thenApply(resp -> {
                    if (resp.status().success()) {
                        this.enqueueFromHeaders(resp.headers(), key, owner);
                        return ResponseBuilder.ok()
                            .headers(resp.headers())
                            .body(resp.body())
                            .build();
                    }
                    return ResponseBuilder.notFound().build();
                });
        }
        
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

    private Optional<CooldownRequest> cooldownRequest(final Headers headers, final Key key) {
        final Matcher matcher = MavenSlice.ARTIFACT.matcher(key.string());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String pkg = matcher.group("pkg");
        final int idx = pkg.lastIndexOf('/');
        if (idx < 0 || idx == pkg.length() - 1) {
            return Optional.empty();
        }
        final String version = pkg.substring(idx + 1);
        final String artifact = MavenSlice.EVENT_INFO.formatArtifactName(pkg.substring(0, idx));
        final String user = new Login(headers).getValue();
        return Optional.of(
            new CooldownRequest(
                this.rtype,
                this.rname,
                artifact,
                version,
                user,
                Instant.now()
            )
        );
    }

    /**
     * Adds artifact data to events queue, if this queue is present.
     * Note, that
     * - checksums, javadoc and sources archives are excluded
     * - event key contains package name and version, for example 'com/artipie/asto/1.5'
     * It is possible, that the same package will be added to the queue twice
     * (as one maven package can contain pom, jar, war etc. at the same time), but will not
     * be duplicated as {@link ProxyArtifactEvent} with the same package key are considered as
     * equal.
     * @param key Artifact key
     */
    private void addEventToQueue(final Key key, final String owner) {
        this.addEventToQueue(key, owner, Optional.empty());
    }

    private void addEventToQueue(final Key key, final String owner, final Optional<Long> release) {
        if (this.events.isPresent()) {
            final Matcher matcher = MavenSlice.ARTIFACT.matcher(key.string());
            if (matcher.matches()) {
                this.events.get().add(
                    new ProxyArtifactEvent(
                        new Key.From(matcher.group("pkg")),
                        this.rname,
                        owner,
                        release
                    )
                );
            }
        }
    }

    /**
     * Checksum cache control verification.
     * @param header Checksum header
     * @return Cache control with digest
     */
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

    /**
     * Handles root path requests without using cache to avoid Key.ROOT issues.
     * @param line Request line
     * @return Response future
     */
    private CompletableFuture<Response> handleRootPath(final RequestLine line) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                if (resp.status().success()) {
                    this.addEventToQueue(new KeyFromPath("/index.html"),
                        com.artipie.scheduling.ArtifactEvent.DEF_OWNER);
                    return ResponseBuilder.ok()
                        .headers(resp.headers())
                        .body(resp.body())
                        .build();
                }
                return ResponseBuilder.notFound().build();
            });
    }
}
