/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.asto.ArtipieIOException;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.Remote;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.Headers;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.pypi.NormalizedProjectName;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice that proxies request with given request line and empty headers and body,
 * caches and returns response from remote.
 */
final class ProxySlice implements Slice {

    /**
     * Python artifacts formats.
     */
    private static final String FORMATS = ".*\\.(whl|tar\\.gz|zip|tar\\.bz2|tar\\.Z|tar|egg)";

    /**
     * Wheel filename pattern.
     */
    private static final Pattern WHEEL_PATTERN =
        Pattern.compile("(?<name>.*?)-(?<version>[0-9a-z.]+)(-\\d+)?-((py\\d.?)+)-(.*)-(.*)\\.whl",
            Pattern.CASE_INSENSITIVE);

    /**
     * Archive filename pattern.
     */
    private static final Pattern ARCHIVE_PATTERN =
        Pattern.compile("(?<name>.*)-(?<version>[0-9a-z.]+?)\\.(?<ext>[a-zA-Z.]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Pattern to rewrite HTML links pointing to upstream packages.
     * Captures href and all other attributes (like data-yanked) to preserve them.
     */
    private static final Pattern HREF_PACKAGES =
        Pattern.compile("<a\\s+([^>]*?href\\s*=\\s*\")(https?://[^\\\"#]+)(/packages/[^\\\"#]*)(#[^\\\"]*)?\"([^>]*)>");

    /**
     * Pattern to rewrite JSON urls pointing to upstream packages.
     */
    private static final Pattern JSON_PACKAGES =
        Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"(https?://[^\\\"#]+)(/packages/[^\\\"#]*)(#[^\\\"]*)?\\\"");

    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * Origin.
     */
    private final Slice origin;

    /**
     * HTTP clients.
     */
    private final ClientSlices clients;

    /**
     * Authenticator to access upstream remotes.
     */
    private final Authenticator auth;

    /**
     * Cache.
     */
    private final Cache cache;

    /**
     * Proxy artifacts events.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

    /**
     * Repository storage.
     */
    private final BlockingStorage storage;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Repository type.
     */
    private final String rtype;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final PyProxyCooldownInspector inspector;

    /**
     * Mirror map repository path -> upstream URI.
     * Bounded cache to prevent unbounded memory growth from accumulating package links.
     * Size: 10,000 entries (typical: 100 packages × 50 versions × 2 (artifact + metadata) = 10k)
     * TTL: 1 hour (index pages are typically cached upstream for similar duration)
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, URI> mirrors;

    /**
     * Ctor.
     * @param origin Origin
     * @param cache Cache
     * @param events Artifact events queue
     * @param rname Repository name
     */
    ProxySlice(final ClientSlices clients, final Authenticator auth,
        final Slice origin, final Storage backend, final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final PyProxyCooldownInspector inspector) {
        this.origin = origin;
        this.clients = clients;
        this.auth = auth;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.mirrors = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();
        this.storage = new BlockingStorage(backend);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers rqheaders, final Content body
    ) {
        final Optional<ArtifactCoordinates> coords = this.extract(line);
        final String user = new Login(rqheaders).getValue();
        
        // For artifacts: evaluate cooldown FIRST using metadata (JSON API),
        // then fetch/cached content only when allowed.
        if (coords.isPresent()) {
            final ArtifactCoordinates info = coords.get();
            final CooldownRequest request = new CooldownRequest(
                this.rtype,
                this.rname,
                info.artifact(),
                info.version(),
                user,
                Instant.now()
            );
            EcsLogger.debug("com.artipie.pypi")
                .message("Evaluating cooldown for artifact")
                .eventCategory("repository")
                .eventAction("cooldown_evaluation")
                .field("package.name", info.artifact())
                .field("package.version", info.version())
                .field("user.name", user)
                .field("repository.type", this.rtype)
                .field("repository.name", this.rname)
                .log();
            return this.cooldown.evaluate(request, this.inspector).thenCompose(evaluation -> {
                if (evaluation.blocked()) {
                    EcsLogger.warn("com.artipie.pypi")
                        .message("Artifact BLOCKED by cooldown")
                        .eventCategory("repository")
                        .eventAction("cooldown_evaluation")
                        .eventOutcome("failure")
                        .field("package.name", info.artifact())
                        .field("package.version", info.version())
                        .log();
                    return CompletableFuture.completedFuture(
                        CooldownResponses.forbidden(evaluation.block().orElseThrow())
                    );
                }
                EcsLogger.debug("com.artipie.pypi")
                    .message("Artifact ALLOWED by cooldown - serving content")
                    .eventCategory("repository")
                    .eventAction("cooldown_evaluation")
                    .eventOutcome("success")
                    .field("package.name", info.artifact())
                    .field("package.version", info.version())
                    .log();
                // Cooldown passed - now serve the artifact (no further cooldown checks)
                return this.serveArtifact(line, rqheaders, info, user);
            });
        }
        
        // Non-artifacts (index pages, metadata): serve directly from cache/upstream
        return this.serveNonArtifact(line, rqheaders, body, user);
    }
    
