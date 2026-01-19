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
import com.artipie.http.log.EcsLogger;

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
                
                // Read body once, then evaluate cooldown + extract metadata in parallel
                final CompletableFuture<byte[]> bytesFuture = response.body().asBytesFuture();
                return bytesFuture.thenCompose(bytes -> {
                    // Rebuild response immediately with buffered bytes
                    final Response rebuilt = new Response(
                        response.status(),
                        response.headers(),
                        new Content.From(bytes)
                    );
                    
                    // Extract release date from headers first (fast path)
                    final Optional<Instant> headerRelease = this.release(response.headers());
                    
                    // If we have release date from headers, use it immediately
                    if (headerRelease.isPresent()) {
                        this.inspector.recordRelease(artifact, version, headerRelease.get());
                        digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, headerRelease.get()));
                        this.inspector.register(
                            artifact, version, headerRelease,
                            user, this.repoName, digest
                        );
                        
                        // Evaluate cooldown with known release date
                        final CooldownRequest cooldownRequest = new CooldownRequest(
                            this.repoType, this.repoName,
                            artifact, version, user, Instant.now()
                        );
                        return this.cooldown.evaluate(cooldownRequest, this.inspector)
                            .thenApply(result -> result.blocked()
                                ? CooldownResponses.forbidden(result.block().orElseThrow())
                                : rebuilt
                            );
                    }
                    
                    // No release date in headers - extract from manifest config
                    // Check if we've seen this artifact before (cached from previous request)
                    if (this.inspector.known(artifact, version)) {
                        // Already cached - evaluate immediately
                        final CooldownRequest cooldownRequest = new CooldownRequest(
                            this.repoType, this.repoName,
                            artifact, version, user, Instant.now()
                        );
                        return this.cooldown.evaluate(cooldownRequest, this.inspector)
                            .thenApply(result -> result.blocked()
                                ? CooldownResponses.forbidden(result.block().orElseThrow())
                                : rebuilt
                            );
                    }
                    
                    // First time seeing this artifact - WAIT for extraction then evaluate
                    return this.determineReleaseSync(request, response.headers(), bytes, artifact, version, digest, user)
                        .thenCompose(release -> {
                            this.inspector.register(
                                artifact, version, release,
                                user, this.repoName, digest
                            );
                            final CooldownRequest cooldownRequest = new CooldownRequest(
                                this.repoType, this.repoName,
                                artifact, version, user, Instant.now()
                            );
                            return this.cooldown.evaluate(cooldownRequest, this.inspector)
                                .thenApply(result -> result.blocked()
                                    ? CooldownResponses.forbidden(result.block().orElseThrow())
                                    : rebuilt
                                );
                        });
                }).exceptionally(ex -> {
                    EcsLogger.warn("com.artipie.docker")
                        .message("Failed to process manifest")
                        .eventCategory("docker")
                        .eventAction("manifest_process")
                        .eventOutcome("failure")
                        .field("package.name", artifact)
                        .field("package.version", version)
                        .error(ex)
                        .log();
                    // Register with empty release date on error
                    this.inspector.register(artifact, version, Optional.empty(), user, this.repoName, digest);
                    return response;
                });
            });
    }

    /**
     * Extract release date from manifest config synchronously.
     * Waits for extraction to complete before returning.
     * Used on first request to properly evaluate cooldown.
     * 
     * @param request Manifest request
     * @param headers Response headers
     * @param manifestBytes Manifest body bytes
     * @param artifact Artifact name
     * @param version Version/digest
     * @param digest Optional digest
     * @param user Requesting user
     * @return CompletableFuture with optional release date
     */
    private CompletableFuture<Optional<Instant>> determineReleaseSync(
        final ManifestRequest request,
        final Headers headers,
        final byte[] manifestBytes,
        final String artifact,
        final String version,
        final Optional<String> digest,
        final String user
    ) {
        final Optional<Manifest> manifest = this.manifestFrom(headers, manifestBytes);
        if (manifest.isEmpty() || manifest.get().isManifestList()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Manifest doc = manifest.get();
        
        // Fetch config blob and extract created timestamp
        return this.docker.repo(request.name()).layers().get(doc.config()).thenCompose(blob -> {
            if (blob.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Instant>empty());
            }
            return blob.get().content()
                .thenCompose(Content::asBytesFuture)
                .thenApply(this::extractCreatedInstant);
        }).whenComplete((release, error) -> {
            if (error != null) {
                EcsLogger.warn("com.artipie.docker")
                    .message("Failed to extract release date from config")
                    .eventCategory("docker")
                    .eventAction("release_date_extract")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .error(error)
                    .log();
            } else if (release.isPresent()) {
                EcsLogger.debug("com.artipie.docker")
                    .message("Extracted release date from config")
                    .eventCategory("docker")
                    .eventAction("release_date_extract")
                    .eventOutcome("success")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("package.release_date", release.get().toString())
                    .log();
                // Also record by digest
                digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, release.get()));
            }
        }).exceptionally(ex -> {
            EcsLogger.warn("com.artipie.docker")
                .message("Exception extracting release date")
                .eventCategory("docker")
                .eventAction("release_date_extract")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .error(ex)
                .log();
            return Optional.empty();
        });
    }

    /**
     * Extract release date from manifest config in background.
     * This is async and doesn't block the response - it updates the inspector when done.
     */
    private void determineReleaseBackground(
        final ManifestRequest request,
        final Headers headers,
        final byte[] manifestBytes,
        final String artifact,
        final String version,
        final Optional<String> digest
    ) {
        final Optional<Manifest> manifest = this.manifestFrom(headers, manifestBytes);
        if (manifest.isEmpty() || manifest.get().isManifestList()) {
            return;
        }
        final Manifest doc = manifest.get();
        
        // Async extraction - runs in background, doesn't block response
        this.docker.repo(request.name()).layers().get(doc.config()).thenCompose(blob -> {
            if (blob.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Instant>empty());
            }
            return blob.get().content()
                .thenCompose(Content::asBytesFuture)
                .thenApply(this::extractCreatedInstant);
        }).thenAccept(release -> {
            if (release.isPresent()) {
                EcsLogger.debug("com.artipie.docker")
                    .message("Extracted release date from config")
                    .eventCategory("docker")
                    .eventAction("release_date_extract")
                    .eventOutcome("success")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("package.release_date", release.get().toString())
                    .log();
                this.inspector.recordRelease(artifact, version, release.get());
                digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, release.get()));
            }
        }).exceptionally(ex -> {
            EcsLogger.debug("com.artipie.docker")
                .message("Failed to extract release date from config")
                .eventCategory("docker")
                .eventAction("release_date_extract")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .error(ex)
                .log();
            return null;
        });
    }

    private Optional<Manifest> manifestFrom(final Headers headers, final byte[] bytes) {
        try {
            final Digest digest = new DigestHeader(headers).value();
            return Optional.of(new Manifest(digest, bytes));
        } catch (final IllegalArgumentException ex) {
            EcsLogger.warn("com.artipie.docker")
                .message("Failed to build manifest from response headers")
                .eventCategory("docker")
                .eventAction("manifest_build")
                .eventOutcome("failure")
                .error(ex)
                .log();
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
            EcsLogger.debug("com.artipie.docker")
                .message("Unable to parse manifest config created field")
                .eventCategory("docker")
                .eventAction("manifest_parse")
                .eventOutcome("failure")
                .error(ex)
                .log();
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
