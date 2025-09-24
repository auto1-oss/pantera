/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.Remote;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.pypi.NormalizedProjectName;
import com.artipie.scheduling.ProxyArtifactEvent;

import java.net.URI;
import java.net.URLConnection;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
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

    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * Origin.
     */
    private final Slice origin;

    /**
     * Cache.
     */
    private final Cache cache;

    /**
     * Proxy artifacts events.
     */
    private final Optional<Queue<ProxyArtifactEvent>> events;

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
     * Ctor.
     * @param origin Origin
     * @param cache Cache
     * @param events Artifact events queue
     * @param rname Repository name
     */
    ProxySlice(final Slice origin, final Cache cache,
        final Optional<Queue<ProxyArtifactEvent>> events,
        final String rname,
        final String rtype,
        final CooldownService cooldown,
        final PyProxyCooldownInspector inspector) {
        this.origin = origin;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.rtype = rtype;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers rqheaders, final Content body
    ) {
        final AtomicReference<Headers> remote = new AtomicReference<>(Headers.EMPTY);
        final Key key = ProxySlice.keyFromPath(line);
        return this.cache.load(
            key,
            new Remote.WithErrorHandling(
                () -> this.origin
                    .response(line, Headers.EMPTY, Content.EMPTY)
                    .thenApply(response -> {
                        remote.set(response.headers());
                        if (response.status().success()) {
                            return Optional.of(response.body());
                        }
                        return Optional.empty();
                    })
            ),
            CacheControl.Standard.ALWAYS
        ).handle(
            (content, throwable) -> {
                if (throwable != null || content.isEmpty()) {
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
                return this.afterHit(line, rqheaders, key, content.get(), remote.get());
            }
        ).thenCompose(Function.identity()).toCompletableFuture();
    }

    private CompletableFuture<Response> afterHit(
        final RequestLine line,
        final Headers rqheaders,
        final Key key,
        final Content content,
        final Headers remote
    ) {
        final Optional<ArtifactCoordinates> coords = this.extract(line);
        if (coords.isEmpty()) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(Headers.from(ProxySlice.contentType(remote, line)))
                    .body(content)
                    .build()
            );
        }
        final ArtifactCoordinates info = coords.get();
        final String user = new Login(rqheaders).getValue();
        this.registerRelease(info, remote);
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
            final java.util.Optional<Long> millis = this.releaseInstant(remote).map(java.time.Instant::toEpochMilli);
            this.events.ifPresent(queue -> queue.add(new ProxyArtifactEvent(key, this.rname, user, millis)));
            return ResponseBuilder.ok()
                .headers(Headers.from(ProxySlice.contentType(remote, line)))
                .body(content)
                .build();
        });
    }

    private void registerRelease(final ArtifactCoordinates coords, final Headers remote) {
        final Optional<Instant> release = this.releaseInstant(remote);
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
        final String[] parts = path.split("/");
        if (parts.length < 3) {
            return Optional.empty();
        }
        final String project = parts[parts.length - 2];
        final String filename = parts[parts.length - 1];
        return this.versionFromFilename(project, filename)
            .map(version -> new ArtifactCoordinates(
                new NormalizedProjectName.Simple(project).value(),
                version
            ));
    }

    private Optional<String> versionFromFilename(final String project, final String filename) {
        final String normalized = new NormalizedProjectName.Simple(project).value();
        final Matcher wheel = WHEEL_PATTERN.matcher(filename);
        if (wheel.matches()) {
            final String name = new NormalizedProjectName.Simple(wheel.group("name")).value();
            if (normalized.equals(name)) {
                return Optional.of(wheel.group("version"));
            }
        }
        final Matcher archive = ARCHIVE_PATTERN.matcher(filename);
        if (archive.matches()) {
            if (filename.matches(ProxySlice.FORMATS)) {
                final String name = new NormalizedProjectName.Simple(archive.group("name")).value();
                if (normalized.equals(name)) {
                    return Optional.of(archive.group("version"));
                }
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
        if (!uri.toString().matches(ProxySlice.FORMATS)) {
            final String last = new KeyLastPart(res).get();
            res = new Key.From(
                res.string().replaceAll(
                    String.format("%s$", last), new NormalizedProjectName.Simple(last).value()
                )
            );
        }
        return res;
    }
}
