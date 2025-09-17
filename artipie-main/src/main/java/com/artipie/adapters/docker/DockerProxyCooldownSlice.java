/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.docker;

import com.artipie.asto.Content;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.docker.cache.DockerProxyCooldownInspector;
import com.artipie.docker.http.PathPatterns;
import com.artipie.docker.http.manifest.ManifestRequest;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

public final class DockerProxyCooldownSlice implements Slice {

    private static final String DIGEST_HEADER = "Docker-Content-Digest";

    private static final String LAST_MODIFIED = "Last-Modified";

    private static final String DATE = "Date";

    private final Slice origin;

    private final String repoName;

    private final String repoType;

    private final CooldownService cooldown;

    private final DockerProxyCooldownInspector inspector;

    public DockerProxyCooldownSlice(
        final Slice origin,
        final String repoName,
        final String repoType,
        final CooldownService cooldown,
        final DockerProxyCooldownInspector inspector
    ) {
        this.origin = origin;
        this.repoName = repoName;
        this.repoType = repoType;
        this.cooldown = cooldown;
        this.inspector = inspector;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        if (!this.shouldInspect(line)) {
            return this.origin.response(line, headers, body);
        }
        final ManifestRequest request;
        try {
            request = ManifestRequest.from(line);
        } catch (final IllegalArgumentException ignored) {
            return this.origin.response(line, headers, body);
        }
        return this.origin.response(line, headers, body)
            .thenCompose(response -> {
                if (!response.status().success()) {
                    return CompletableFuture.completedFuture(response);
                }
                final String artifact = request.name();
                final String version = request.reference().digest();
                final String user = new Login(headers).getValue();
                final Optional<String> digest = this.digest(response.headers());
                final Optional<Instant> release = this.release(response.headers());
                final Optional<Instant> registered =
                    release.isPresent() ? release
                        : (this.inspector.known(artifact, version)
                        ? Optional.empty() : Optional.of(Instant.EPOCH));
                this.inspector.register(
                    artifact,
                    version,
                    registered,
                    user,
                    this.repoName,
                    digest
                );
                final CooldownRequest cooldownRequest = new CooldownRequest(
                    this.repoType,
                    this.repoName,
                    artifact,
                    version,
                    user,
                    Instant.now()
                );
                return this.cooldown.evaluate(cooldownRequest, this.inspector)
                    .thenApply(result -> result.blocked()
                        ? CooldownResponses.forbidden(result.block().orElseThrow())
                        : response
                    );
            });
    }

    private boolean shouldInspect(final RequestLine line) {
        return line.method() == RqMethod.GET
            && PathPatterns.MANIFESTS.matcher(line.uri().getPath()).matches();
    }

    private Optional<String> digest(final Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(header -> DIGEST_HEADER.equalsIgnoreCase(header.getKey()))
            .map(Header::getValue)
            .findFirst();
    }

    private Optional<Instant> release(final Headers headers) {
        return this.firstHeader(headers, LAST_MODIFIED)
            .or(() -> this.firstHeader(headers, DATE))
            .flatMap(value -> {
                try {
                    return Optional.of(Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)));
                } catch (final DateTimeParseException ignored) {
                    return Optional.empty();
                }
            });
    }

    private Optional<String> firstHeader(final Headers headers, final String name) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(header -> name.equalsIgnoreCase(header.getKey()))
            .map(Header::getValue)
            .findFirst();
    }
}
