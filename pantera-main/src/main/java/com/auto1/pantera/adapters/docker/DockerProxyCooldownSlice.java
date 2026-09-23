/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.docker.cooldown.CooldownImageName;
import com.auto1.pantera.docker.cooldown.DockerManifestByTagHandler;
import com.auto1.pantera.docker.cooldown.DockerManifestByTagMetadataRequestDetector;
import com.auto1.pantera.docker.cooldown.DockerMetadataRequestDetector;
import com.auto1.pantera.docker.cooldown.DockerTagsListHandler;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.http.manifest.ManifestRequest;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.misc.OfficialImageName;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import org.slf4j.MDC;

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

/**
 * Cooldown evaluator for the docker-proxy adapter.
 *
 * <p><b>Trace context contract.</b> Trace context (trace.id / span.id /
 * span.parent.id) is inherited from the {@code EcsLoggingSlice} MDC scope
 * set at request entry. Any async hop introduced in this slice MUST use
 * {@code ContextualExecutor.contextualize(...)} (or an equivalent MDC
 * capture-and-restore) to preserve trace.id across the executor
 * boundary — without it, log lines emitted from the worker thread
 * surface in Kibana with no trace correlation back to the originating
 * request.
 */
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

    /**
     * Canonical cooldown artifact naming (repo prefix removed, Docker Hub
     * official-image rule applied) shared by every docker cooldown path.
     */
    private final CooldownImageName names;

    public DockerProxyCooldownSlice(
        final Slice origin,
        final String repoName,
        final String repoType,
        final CooldownService cooldown,
        final DockerProxyCooldownInspector inspector,
        final Docker docker
    ) {
        this(
            origin, repoName, repoType, cooldown, inspector, docker,
            new CooldownImageName(repoName, new OfficialImageName(false))
        );
    }

    /**
     * Ctor.
     *
     * @param origin Docker slice
     * @param repoName Repository name
     * @param repoType Repository type
     * @param cooldown Cooldown service
     * @param inspector Cooldown inspector
     * @param docker Docker (for manifest config lookups)
     * @param names Canonical cooldown artifact naming
     */
    public DockerProxyCooldownSlice(
        final Slice origin,
        final String repoName,
        final String repoType,
        final CooldownService cooldown,
        final DockerProxyCooldownInspector inspector,
        final Docker docker,
        final CooldownImageName names
    ) {
        this.origin = origin;
        this.repoName = repoName;
        this.repoType = repoType;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.docker = docker;
        this.names = names;
        this.tagsHandler = new DockerTagsListHandler(
            origin, cooldown, inspector, repoType, repoName, names
        );
        this.tagsDetector = new DockerMetadataRequestDetector();
        this.manifestTagHandler = new DockerManifestByTagHandler(
            origin, cooldown, inspector, repoType, repoName, names,
            this::recordTagRelease
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
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        // GET /v2/<name>/tags/list — route through the tags-list filter
        // handler. This is where the Docker cooldown bundle registered
        // in CooldownWiring is actually consumed; without this dispatch
        // the bundle is dead infrastructure and blocked tags leak via
        // `docker pull --all-tags` and `skopeo list-tags`.
        if (line.method() == RqMethod.GET && this.tagsDetector.isMetadataRequest(path)) {
            // Consume request body to match the invariant elsewhere that
            // nothing leaks a Vert.x stream.
            return body.asBytesFuture().thenCompose(ignored ->
                this.tagsHandler.handle(line, headers, new Login(headers).getValue())
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
            EcsLogger.debug("com.auto1.pantera.adapters.docker")
                .message("Failed to parse manifest request, falling through to origin")
                .error(ex)
                .field("log.source", "application")
                .log();
            return this.origin.response(line, headers, body);
        }
        return this.origin.response(line, headers, body)
            .thenCompose(response -> {
                if (!response.status().success()) {
                    return CompletableFuture.completedFuture(response);
                }
                // Buffer manifest body for cooldown evaluation.
                // Docker manifests are small JSON (<50KB), not blob layers (which are GB-sized).
                // This cooldown slice is only mounted on manifest endpoints, so buffering is safe.
                return response.body().asBytesFuture().handle((bytes, err) -> {
                    if (err != null) {
                        return CompletableFuture.completedFuture(
                            this.unreadableManifest(request, err)
                        );
                    }
                    return this.evaluateDigestManifest(request, response, bytes, headers, ctx);
                }).thenCompose(java.util.function.Function.identity());
            });
    }

    /**
     * The upstream manifest body could not be read, so there is nothing to
     * serve: answer a Registry v2 error instead of a response whose body is
     * already broken.
     */
    private Response unreadableManifest(final ManifestRequest request, final Throwable err) {
        EcsLogger.warn("com.auto1.pantera.adapters.docker")
            .message("Failed to read upstream manifest body")
            .eventCategory("web")
            .eventAction("manifest_process")
            .eventOutcome("failure")
            .field("package.name", this.names.of(request.name()))
            .field("package.version", request.reference().digest())
            .error(err)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.badGateway()
            .jsonBody(
                "{\"errors\":[{\"code\":\"UNKNOWN\","
                    + "\"message\":\"upstream manifest body could not be read\"}]}"
            )
            .build();
    }

    /**
     * Evaluate cooldown for a digest-addressed manifest whose bytes are
     * already buffered. Any failure while resolving the release date or
     * evaluating falls back to serving the manifest: the upstream body was
     * consumed into {@code bytes}, so the fallback MUST be rebuilt from them —
     * handing back the original, drained response gave the client an empty
     * body.
     */
    private CompletableFuture<Response> evaluateDigestManifest(
        final ManifestRequest request,
        final Response response,
        final byte[] bytes,
        final Headers headers,
        final AuditContext ctx
    ) {
        final String artifact = this.names.of(request.name());
        final String version = request.reference().digest();
        final String user = new Login(headers).getValue();
        final Optional<String> digest = this.digest(response.headers());
        final Response rebuilt = rebuild(response, bytes);
        final CompletableFuture<Response> evaluated;
        try {
            evaluated = this.resolveDigestRelease(request, response.headers(), bytes, artifact, version, digest, user)
                .thenCompose(ignored -> this.evaluateAndAudit(
                    new CooldownRequest(
                        this.repoType, this.repoName, artifact, version, user, Instant.now()
                    ),
                    rebuilt, ctx, user
                ));
        } catch (final RuntimeException ex) {
            return CompletableFuture.completedFuture(
                this.manifestFallback(response, bytes, artifact, version, user, digest, ex)
            );
        }
        return evaluated.exceptionally(
            ex -> this.manifestFallback(response, bytes, artifact, version, user, digest, ex)
        );
    }

    /**
     * Make the release date of a digest-addressed manifest known to the
     * inspector: from the {@code Last-Modified}/{@code Date} headers when
     * present, else from the manifest config (first-time only).
     */
    private CompletableFuture<Void> resolveDigestRelease(
        final ManifestRequest request,
        final Headers respHeaders,
        final byte[] bytes,
        final String artifact,
        final String version,
        final Optional<String> digest,
        final String user
    ) {
        final Optional<Instant> headerRelease = this.release(respHeaders);
        if (headerRelease.isPresent()) {
            this.inspector.recordRelease(artifact, version, headerRelease.get());
            digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, headerRelease.get()));
            this.inspector.register(artifact, version, headerRelease, user, this.repoName, digest);
            return CompletableFuture.completedFuture(null);
        }
        if (this.inspector.known(artifact, version)) {
            return CompletableFuture.completedFuture(null);
        }
        return this.determineReleaseSync(request.name(), respHeaders, bytes, artifact, version, digest)
            .thenAccept(release -> this.inspector.register(
                artifact, version, release, user, this.repoName, digest
            ));
    }

    /**
     * Serve the buffered manifest after a processing failure (fail open, as
     * before), logging the failure.
     */
    private Response manifestFallback(
        final Response response,
        final byte[] bytes,
        final String artifact,
        final String version,
        final String user,
        final Optional<String> digest,
        final Throwable ex
    ) {
        EcsLogger.warn("com.auto1.pantera.adapters.docker")
            .message("Failed to process manifest for cooldown; serving it unevaluated")
            .eventCategory("web")
            .eventAction("manifest_process")
            .eventOutcome("failure")
            .field("package.name", artifact)
            .field("package.version", version)
            .error(ex)
            .field("log.source", "application")
            .log();
        this.inspector.register(artifact, version, Optional.empty(), user, this.repoName, digest);
        return rebuild(response, bytes);
    }

    /**
     * Release-date recorder for manifest-by-tag requests: when the inspector
     * has no date for the tag yet, resolve it synchronously from the manifest
     * config and record it under the tag and the manifest digest, so the
     * first evaluation of a fresh tag sees it (the cache layer records dates
     * only after the response has been handed back).
     */
    private CompletableFuture<Void> recordTagRelease(
        final String name,
        final String artifact,
        final String tag,
        final Optional<String> digest,
        final Headers respHeaders,
        final byte[] bytes
    ) {
        if (this.inspector.known(artifact, tag)) {
            return CompletableFuture.completedFuture(null);
        }
        return this.determineReleaseSync(name, respHeaders, bytes, artifact, tag, digest)
            .thenAccept(release -> release.ifPresent(
                when -> this.inspector.recordRelease(artifact, tag, when)
            ));
    }

    /**
     * Fresh response carrying buffered body bytes.
     */
    private static Response rebuild(final Response response, final byte[] bytes) {
        return new Response(response.status(), response.headers(), new Content.From(bytes));
    }

    /**
     * Evaluate cooldown for a digest-addressed manifest request and audit
     * the outcome. Shared by the three near-identical branches above (known
     * release date via headers, cached release date, freshly-extracted
     * release date) so the audit call site exists exactly once.
     *
     * @param cooldownRequest Cooldown evaluation request
     * @param rebuilt Response to return when cooldown allows the fetch
     * @param ctx Request correlation context
     * @param owner Requesting user
     * @return Forbidden response when blocked, otherwise {@code rebuilt}
     */
    private CompletableFuture<Response> evaluateAndAudit(
        final CooldownRequest cooldownRequest,
        final Response rebuilt,
        final AuditContext ctx,
        final String owner
    ) {
        return this.cooldown.evaluate(cooldownRequest, this.inspector)
            .thenApply(result -> {
                if (result.blocked()) {
                    AuditLogger.access(
                        ctx, this.repoType, this.repoName,
                        cooldownRequest.artifact(), cooldownRequest.version(), 0L, owner,
                        AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_COOLDOWN_ACTIVE
                    );
                    return CooldownResponseRegistry.instance()
                        .getOrThrow(this.repoType)
                        .forbidden(result.block().orElseThrow());
                }
                AuditLogger.access(
                    ctx, this.repoType, this.repoName,
                    cooldownRequest.artifact(), cooldownRequest.version(), 0L, owner,
                    AuditLogger.OUTCOME_SUCCESS, null
                );
                return rebuilt;
            });
    }

    /**
     * Extract release date from manifest config synchronously.
     * Waits for extraction to complete before returning.
     * Used on first request to properly evaluate cooldown.
     *
     * @param name Image name as in the request path
     * @param headers Response headers
     * @param manifestBytes Manifest body bytes
     * @param artifact Canonical cooldown artifact name
     * @param version Version/digest
     * @param digest Optional digest
     * @return CompletableFuture with optional release date
     */
    private CompletableFuture<Optional<Instant>> determineReleaseSync(
        final String name,
        final Headers headers,
        final byte[] manifestBytes,
        final String artifact,
        final String version,
        final Optional<String> digest
    ) {
        final Optional<Manifest> manifest = this.manifestFrom(headers, manifestBytes);
        if (manifest.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.configCreated(name, manifest.get()).whenComplete((release, error) -> {
            if (error != null) {
                EcsLogger.warn("com.auto1.pantera.adapters.docker")
                    .message("Failed to extract release date from config")
                    .eventCategory("web")
                    .eventAction("release_date_extract")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .error(error)
                    .field("log.source", "application")
                    .log();
            } else if (release.isPresent()) {
                EcsLogger.debug("com.auto1.pantera.adapters.docker")
                    .message("Extracted release date from config")
                    .eventCategory("web")
                    .eventAction("release_date_extract")
                    .eventOutcome("success")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("package.release_date", release.get().toString())
                    .field("log.source", "application")
                    .log();
                // Also record by digest
                digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, release.get()));
            }
        }).exceptionally(ex -> {
            EcsLogger.warn("com.auto1.pantera.adapters.docker")
                .message("Exception extracting release date")
                .eventCategory("web")
                .eventAction("release_date_extract")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .error(ex)
                .field("log.source", "application")
                .log();
            return Optional.empty();
        });
    }

    /**
     * The image config's {@code created} timestamp. For a manifest list /
     * OCI index (multi-arch tags) there is no config, so the first child's is
     * used — all children of one tag come from one build, the same rule
     * {@code CacheManifests} applies when it records release dates.
     *
     * @param name Image name as in the request path
     * @param doc Manifest
     * @return Created timestamp, empty when unavailable
     */
    private CompletableFuture<Optional<Instant>> configCreated(final String name, final Manifest doc) {
        if (doc.isManifestList()) {
            final java.util.Collection<Digest> children = doc.manifestListChildren();
            if (children.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return this.docker.repo(name).manifests()
                .get(com.auto1.pantera.docker.ManifestReference.from(children.iterator().next()))
                .thenCompose(child -> {
                    if (child.isEmpty() || child.get().isManifestList()) {
                        return CompletableFuture.completedFuture(Optional.<Instant>empty());
                    }
                    return this.configCreated(name, child.get());
                });
        }
        return this.docker.repo(name).layers().get(doc.config()).thenCompose(blob -> {
            if (blob.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Instant>empty());
            }
            return blob.get().content()
                .thenCompose(Content::asBytesFuture)
                .thenApply(this::extractCreatedInstant);
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
                EcsLogger.debug("com.auto1.pantera.adapters.docker")
                    .message("Extracted release date from config")
                    .eventCategory("web")
                    .eventAction("release_date_extract")
                    .eventOutcome("success")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("package.release_date", release.get().toString())
                    .field("log.source", "application")
                    .log();
                this.inspector.recordRelease(artifact, version, release.get());
                digest.ifPresent(d -> this.inspector.recordRelease(artifact, d, release.get()));
            }
        }).exceptionally(ex -> {
            EcsLogger.debug("com.auto1.pantera.adapters.docker")
                .message("Failed to extract release date from config")
                .eventCategory("web")
                .eventAction("release_date_extract")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .error(ex)
                .field("log.source", "application")
                .log();
            return null;
        });
    }

    private Optional<Manifest> manifestFrom(final Headers headers, final byte[] bytes) {
        try {
            final Digest digest = new DigestHeader(headers).value();
            return Optional.of(new Manifest(digest, bytes));
        } catch (final IllegalArgumentException ex) {
            EcsLogger.warn("com.auto1.pantera.adapters.docker")
                .message("Failed to build manifest from response headers")
                .eventCategory("web")
                .eventAction("manifest_build")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
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
            EcsLogger.debug("com.auto1.pantera.adapters.docker")
                .message("Unable to parse manifest config created field")
                .eventCategory("web")
                .eventAction("manifest_parse")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
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
                    EcsLogger.debug("com.auto1.pantera.adapters.docker")
                        .message("Failed to parse date header for release time")
                        .error(ex)
                        .field("log.source", "application")
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
