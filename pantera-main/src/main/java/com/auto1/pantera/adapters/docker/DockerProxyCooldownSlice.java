/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.docker.cooldown.DockerManifestByTagHandler;
import com.auto1.pantera.docker.cooldown.DockerManifestByTagMetadataRequestDetector;
import com.auto1.pantera.docker.cooldown.DockerMetadataRequestDetector;
import com.auto1.pantera.docker.cooldown.DockerTagsListHandler;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.http.manifest.ManifestRequest;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.log.EcsLogger;

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

    /**
     * Handler for {@code /v2/<name>/tags/list} — filters cooldown-blocked
     * tags out of the JSON response. Prior to this wiring the Docker
     * bundle registered in {@code CooldownWiring} was dead code.
     */
    private final DockerTagsListHandler tagsHandler;

    /**
     * Detector for {@code /v2/<name>/tags/list} paths.
     */
    private final DockerMetadataRequestDetector tagsDetector;

    /**
     * Handler for {@code /v2/<name>/manifests/<tag>} — returns 404
     * MANIFEST_UNKNOWN when the tag itself OR the digest it resolves
     * to is in cooldown. See class-level javadoc on the handler for
     * why both must be checked.
     */
    private final DockerManifestByTagHandler manifestTagHandler;

    /**
     * Detector for {@code /v2/<name>/manifests/<tag>} paths. Returns
     * true only for tag references, not digest references.
     */
    private final DockerManifestByTagMetadataRequestDetector manifestTagDetector;

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
        this.tagsHandler = new DockerTagsListHandler(
            origin, cooldown, inspector, repoType, repoName
        );
        this.tagsDetector = new DockerMetadataRequestDetector();
        this.manifestTagHandler = new DockerManifestByTagHandler(
            origin, cooldown, inspector, repoType, repoName
        );
        this.manifestTagDetector = new DockerManifestByTagMetadataRequestDetector();
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String path = line.uri().getPath();
        // GET /v2/<name>/tags/list — route through the tags-list filter
        // handler. This is where the Docker cooldown bundle registered
        // in CooldownWiring is actually consumed; without this dispatch
        // the bundle is dead infrastructure and blocked tags leak via
        // `docker pull --all-tags` and `skopeo list-tags`.
        if (line.method() == RqMethod.GET && this.tagsDetector.isMetadataRequest(path)) {
            // Consume request body to match the invariant elsewhere that
            // nothing leaks a Vert.x stream.
            return body.asBytesFuture().thenCompose(ignored ->
                this.tagsHandler.handle(line, new Login(headers).getValue())
            );
        }
        // GET /v2/<name>/manifests/<tag> — route through the manifest-tag
        // filter. Returns 404 MANIFEST_UNKNOWN when the tag or the digest
        // it resolves to is blocked by cooldown. Digest references fall
        // through to the existing flow below, which handles digest-addressed
        // manifests (release-date bookkeeping + cooldown evaluation).
        if (line.method() == RqMethod.GET && this.manifestTagDetector.isMetadataRequest(path)) {
            return this.manifestTagHandler.handle(
                line, headers, body, new Login(headers).getValue()
            );
        }
        if (!this.shouldInspect(line)) {
            return this.origin.response(line, headers, body);
        }
        final ManifestRequest request;
        try {
            request = ManifestRequest.from(line);
        } catch (final IllegalArgumentException ex) {
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Failed to parse manifest request, falling through to origin")
                .error(ex)
                .log();
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
                
                // Buffer manifest body for cooldown evaluation.
                // Docker manifests are small JSON (<50KB), not blob layers (which are GB-sized).
                // This cooldown slice is only mounted on manifest endpoints, so buffering is safe.
                final CompletableFuture<byte[]> bytesFuture = response.body().asBytesFuture();
                return bytesFuture.thenCompose(bytes -> {
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
                                ? CooldownResponseRegistry.instance()
                                    .getOrThrow(this.repoType)
                                    .forbidden(result.block().orElseThrow())
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
                                ? CooldownResponseRegistry.instance()
                                    .getOrThrow(this.repoType)
                                    .forbidden(result.block().orElseThrow())
                                : rebuilt
                            );
                    }
                    
                    // First time seeing this artifact - WAIT for extraction then evaluate
                    return this.determineReleaseSync(request, response.headers(), bytes, artifact, version, digest)
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
                                    ? CooldownResponseRegistry.instance()
                                        .getOrThrow(this.repoType)
                                        .forbidden(result.block().orElseThrow())
                                    : rebuilt
                                );
                        });
                }).exceptionally(ex -> {
                    EcsLogger.warn("com.auto1.pantera.docker")
                        .message("Failed to process manifest")
                        .eventCategory("web")
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
        final Optional<String> digest
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
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("Failed to extract release date from config")
                    .eventCategory("web")
                    .eventAction("release_date_extract")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .error(error)
                    .log();
            } else if (release.isPresent()) {
                EcsLogger.debug("com.auto1.pantera.docker")
                    .message("Extracted release date from config")
                    .eventCategory("web")
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
            EcsLogger.warn("com.auto1.pantera.docker")
                .message("Exception extracting release date")
                .eventCategory("web")
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
                EcsLogger.debug("com.auto1.pantera.docker")
                    .message("Extracted release date from config")
                    .eventCategory("web")
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
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Failed to extract release date from config")
                .eventCategory("web")
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
            EcsLogger.warn("com.auto1.pantera.docker")
                .message("Failed to build manifest from response headers")
                .eventCategory("web")
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
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Unable to parse manifest config created field")
                .eventCategory("web")
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
                } catch (final DateTimeParseException ex) {
                    EcsLogger.debug("com.auto1.pantera.docker")
                        .message("Failed to parse date header for release time")
                        .error(ex)
                        .log();
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