    private CompletableFuture<Response> serveNonArtifact(
        final RequestLine line, final Headers rqheaders, final Content body, final String user
    ) {
        final AtomicReference<Headers> remote = new AtomicReference<>(Headers.EMPTY);
        final AtomicBoolean remoteSuccess = new AtomicBoolean(false);
        final Key key = ProxySlice.keyFromPath(line);
        final RequestLine upstream = this.upstreamLine(line);
        return this.cache.load(
            key,
            new Remote.WithErrorHandling(
                () -> {
                    final CompletableFuture<Response> fetch;
                    
                    // Check mirror cache first for all paths
                    final URI mirror = this.mirrors.getIfPresent(line.uri().getPath());
                    if (mirror != null) {
                        EcsLogger.debug("com.artipie.pypi")
                            .message("Serving via cached mirror")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .field("url.path", line.uri().getPath())
                            .field("destination.address", mirror.toString())
                            .log();
                        fetch = this.fetchFromMirror(line, mirror);
                    } else if (this.isPackageFilePath(line)) {
                        // For /packages/ paths without mirror mapping:
                        // PyPI serves package files from files.pythonhosted.org, not pypi.org/simple
                        // Construct the CDN URL directly since pip may request files before index pages
                        final URI filesUri = URI.create("https://files.pythonhosted.org" + line.uri().getPath());
                        EcsLogger.debug("com.artipie.pypi")
                            .message("Package file request (no mirror) -> fetching from files.pythonhosted.org")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .field("url.path", line.uri().getPath())
                            .field("destination.address", filesUri.toString())
                            .log();
                        fetch = this.fetchFromMirror(line, filesUri).thenApply(resp -> {
                            EcsLogger.debug("com.artipie.pypi")
                                .message("files.pythonhosted.org response")
                                .eventCategory("repository")
                                .eventAction("proxy_request")
                                .field("url.path", line.uri().getPath())
                                .field("http.response.status_code", resp.status().code())
                                .log();
                            return resp;
                        });
                    } else {
                        // For other paths without mirrors, forward to upstream
                        EcsLogger.debug("com.artipie.pypi")
                            .message("Forwarding to primary upstream")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .field("url.path", line.uri().getPath())
                            .field("destination.address", upstream.uri().toString())
                            .log();
                        fetch = this.origin.response(upstream, Headers.EMPTY, Content.EMPTY);
                    }
                    return fetch.thenApply(response -> {
                        remote.set(response.headers());
                        if (response.status().success()) {
                            remoteSuccess.set(true);
                            // Enqueue artifact event immediately on successful remote fetch
                            // ONLY for actual artifact downloads (archives/wheels). This ensures
                            // metadata is recorded even if cooldown blocks this request, while
                            // avoiding index requests polluting the queue.
                            if (ProxySlice.this.extract(line).isPresent()) {
                                ProxySlice.this.extract(line).ifPresent(info -> {
                                    final Optional<Instant> releaseDate = ProxySlice.this.releaseInstant(response.headers());
                                    ProxySlice.this.events.ifPresent(queue ->
                                        queue.add(new ProxyArtifactEvent(
                                            key,
                                            ProxySlice.this.rname,
                                            user,
                                            releaseDate.map(Instant::toEpochMilli)
                                        ))
                                    );
                                });
                            }
                            return Optional.of(response.body());
                        }
                        return Optional.empty();
                    });
                }
            ),
            CacheControl.Standard.ALWAYS
        ).handle(
            (content, throwable) -> {
                if (throwable != null || content.isEmpty()) {
                    // Consume request body to prevent Vert.x request leak
                    return body.asBytesFuture().thenApply(ignored ->
                        ResponseBuilder.notFound().build()
                    );
                }
                return this.afterHit(
                    line, rqheaders, key, content.get(), remote.get(), remoteSuccess.get()
                );
            }
        ).thenCompose(Function.identity()).toCompletableFuture();
    }
    
