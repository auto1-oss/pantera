/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.DigestVerification;
import com.artipie.asto.cache.Remote;
import com.artipie.asto.ext.Digests;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownInspector;
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
 * Go proxy slice with cache support.
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
     * Pattern to match Go module artifacts.
     * Matches: module/path/@v/v1.2.3.{info|mod|zip}
     */
    private static final Pattern ARTIFACT = Pattern.compile(
        "^(?<module>.+)/@v/v(?<version>[^/]+)\\.(?<ext>info|mod|zip)$"
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
     *
     * @param client Client slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param rtype Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     */
    CachedProxySlice(
        final Slice client,
        final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final CooldownInspector inspector
    ) {
        this.client = client;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        Logger.info(this, "DEBUG: Processing Go proxy request path: %s", path);
        
        if ("/".equals(path) || path.isEmpty()) {
            Logger.debug(this, "DEBUG: Handling root path");
            return this.handleRootPath(line);
        }
        final Key key = new KeyFromPath(path);
        final Matcher matcher = ARTIFACT.matcher(key.string());
        
        // For non-artifact paths (e.g., list endpoints), skip cooldown and cache directly
        if (!matcher.matches()) {
            Logger.debug(this, "DEBUG: Non-artifact path, skipping cooldown: %s", key.string());
            return this.fetchThroughCache(line, key, headers, Optional.empty(), Optional.empty());
        }
        
        // Extract artifact info and create cooldown request
        final String module = matcher.group("module");
        final String version = matcher.group("version");
        final String user = new Login(headers).getValue();
        Logger.info(this, "DEBUG: Go artifact request - module: %s, version: %s, user: %s", module, version, user);
        
        final CooldownRequest request = new CooldownRequest(
            this.rtype,
            this.rname,
            module,
            version,
            user,
            Instant.now()
        );
        
        // Check cooldown FIRST - get release date and evaluate
        return this.cooldown.evaluate(request, this.inspector)
            .thenCompose(result -> {
                if (result.blocked()) {
                    Logger.info(
                        this,
                        "DEBUG: Blocked Go artifact %s@v%s due to cooldown: %s",
                        module,
                        version,
                        result.block().orElseThrow().reason()
                    );
                    return CompletableFuture.completedFuture(
                        CooldownResponses.forbidden(result.block().orElseThrow())
                    );
                }
                Logger.info(this, "DEBUG: Cooldown passed for %s@v%s, proceeding with fetch", module, version);
                // Cooldown passed, proceed with fetch
                // Get the release date for database event
                return this.inspector.releaseDate(module, version)
                    .thenCompose(releaseDate -> {
                        Logger.debug(this, "DEBUG: Release date for %s@v%s: %s", module, version, releaseDate.orElse(null));
                        return this.fetchThroughCache(
                            line, 
                            key, 
                            headers, 
                            Optional.of(module + "/@v/" + version),
                            releaseDate
                        );
                    });
            });
    }


    private CompletableFuture<Response> fetchThroughCache(
        final RequestLine line,
        final Key key,
        final Headers request,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate
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
                                        promise.complete(Optional.of(new Content.From(res)));
                                    } else {
                                        // CRITICAL: Consume body to prevent Vert.x request leak
                                        resp.body().asBytesFuture().whenComplete((ignored, error) -> {
                                            promise.complete(Optional.empty());
                                            term.complete(null);
                                        });
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
                            // Record database event ONLY after successful cache load for .zip files
                            // This ensures the full module is downloaded before recording the event
                            if (key.string().endsWith(".zip") && artifactPath.isPresent()) {
                                Logger.info(
                                    this,
                                    "DEBUG: Attempting to enqueue Go proxy event for key: %s, artifactPath: %s, owner: %s",
                                    key.string(),
                                    artifactPath.get(),
                                    owner
                                );
                                this.enqueueEvent(
                                    key, 
                                    owner, 
                                    artifactPath, 
                                    releaseDate.or(() -> this.parseLastModified(rshdr.get()))
                                );
                            } else {
                                Logger.debug(
                                    this,
                                    "DEBUG: Skipping event enqueue - key: %s, isZip: %s, hasArtifactPath: %s",
                                    key.string(),
                                    key.string().endsWith(".zip"),
                                    artifactPath.isPresent()
                                );
                            }
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


    /**
     * Parse Last-Modified header to Instant.
     *
     * @param headers Response headers
     * @return Optional Instant
     */
    private Optional<Instant> parseLastModified(final Headers headers) {
        try {
            return StreamSupport.stream(headers.spliterator(), false)
                .filter(h -> "Last-Modified".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .map(val -> Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(val)));
        } catch (final DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Enqueue artifact event for metadata processing.
     * Only enqueues for actual artifacts (not list endpoints).
     *
     * @param key Artifact key
     * @param owner Owner username
     * @param artifactPath Optional artifact path (module/@v/version)
     * @param releaseDate Optional release date
     */
    private void enqueueEvent(
        final Key key,
        final String owner,
        final Optional<String> artifactPath,
        final Optional<Instant> releaseDate
    ) {
        // Only enqueue if this is an actual artifact (has artifactPath)
        if (artifactPath.isEmpty()) {
            return;
        }
        this.addEventToQueue(
            new Key.From(artifactPath.get()),
            owner,
            releaseDate.map(Instant::toEpochMilli)
        );
    }

    /**
     * Add event to queue for background processing.
     * The event will be processed by GoProxyPackageProcessor to write metadata to database.
     *
     * @param key Artifact key (should be in format: module/@v/version)
     * @param owner Owner username
     * @param release Optional release timestamp in millis
     */
    private void addEventToQueue(final Key key, final String owner, final Optional<Long> release) {
        if (this.events.isEmpty()) {
            Logger.error(this, "DEBUG: Events queue is NOT present - cannot enqueue events!");
            return;
        }
        
        this.events.ifPresent(queue -> {
            final ProxyArtifactEvent event = new ProxyArtifactEvent(
                key,
                this.rname,
                owner,
                release
            );
            queue.add(event);
            Logger.info(
                this,
                "DEBUG: Successfully enqueued Go proxy event - key: %s, repo: %s, owner: %s, release: %s, queue size: %d",
                key.string(),
                this.rname,
                owner,
                release.map(Object::toString).orElse("unknown"),
                queue.size()
            );
        });
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
}
