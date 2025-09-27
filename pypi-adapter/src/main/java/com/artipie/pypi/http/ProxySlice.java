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
import com.jcabi.log.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.StreamSupport;
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
     */
    private static final Pattern HREF_PACKAGES =
        Pattern.compile("href\\s*=\\s*\"(https?://[^\\\"#]+)(/packages/[^\\\"#]*)(#[^\\\"]*)?\"");

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
     */
    private final ConcurrentMap<String, URI> mirrors;

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
        this.mirrors = new ConcurrentHashMap<>(0);
        this.storage = new BlockingStorage(backend);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers rqheaders, final Content body
    ) {
        final AtomicReference<Headers> remote = new AtomicReference<>(Headers.EMPTY);
        final AtomicBoolean remoteSuccess = new AtomicBoolean(false);
        final Key key = ProxySlice.keyFromPath(line);
        final RequestLine upstream = this.upstreamLine(line);
        return this.cache.load(
            key,
            new Remote.WithErrorHandling(
                () -> {
                    final URI mirror = this.mirrors.get(line.uri().getPath());
                    final CompletableFuture<Response> fetch;
                    if (mirror != null) {
                        Logger.debug(
                            this,
                            "Serving %s via mirror %s",
                            line.uri().getPath(),
                            mirror
                        );
                        fetch = this.fetchFromMirror(line, mirror);
                    } else {
                        Logger.warn(
                            this,
                            "Mirror lookup missed for %s; known mirrors: %s",
                            line.uri().getPath(),
                            this.mirrors.keySet()
                        );
                        Logger.debug(
                            this,
                            "Forwarding %s to primary upstream %s",
                            line.uri().getPath(),
                            upstream.uri()
                        );
                        fetch = this.origin.response(upstream, Headers.EMPTY, Content.EMPTY);
                    }
                    return fetch.thenApply(response -> {
                        remote.set(response.headers());
                        if (response.status().success()) {
                            remoteSuccess.set(true);
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
                return this.afterHit(
                    line, rqheaders, key, content.get(), remote.get(), remoteSuccess.get()
                );
            }
        ).thenCompose(Function.identity()).toCompletableFuture();
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
            final Header ctype = ProxySlice.contentType(remote, line);
            return this.rewriteIndex(content, ctype)
                .thenApply(
                    updated -> ResponseBuilder.ok()
                        .headers(Headers.from(ctype))
                        .body(updated)
                        .build()
                );
        }

        final ArtifactCoordinates info = coords.get();
        final String user = new Login(rqheaders).getValue();
        final byte[] data = this.storage.value(key);
        Logger.warn(
            this,
            "Responding with cached artifact %s (%d bytes)",
            key,
            data.length
        );
        final Content payload = new Content.From(data);

        return this.resolveRelease(info, remote, remoteSuccess)
            .thenCompose(ctx -> {
                final CooldownRequest request = new CooldownRequest(
                    this.rtype,
                    this.rname,
                    info.artifact(),
                    info.version(),
                    user,
                    Instant.now()
                );

                return this.cooldown.evaluate(request, this.inspector).thenApply(result -> {
                    if (result.blocked()) {
                        return CooldownResponses.forbidden(result.block().orElseThrow());
                    }

                    if (remoteSuccess || !ctx.knownBefore()) {
                        this.events.ifPresent(queue ->
                            queue.add(new ProxyArtifactEvent(
                                key,
                                this.rname,
                                user,
                                ctx.release().map(Instant::toEpochMilli)
                            ))
                        );
                    }

                    return ResponseBuilder.ok()
                        .headers(Headers.from(ProxySlice.contentType(remote, line)))
                        .body(payload)
                        .header(new com.artipie.http.headers.ContentLength((long) data.length), true)
                        .build();
                });
            });
    }

    private CompletableFuture<Content> rewriteIndex(final Content content, final Header header) {
        return new com.artipie.asto.streams.ContentAsStream<Content>(content)
            .process(stream -> {
                try {
                    final byte[] bytes = stream.readAllBytes();
                    if (bytes.length == 0) {
                        return new Content.From(bytes);
                    }
                    final String original = new String(bytes, StandardCharsets.UTF_8);
                    final String rewritten = this.rewriteIndexBody(original, header);
                    return new Content.From(rewritten.getBytes(StandardCharsets.UTF_8));
                } catch (final IOException ex) {
                    throw new ArtipieIOException(ex);
                }
            })
            .toCompletableFuture()
            .handle(
                (Content body, Throwable error) -> {
                    if (error != null) {
                        Logger.warn(
                            this,
                            "Failed to rewrite PyPI index content: %s",
                            error.getMessage()
                        );
                        return body == null ? new Content.From(new byte[0]) : body;
                    }
                    return body;
                }
            );
    }

    private String rewriteIndexBody(final String body, final Header header) {
        final String base = String.format("/%s", this.rname);
        String result = body;
        if (this.isHtml(header) || this.looksLikeHtml(body)) {
            result = this.rewriteHtmlLinks(result, base);
        }
        if (this.isJson(header) || this.looksLikeJson(body)) {
            result = this.rewriteJsonLinks(result, base);
        }
        return result;
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
        final StringBuffer buffer = new StringBuffer(body.length());
        while (matcher.find()) {
            final String upstreamHost = matcher.group(1);
            final String upstreamPath = matcher.group(2);
            final String fragment = Optional.ofNullable(matcher.group(3)).orElse("");
            final URI upstream = URI.create(upstreamHost + upstreamPath);
            this.registerMirror(String.format("%s%s", base, upstreamPath), upstream);
            matcher.appendReplacement(
                buffer,
                Matcher.quoteReplacement(
                    String.format("href=\"%s%s%s\"", base, upstreamPath, fragment)
                )
            );
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String rewriteJsonLinks(final String body, final String base) {
        final Matcher matcher = JSON_PACKAGES.matcher(body);
        final StringBuffer buffer = new StringBuffer(body.length());
        while (matcher.find()) {
            final String upstreamHost = matcher.group(1);
            final String upstreamPath = matcher.group(2);
            final String fragment = Optional.ofNullable(matcher.group(3)).orElse("");
            final URI upstream = URI.create(upstreamHost + upstreamPath);
            this.registerMirror(String.format("%s%s", base, upstreamPath), upstream);
            matcher.appendReplacement(
                buffer,
                Matcher.quoteReplacement(
                    String.format("\"url\":\"%s%s%s\"", base, upstreamPath, fragment)
                )
            );
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
        Logger.debug(this, "Registered mirror mapping %s -> %s", path, upstream);
        if (!path.endsWith(".metadata")) {
            final URI metadata = ProxySlice.metadataUri(upstream);
            this.mirrors.put(path + ".metadata", metadata);
            Logger.debug(
                this,
                "Registered metadata mirror mapping %s -> %s",
                path + ".metadata",
                metadata
            );
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
}