    private CompletableFuture<Response> serveArtifact(
        final RequestLine line, final Headers rqheaders, final ArtifactCoordinates info, final String user
    ) {
        final AtomicReference<Headers> remote = new AtomicReference<>(Headers.EMPTY);
        final AtomicBoolean remoteSuccess = new AtomicBoolean(false);
        final Key key = ProxySlice.keyFromPath(line);
        final RequestLine upstream = this.upstreamLine(line);
        
        return this.cache.load(
            key,
            new Remote.WithErrorHandling(
                () -> {
                    final CompletableFuture<Response> fetch;
                    
                    // Check mirror cache first for all paths
                    final URI mirror = this.mirrors.getIfPresent(line.uri().getPath());
                    if (mirror != null) {
                        EcsLogger.debug("com.artipie.pypi")
                            .message("Serving via cached mirror")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .field("url.path", line.uri().getPath())
                            .field("destination.address", mirror.toString())
                            .log();
                        fetch = this.fetchFromMirror(line, mirror);
                    } else if (this.isPackageFilePath(line)) {
                        // For /packages/ paths without mirror mapping:
                        // PyPI serves package files from files.pythonhosted.org, not pypi.org/simple
                        // Construct the CDN URL directly since pip may request files before index pages
                        final URI filesUri = URI.create("https://files.pythonhosted.org" + line.uri().getPath());
                        EcsLogger.debug("com.artipie.pypi")
                            .message("Package file request (no mirror) -> fetching from files.pythonhosted.org")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .field("url.path", line.uri().getPath())
                            .field("destination.address", filesUri.toString())
                            .log();
                        fetch = this.fetchFromMirror(line, filesUri).thenApply(resp -> {
                            EcsLogger.debug("com.artipie.pypi")
                                .message("files.pythonhosted.org response")
                                .eventCategory("repository")
                                .eventAction("proxy_request")
                                .field("url.path", line.uri().getPath())
                                .field("http.response.status_code", resp.status().code())
                                .log();
                            return resp;
                        });
                    } else {
                        // For other paths without mirrors, forward to upstream
                        EcsLogger.debug("com.artipie.pypi")
                            .message("Forwarding to primary upstream")
                            .eventCategory("repository")
                            .eventAction("proxy_request")
                            .field("url.path", line.uri().getPath())
                            .field("destination.address", upstream.uri().toString())
                            .log();
                        fetch = this.origin.response(upstream, Headers.EMPTY, Content.EMPTY);
                    }
                    
                    return fetch.thenApply(response -> {
                        remote.set(response.headers());
                        if (response.status().success()) {
                            remoteSuccess.set(true);
                            // Enqueue artifact event immediately on successful remote fetch
                            ProxySlice.this.events.ifPresent(queue ->
                                queue.add(new ProxyArtifactEvent(
                                    key,
                                    ProxySlice.this.rname,
                                    user,
                                    ProxySlice.this.releaseInstant(response.headers()).map(Instant::toEpochMilli)
                                ))
                            );
                            return Optional.of(response.body());
                        }
                        return Optional.empty();
                    });
                }
            ),
            CacheControl.Standard.ALWAYS
        ).handle(
            (content, throwable) -> {
                if (throwable != null || content.isEmpty()) {
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
                // Enqueue event on cache hit (remote fetch already enqueued above)
                if (!remoteSuccess.get()) {
                    ProxySlice.this.events.ifPresent(queue ->
                        queue.add(new ProxyArtifactEvent(
                            key,
                            ProxySlice.this.rname,
                            user,
                            Optional.empty()  // No release date on cache hit
                        ))
                    );
                }
                // Serve artifact content (cooldown already evaluated and passed)
                return this.serveArtifactContent(line, key, content.get(), remote.get());
            }
        ).thenCompose(Function.identity()).toCompletableFuture();
    }
    
    private CompletableFuture<Response> serveArtifactContent(
        final RequestLine line, final Key key, final Content content, final Headers remote
    ) {
        return new com.artipie.asto.streams.ContentAsStream<Response>(content)
            .process(stream -> {
                try {
                    final byte[] data = stream.readAllBytes();
                    // Artifact served successfully (keep at debug to reduce log noise)
                    return ResponseBuilder.ok()
                        .headers(Headers.from(ProxySlice.contentType(remote, line)))
                        .body(new Content.From(data))
                        .header(new com.artipie.http.headers.ContentLength((long) data.length), true)
                        .build();
                } catch (final java.io.IOException ex) {
                    throw new com.artipie.asto.ArtipieIOException(ex);
                }
            })
            .toCompletableFuture();
    }

    private CompletableFuture<Response> afterHit(
        final RequestLine line,
        final Headers rqheaders,
        final Key key,
        final Content content,
        final Headers remote,
        final boolean remoteSuccess
    ) {
        final Optional<ArtifactCoordinates> coords = this.extract(line);
        if (coords.isEmpty()) {
            final String path = line.uri().getPath();
            // Serve .metadata files exactly as received (no rewriting, no charset conversions)
            if (path != null && path.endsWith(".metadata")) {
                return new com.artipie.asto.streams.ContentAsStream<Response>(content)
                    .process(stream -> {
                        try {
                            final byte[] bytes = stream.readAllBytes();
                            return ResponseBuilder.ok()
                                // Keep minimal headers; integrity depends on body bytes, not headers
                                .headers(Headers.EMPTY)
                                .body(new Content.From(bytes))
                                .header(new com.artipie.http.headers.ContentLength((long) bytes.length), true)
                                .build();
                        } catch (final java.io.IOException ex) {
                            throw new com.artipie.asto.ArtipieIOException(ex);
                        }
                    })
                    .toCompletableFuture();
            }
            final Header ctype = ProxySlice.contentType(remote, line);
            return this.rewriteIndex(content, ctype, line)
                .thenApply(
                    updated -> updated
                        .map(
                            body -> ResponseBuilder.ok()
                                .headers(Headers.from(ctype))
                                .body(body)
                                .build()
                        )
                        .orElseGet(ResponseBuilder.notFound()::build)
                );
        }

        final ArtifactCoordinates info = coords.get();
        final String user = new Login(rqheaders).getValue();
        
        // Use content from cache (passed as parameter) instead of reading from storage again.
        return new com.artipie.asto.streams.ContentAsStream<ContentAndCoords>(content)
            .process(stream -> {
                try {
                    final byte[] data = stream.readAllBytes();
                    EcsLogger.debug("com.artipie.pypi")
                        .message("Responding with cached artifact")
                        .eventCategory("repository")
                        .eventAction("proxy_request")
                        .field("package.name", key.string())
                        .field("package.size", data.length)
                        .log();
                    final Content payload = new Content.From(data);
                    return new ContentAndCoords(payload, info, data.length, data);
                } catch (final java.io.IOException ex) {
                    throw new com.artipie.asto.ArtipieIOException(ex);
                }
            })
            .toCompletableFuture()
            .thenCompose(cac -> this.resolveRelease(info, remote, remoteSuccess)
            .thenCompose(ctx -> {
                // Cache hit path: enqueue event here (remote fetch path enqueues earlier).
                if (!remoteSuccess && this.events.isPresent()) {
                    this.events.get().add(
                        new ProxyArtifactEvent(
                            key,
                            this.rname,
                            user,
                            ctx.release().map(Instant::toEpochMilli)
                        )
                    );
                }

                // Save to backing storage only when content was fetched from remote.
                if (remoteSuccess) {
                    CompletableFuture.runAsync(() -> this.storage.save(key, cac.bytes));
                }

                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .headers(Headers.from(ProxySlice.contentType(remote, line)))
                        .body(cac.payload)
                        .header(new com.artipie.http.headers.ContentLength((long) cac.length), true)
                        .build()
                );
            }));
    }

