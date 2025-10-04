/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.adapters.docker;

import com.artipie.asto.Content;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.docker.Digest;
import com.artipie.docker.Docker;
import com.artipie.docker.cache.DockerProxyCooldownInspector;
import com.artipie.docker.http.DigestHeader;
import com.artipie.docker.http.PathPatterns;
import com.artipie.docker.http.manifest.ManifestRequest;
import com.artipie.docker.manifest.Manifest;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

public final class DockerProxyCooldownSlice implements Slice {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerProxyCooldownSlice.class);

    private static final String DIGEST_HEADER = "Docker-Content-Digest";

    private static final String LAST_MODIFIED = "Last-Modified";

    private static final String DATE = "Date";

    private final Slice origin;

    private final String repoName;

    private final String repoType;

    private final CooldownService cooldown;

    private final DockerProxyCooldownInspector inspector;

    private final Docker docker;

    public DockerProxyCooldownSlice(
        final Slice origin,
        final String repoName,
        final String repoType,
        final CooldownService cooldown,
        final DockerProxyCooldownInspector inspector,
        final Docker docker
    ) {
        this.origin = origin;
        this.repoName = repoName;
        this.repoType = repoType;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.docker = docker;
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
                final CompletableFuture<byte[]> bytesFuture = response.body().asBytesFuture();
                return bytesFuture.thenCompose(bytes ->
                    this.determineRelease(request, response.headers(), bytes)
                        .exceptionally(ex -> {
                            LOGGER.warn("Failed to determine release for {}@{}: {}", artifact, version, ex.getMessage());
                            return Optional.empty();
                        })
                        .thenCompose(release -> {
                            release.ifPresent(inst -> {
                                this.inspector.recordRelease(artifact, version, inst);
                                digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, inst));
                            });
                            this.inspector.register(
                                artifact,
                                version,
                                release,
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
                            final Response rebuilt = new Response(
                                response.status(),
                                response.headers(),
                                new Content.From(bytes)
                            );
                            return this.cooldown.evaluate(cooldownRequest, this.inspector)
                                .thenApply(result -> result.blocked()
                                    ? CooldownResponses.forbidden(result.block().orElseThrow())
                                    : rebuilt
                                );
                        })
                ).exceptionally(ex -> {
                    LOGGER.warn("Failed to read manifest payload for {}@{}: {}", artifact, version, ex.getMessage());
                    this.inspector.register(artifact, version, Optional.empty(), user, this.repoName, digest);
                    return response;
                });
            });
    }

    private CompletableFuture<Optional<Instant>> determineRelease(
        final ManifestRequest request,
        final Headers headers,
        final byte[] manifestBytes
    ) {
        final Optional<Instant> headerRelease = this.release(headers);
        if (headerRelease.isPresent()) {
            return CompletableFuture.completedFuture(headerRelease);
        }
        final Optional<Manifest> manifest = this.manifestFrom(headers, manifestBytes);
        if (manifest.isEmpty() || manifest.get().isManifestList()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Manifest doc = manifest.get();
        return this.docker.repo(request.name()).layers().get(doc.config()).thenCompose(blob -> {
            if (blob.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return blob.get().content()
                .thenCompose(Content::asBytesFuture)
                .thenApply(this::extractCreatedInstant);
        });
    }

    private Optional<Manifest> manifestFrom(final Headers headers, final byte[] bytes) {
        try {
            final Digest digest = new DigestHeader(headers).value();
            return Optional.of(new Manifest(digest, bytes));
        } catch (final IllegalArgumentException ex) {
            LOGGER.warn("Failed to build manifest from response headers: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Instant> extractCreatedInstant(final byte[] config) {
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(config))) {
            final JsonObject json = reader.readObject();
            final String created = json.getString("created", null);
            if (created != null && !created.isEmpty()) {
                return Optional.of(Instant.parse(created));
            }
        } catch (final DateTimeParseException | JsonException ex) {
            LOGGER.debug("Unable to parse manifest config `created` field: {}", ex.getMessage());
        }
        return Optional.empty();
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