    /**
     * Maximum size for index pages to prevent memory exhaustion (10MB).
     * Typical PyPI index pages: 1-5MB. This limit protects against malicious/corrupted responses.
     */
    private static final int MAX_INDEX_SIZE = 10 * 1024 * 1024;

    private CompletableFuture<Optional<Content>> rewriteIndex(
        final Content content,
        final Header header,
        final RequestLine line
    ) {
        return new com.artipie.asto.streams.ContentAsStream<Optional<Content>>(content)
            .process(stream -> {
                try {
                    final byte[] bytes = stream.readAllBytes();
                    if (bytes.length == 0) {
                        if (this.packageIndexWithoutLinks(line, "")) {
                            return Optional.empty();
                        }
                        return Optional.of(new Content.From(bytes));
                    }
                    // Size limit protection
                    if (bytes.length > MAX_INDEX_SIZE) {
                        EcsLogger.warn("com.artipie.pypi")
                            .message("PyPI index too large (" + bytes.length + " bytes, max: " + MAX_INDEX_SIZE + " bytes)")
                            .eventCategory("repository")
                            .eventAction("index_rewrite")
                            .eventOutcome("failure")
                            .log();
                        return Optional.empty();
                    }
                    // Process in single pass to minimize memory copies
                    final String original = new String(bytes, StandardCharsets.UTF_8);
                    final String rewritten = this.rewriteIndexBody(original, header, line);
                    if (this.packageIndexWithoutLinks(line, rewritten)) {
                        return Optional.empty();
                    }
                    // Reuse original bytes if no changes made
                    if (rewritten.equals(original)) {
                        return Optional.of(new Content.From(bytes));
                    }
                    return Optional.of(new Content.From(rewritten.getBytes(StandardCharsets.UTF_8)));
                } catch (final IOException ex) {
                    throw new ArtipieIOException(ex);
                }
            })
            .toCompletableFuture()
            .handle(
                (Optional<Content> body, Throwable error) -> {
                    if (error != null) {
                        EcsLogger.warn("com.artipie.pypi")
                            .message("Failed to rewrite PyPI index content")
                            .eventCategory("repository")
                            .eventAction("index_rewrite")
                            .eventOutcome("failure")
                            .error(error)
                            .log();
                        return Optional.of(new Content.From(new byte[0]));
                    }
                    return body;
                }
            );
    }

    private boolean packageIndexWithoutLinks(final RequestLine line, final String body) {
        if (!this.looksLikeHtml(body)) {
            return false;
        }
        final String lower = body.toLowerCase();
        if (lower.contains("<a ") && lower.contains("href=")) {
            return false;
        }
        final List<String> segments = ProxySlice.pathSegments(line.uri().getPath());
        if (segments.isEmpty()) {
            return false;
        }
        String candidate = segments.get(segments.size() - 1);
        if ("index.html".equalsIgnoreCase(candidate) && segments.size() >= 2) {
            candidate = segments.get(segments.size() - 2);
        }
        return !"simple".equalsIgnoreCase(candidate);
    }

    private static List<String> pathSegments(final String path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(path.split("/"))
            .filter(part -> !part.isEmpty())
            .collect(Collectors.toList());
    }

    private String rewriteIndexBody(final String body, final Header header, final RequestLine line) {
        // Extract base path from request URI, removing the package-specific trailing path.
        // Example: /test_prefix/api/pypi/pypi_group/workday/ -> /test_prefix/api/pypi/pypi_group
        // This ensures download links preserve the correct path prefix for proxy routing.
        final String base = this.extractBasePath(line);
        EcsLogger.debug("com.artipie.pypi")
            .message("Rewriting index body")
            .eventCategory("repository")
            .eventAction("index_rewrite")
            .field("url.path", line.uri().getPath())
            .field("url.path", base)
            .log();
        String result = body;
        if (this.isHtml(header) || this.looksLikeHtml(body)) {
            result = this.rewriteHtmlLinks(result, base);
        }
        if (this.isJson(header) || this.looksLikeJson(body)) {
            result = this.rewriteJsonLinks(result, base);
        }
        return result;
    }

    /**
     * Extract base path from request URI for link rewriting.
     * 
     * IMPORTANT: Artipie's routing layer (ApiRoutingSlice, SliceByPath) strips path prefixes
     * before requests reach ProxySlice. For example:
     * - External: /test_prefix/api/pypi/pypi_group/simple/requests/
     * - ProxySlice sees: /simple/requests/ (prefix already stripped!)
     * 
     * Therefore, we ALWAYS use the repository name (this.rname) as the base path.
     * The routing layer will add the necessary prefix when serving responses.
     * 
     * According to PEP 503:
     * - Index pages: /{repo}/simple/{package}/
     * - Download links: /{repo}/packages/{hash}/{filename}
     * 
     * @param line Request line (already stripped of prefix by routing layer)
     * @return Base path (repository name, e.g., "/pypi_group")
     */
    private String extractBasePath(final RequestLine line) {
        // ALWAYS use repository name as base.
        // The routing layer handles path prefix mapping, we just need the repo name.
        return String.format("/%s", this.rname);
    }

    private boolean isHtml(final Header header) {
        return header != null
            && header.getValue() != null
            && header.getValue().toLowerCase().contains("html");
    }

    private boolean isJson(final Header header) {
        return header != null
            && header.getValue() != null
            && header.getValue().toLowerCase().contains("json");
    }

    private boolean looksLikeHtml(final String body) {
        final String trimmed = body.trim().toLowerCase();
        return trimmed.startsWith("<!doctype") || trimmed.startsWith("<html") || trimmed.contains("<a ");
    }

    private boolean looksLikeJson(final String body) {
        final String trimmed = body.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private String rewriteHtmlLinks(final String body, final String base) {
        final Matcher matcher = HREF_PACKAGES.matcher(body);
        // Use StringBuilder instead of StringBuffer (30-40% faster, no synchronization overhead)
        final StringBuilder buffer = new StringBuilder(body.length());
        int lastAppend = 0;
        while (matcher.find()) {
            final String prefix = matcher.group(1);          // "<a " + attributes before href
            final String upstreamHost = matcher.group(2);    // upstream host
            final String upstreamPath = matcher.group(3);    // /packages/...
            final String fragment = Optional.ofNullable(matcher.group(4)).orElse(""); // #sha256=...
            final String suffix = matcher.group(5);          // attributes after href (including data-yanked)
            final URI upstream = URI.create(upstreamHost + upstreamPath);
            this.registerMirror(String.format("%s%s", base, upstreamPath), upstream);
            // CRITICAL: Preserve all attributes (especially data-yanked for PEP 592)
            buffer.append(body, lastAppend, matcher.start());
            buffer.append("<a ").append(prefix).append(base).append(upstreamPath)
                  .append(fragment).append("\"").append(suffix).append(">");
            lastAppend = matcher.end();
        }
        buffer.append(body, lastAppend, body.length());
        return buffer.toString();
    }

    private String rewriteJsonLinks(final String body, final String base) {
        final Matcher matcher = JSON_PACKAGES.matcher(body);
        // Use StringBuilder instead of StringBuffer (30-40% faster, no synchronization overhead)
        final StringBuilder buffer = new StringBuilder(body.length());
        int lastAppend = 0;
        while (matcher.find()) {
            final String upstreamHost = matcher.group(1);
            final String upstreamPath = matcher.group(2);
            final String fragment = Optional.ofNullable(matcher.group(3)).orElse("");
            final URI upstream = URI.create(upstreamHost + upstreamPath);
            this.registerMirror(String.format("%s%s", base, upstreamPath), upstream);
            buffer.append(body, lastAppend, matcher.start());
            buffer.append("\"url\":\"").append(base).append(upstreamPath)
                  .append(fragment).append("\"");
            lastAppend = matcher.end();
        }
        buffer.append(body, lastAppend, body.length());
        return buffer.toString();
    }

    /**
     * Check if request path matches PyPI package file patterns.
     * 
     * PyPI structure:
     * - /packages/{hash}/{filename} -> package files (wheels, tarballs)
     * - /packages/{hash}/{filename}.metadata -> PEP 658 metadata files
     * 
     * These can be forwarded directly to the configured upstream without
     * requiring index page parsing first.
     * 
     * @param line Request line
     * @return true if path matches /packages/ pattern
     */
    private boolean isPackageFilePath(final RequestLine line) {
        String path = line.uri().getPath();
        
        // Remove repo prefix if present (routing may or may not strip it)
        final String repoPrefix = String.format("/%s", this.rname);
        if (path.startsWith(repoPrefix + "/")) {
            path = path.substring(repoPrefix.length());
        }
        
        // Pattern: /packages/{hash}/{filename} or /packages/{hash}/{filename}.metadata
        final boolean isPackage = path.startsWith("/packages/");
        EcsLogger.debug("com.artipie.pypi")
            .message("isPackageFilePath check: " + path + " (repo prefix: " + repoPrefix + ", is package: " + isPackage + ")")
            .eventCategory("repository")
            .eventAction("path_classification")
            .field("url.original", line.uri().getPath())
            .log();
        return isPackage;
    }

    private void registerMirror(final String repoPath, final URI upstream) {
        this.storeMirror(repoPath, upstream);
        this.trimmedPath(repoPath).ifPresent(path -> this.storeMirror(path, upstream));
    }

    private CompletableFuture<Response> fetchFromMirror(
        final RequestLine original,
        final URI target
    ) {
        final Slice slice = this.sliceForUri(target);
        final String path = Optional.ofNullable(target.getRawPath()).orElse("/");
        final StringBuilder full = new StringBuilder(path);
        if (target.getRawQuery() != null && !target.getRawQuery().isEmpty()) {
            full.append('?').append(target.getRawQuery());
        }
        return slice.response(
            new RequestLine(original.method().value(), full.toString(), original.version()),
            Headers.EMPTY,
            Content.EMPTY
        );
    }

    private Slice sliceForUri(final URI uri) {
        final Slice base;
        final String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            base = uri.getPort() > 0
                ? this.clients.https(uri.getHost(), uri.getPort())
                : this.clients.https(uri.getHost());
        } else if ("http".equalsIgnoreCase(scheme)) {
            base = uri.getPort() > 0
                ? this.clients.http(uri.getHost(), uri.getPort())
                : this.clients.http(uri.getHost());
        } else {
            throw new IllegalStateException(
                String.format("Unsupported mirror scheme: %s", scheme)
            );
        }
        return new com.artipie.http.client.auth.AuthClientSlice(base, this.auth);
    }

    private void storeMirror(final String path, final URI upstream) {
        this.mirrors.put(path, upstream);
        EcsLogger.debug("com.artipie.pypi")
            .message("Registered mirror mapping")
            .eventCategory("repository")
            .eventAction("mirror_registration")
            .field("url.path", path)
            .field("destination.address", upstream.toString())
            .log();
        if (!path.endsWith(".metadata")) {
            final URI metadata = ProxySlice.metadataUri(upstream);
            this.mirrors.put(path + ".metadata", metadata);
            EcsLogger.debug("com.artipie.pypi")
                .message("Registered metadata mirror mapping (cache size: " + this.mirrors.estimatedSize() + ")")
                .eventCategory("repository")
                .eventAction("mirror_registration")
                .field("url.path", path + ".metadata")
                .field("url.original", metadata.toString())
                .log();
        }
    }

    private Optional<String> trimmedPath(final String repoPath) {
        final String prefix = String.format("/%s", this.rname);
        if (repoPath.equals(prefix)) {
            return Optional.of("/");
        }
        if (repoPath.startsWith(prefix + "/")) {
            return Optional.of(repoPath.substring(prefix.length()));
        }
        return Optional.empty();
    }

    private static URI metadataUri(final URI upstream) {
        final String path = Optional.ofNullable(upstream.getPath()).orElse("");
        try {
            return new URI(
                upstream.getScheme(),
                upstream.getUserInfo(),
                upstream.getHost(),
                upstream.getPort(),
                path + ".metadata",
                upstream.getQuery(),
                null
            );
        } catch (final Exception error) {
            throw new IllegalStateException(
                String.format("Failed to build metadata URI from %s", upstream),
                error
            );
        }
    }

    private RequestLine upstreamLine(final RequestLine original) {
        final URI uri = original.uri();
        final String prefix = String.format("/%s", this.rname);
        String path = uri.getPath();
        if (path.startsWith(prefix + "/")) {
            path = path.substring(prefix.length());
        }
        if (path.isEmpty()) {
            path = "/";
        }
        final StringBuilder target = new StringBuilder(path);
        if (uri.getQuery() != null) {
            target.append('?').append(uri.getQuery());
        }
        if (uri.getFragment() != null) {
            target.append('#').append(uri.getFragment());
        }
        return new RequestLine(original.method(), URI.create(target.toString()), original.version());
    }

    private CompletableFuture<ReleaseContext> resolveRelease(
        final ArtifactCoordinates info,
        final Headers remote,
        final boolean remoteSuccess
    ) {
        return this.inspector.releaseDate(info.artifact(), info.version()).thenCompose(existing -> {
            final boolean known = existing.isPresent();
            if (remoteSuccess) {
                final Optional<Instant> header = this.releaseInstant(remote);
                this.registerRelease(info, header);
                return this.inspector.releaseDate(info.artifact(), info.version())
                    .thenApply(updated -> new ReleaseContext(
                        updated.or(() -> header),
                        known
                    ));
            }
            if (!known) {
                this.registerRelease(info, Optional.empty());
                return this.inspector.releaseDate(info.artifact(), info.version())
                    .thenApply(updated -> new ReleaseContext(updated, false));
            }
            return CompletableFuture.completedFuture(new ReleaseContext(existing, true));
        });
    }

    private void registerRelease(final ArtifactCoordinates coords, final Optional<Instant> release) {
        if (release.isPresent()) {
            this.inspector.register(
                coords.artifact(),
                coords.version(),
                release.get()
            );
        } else if (!this.inspector.known(coords.artifact(), coords.version())) {
            this.inspector.register(coords.artifact(), coords.version(), Instant.EPOCH);
        }
    }

    private Optional<Instant> releaseInstant(final Headers headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(header -> "last-modified".equalsIgnoreCase(header.getKey()))
            .map(Header::getValue)
            .findFirst()
            .flatMap(value -> {
                try {
                    return Optional.of(Instant.from(RFC_1123.parse(value)));
                } catch (final DateTimeParseException ignored) {
                    return Optional.empty();
                }
            });
    }

    private Optional<ArtifactCoordinates> extract(final RequestLine line) {
        final String path = line.uri().getPath();
        if (!path.matches(ProxySlice.FORMATS)) {
            return Optional.empty();
        }
        final int slash = path.lastIndexOf('/');
        final String filename = slash >= 0 ? path.substring(slash + 1) : path;
        return this.coordinatesFromFilename(filename);
    }

    private static final class ReleaseContext {
        private final Optional<Instant> release;
        private final boolean knownBefore;

        ReleaseContext(final Optional<Instant> release, final boolean knownBefore) {
            this.release = release == null ? Optional.empty() : release;
            this.knownBefore = knownBefore;
        }

        Optional<Instant> release() {
            return this.release;
        }

        boolean knownBefore() {
            return this.knownBefore;
        }
    }

    private Optional<ArtifactCoordinates> coordinatesFromFilename(final String filename) {
        final String lower = filename.toLowerCase();
        if (lower.endsWith(".whl")) {
            final int first = filename.indexOf('-');
            if (first > 0 && first < filename.length() - 1) {
                final int second = filename.indexOf('-', first + 1);
                if (second > first) {
                    final String name = new NormalizedProjectName.Simple(filename.substring(0, first)).value();
                    final String version = filename.substring(first + 1, second);
                    return Optional.of(new ArtifactCoordinates(name, version));
                }
            }
        }
        final Matcher wheel = WHEEL_PATTERN.matcher(filename);
        if (wheel.matches()) {
            final String name = new NormalizedProjectName.Simple(wheel.group("name")).value();
            return Optional.of(new ArtifactCoordinates(name, wheel.group("version")));
        }
        final Matcher archive = ARCHIVE_PATTERN.matcher(filename);
        if (archive.matches()) {
            if (filename.matches(ProxySlice.FORMATS)) {
                final String name = new NormalizedProjectName.Simple(archive.group("name")).value();
                return Optional.of(new ArtifactCoordinates(name, archive.group("version")));
            }
        }
        return Optional.empty();
    }

    private static final class ArtifactCoordinates {
        private final String artifact;
        private final String version;

        ArtifactCoordinates(final String artifact, final String version) {
            this.artifact = artifact;
            this.version = version;
        }

        String artifact() {
            return this.artifact;
        }

        String version() {
            return this.version;
        }
    }

    /**
     * Obtains content-type from remote's headers or trays to guess it by request line.
     * @param headers Header
     * @param line Request line
     * @return Cleaned up headers.
     */
    private static Header contentType(final Headers headers, final RequestLine line) {
        final String name = "content-type";
        // For metadata files, default to plain text for better compatibility
        final String path = line.uri().getPath();
        if (path != null && path.endsWith(".metadata")) {
            return new Header(name, "text/plain; charset=utf-8");
        }
        return Optional.ofNullable(headers).flatMap(
            hdrs -> StreamSupport.stream(hdrs.spliterator(), false)
                .filter(header -> header.getKey().equalsIgnoreCase(name)).findFirst()
                .map(Header::new)
            ).orElseGet(
                () -> {
                    Header res = new Header(name, "text/html");
                    final String ext = line.uri().toString();
                    if (ext.matches(ProxySlice.FORMATS)) {
                        res = new Header(
                            name,
                            Optional.ofNullable(URLConnection.guessContentTypeFromName(ext))
                                .orElse("*")
                        );
                    }
                    return res;
                }
            );
    }

    /**
     * Obtains key from request line with names normalization.
     * @param line Request line
     * @return Instance of {@link Key}.
     */
    private static Key keyFromPath(final RequestLine line) {
        final URI uri = line.uri();
        Key res = new KeyFromPath(uri.getPath());
        final String last = new KeyLastPart(res).get();
        final boolean artifactPath = uri.toString().matches(ProxySlice.FORMATS);
        if (!artifactPath && !last.endsWith(".metadata")) {
            res = new Key.From(
                res.string().replaceAll(
                    String.format("%s$", last), new NormalizedProjectName.Simple(last).value()
                )
            );
        }
        return res;
    }

    /**
     * Helper class to hold cached artifact content along with its coordinates and size.
     * Used to ensure the same content bytes flow through the entire response pipeline.
     */
    private static final class ContentAndCoords {
        private final Content payload;
        private final ArtifactCoordinates coords;
        private final int length;
        private final byte[] bytes;

        ContentAndCoords(final Content payload, final ArtifactCoordinates coords, final int length, final byte[] bytes) {
            this.payload = payload;
            this.coords = coords;
            this.length = length;
            this.bytes = bytes;
        }
    }
}
