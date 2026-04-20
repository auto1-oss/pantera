/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.docker.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

/**
 * Orchestrates cooldown-aware filtering of Docker Registry v2
 * manifest-by-tag requests:
 * {@code GET /v2/<name>/manifests/<tag>}.
 *
 * <p>The Docker client resolves
 * {@code docker pull nginx:latest} via a single request to
 * {@code /v2/nginx/manifests/latest} — it does <em>not</em> always
 * call {@code /tags/list} first. So tag filtering alone would leak
 * blocked versions straight through the pull path. This handler
 * plugs that hole by checking the returned manifest's
 * {@code Docker-Content-Digest} <em>and</em> the tag-string against
 * cooldown.</p>
 *
 * <p>Why check both tag and digest: Pantera records cooldown blocks
 * using whatever version string the write path saw — which can be a
 * tag like {@code "1.27"}, a digest like
 * {@code sha256:abc…}, or the literal {@code "latest"}. A naive
 * tag-only check misses the "digest-blocked, tag re-used" case;
 * a digest-only check misses the "tag moved to a new digest since
 * the block was recorded" case. Checking both covers every
 * combination the operator might have produced.</p>
 *
 * <p>When blocked, returns <strong>404 {@code MANIFEST_UNKNOWN}</strong>
 * per the OCI distribution spec for "the referenced tag was not
 * found in the repository". Docker clients handle 404 cleanly; a
 * 403 here causes some client versions to retry forever. This is
 * the same rationale PyPI uses for all-blocked {@code /simple/}.</p>
 *
 * <p>Scope guardrails:</p>
 * <ul>
 *   <li>GET only. HEAD requests bypass this handler (HEAD responses
 *       have no body to inspect and the existing
 *       {@code DockerProxyCooldownSlice} manifest path already guards
 *       them via artifact-fetch cooldown).</li>
 *   <li>Tag references only — digest references pass through to the
 *       generic flow (the URL is already immutable content addressing,
 *       not a hot-path resolution endpoint).</li>
 *   <li>On non-2xx from upstream, forward verbatim. On 2xx without a
 *       digest header, fall back to tag-only check — never drop a
 *       valid response just because the header is missing.</li>
 * </ul>
 *
 * <p>Mirrors the Go/PyPI handler pattern but returns the manifest
 * body bytes directly when not blocked (rather than re-fetching),
 * since a Docker manifest is small JSON (&lt;50 KB) and buffering is
 * cheap. Layer blobs never pass through this handler — they follow
 * the {@code /v2/<name>/blobs/} route.</p>
 *
 * @since 2.2.0
 */
public final class DockerManifestByTagHandler {

    /**
     * {@code Docker-Content-Digest} response header name.
     */
    private static final String DIGEST_HEADER = "Docker-Content-Digest";

    /**
     * Upstream slice shared with the main Docker proxy.
     */
    private final Slice upstream;

    /**
     * Cooldown evaluation service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector (release-date lookups).
     */
    private final CooldownInspector inspector;

    /**
     * Repository type (e.g. {@code "docker"}, {@code "docker-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Detector for manifest-by-tag paths.
     */
    private final DockerManifestByTagMetadataRequestDetector detector;

    /**
     * Ctor.
     *
     * @param upstream Upstream Docker registry proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type (e.g. {@code "docker-proxy"})
     * @param repoName Repository name
     */
    public DockerManifestByTagHandler(
        final Slice upstream,
        final CooldownService cooldown,
        final CooldownInspector inspector,
        final String repoType,
        final String repoName
    ) {
        this.upstream = upstream;
        this.cooldown = cooldown;
        this.inspector = inspector;
        this.repoType = repoType;
        this.repoName = repoName;
        this.detector = new DockerManifestByTagMetadataRequestDetector();
    }

    /**
     * Whether this handler should intercept the given request path.
     *
     * @param path Request path
     * @return true for {@code /v2/<name>/manifests/<tag>} GET paths
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path);
    }

    /**
     * Handle a manifest-by-tag request with cooldown filtering.
     *
     * @param line Request line (must be GET of a manifest-by-tag path)
     * @param headers Inbound headers (forwarded upstream verbatim)
     * @param body Inbound body (forwarded upstream verbatim)
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(
        final RequestLine line,
        final Headers headers,
        final Content body,
        final String user
    ) {
        final String path = line.uri().getPath();
        final String image = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a manifest-by-tag path: " + path)
        );
        final String tag = this.detector.extractTag(path).orElseThrow(
            () -> new IllegalArgumentException("Could not extract tag from: " + path)
        );
        return this.upstream.response(line, headers, body)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    // Forward upstream errors (404, 401, etc.) unchanged.
                    return bodyBytes(resp.body()).thenApply(bytes ->
                        ResponseBuilder.from(resp.status())
                            .headers(resp.headers())
                            .body(bytes)
                            .build()
                    );
                }
                // Buffer the manifest body — it is small JSON (<50 KB).
                return bodyBytes(resp.body()).thenCompose(bytes ->
                    this.evaluateAndRespond(resp.headers(), bytes, image, tag, user)
                );
            });
    }

    /**
     * Check tag-string AND upstream digest against cooldown. Any hit
     * blocks the request with a 404 {@code MANIFEST_UNKNOWN}. No hit:
     * rebuild the response from the buffered bytes, preserving
     * upstream headers (including {@code Docker-Content-Digest} and
     * {@code Content-Type}).
     */
    private CompletableFuture<Response> evaluateAndRespond(
        final Headers upstreamHeaders,
        final byte[] manifestBytes,
        final String image,
        final String tag,
        final String user
    ) {
        final Optional<String> digest = digestHeader(upstreamHeaders);
        final CompletableFuture<Boolean> tagCheck = this.isBlocked(image, tag, user);
        final CompletableFuture<Boolean> digestCheck = digest
            .map(d -> this.isBlocked(image, d, user))
            .orElseGet(() -> CompletableFuture.completedFuture(false));
        return tagCheck.thenCombine(digestCheck, (tagBlocked, digestBlocked) -> {
            if (tagBlocked || digestBlocked) {
                EcsLogger.info("com.auto1.pantera.docker")
                    .message("Manifest-by-tag blocked by cooldown — returning 404")
                    .eventCategory("web")
                    .eventAction("manifest_tag_filter")
                    .eventOutcome("failure")
                    .field("event.reason",
                        tagBlocked ? "tag_blocked" : "digest_blocked")
                    .field("repository.name", this.repoName)
                    .field("package.name", image)
                    .field("package.version", tag)
                    .field("container.image.digest", digest.orElse(""))
                    .log();
                return manifestUnknown(tag);
            }
            return ResponseBuilder.ok()
                .headers(upstreamHeaders)
                .body(manifestBytes)
                .build();
        });
    }

    /**
     * Build a Docker Registry v2 {@code MANIFEST_UNKNOWN} 404 response
     * for a tag that the client should treat as "not found in this
     * repository". Body shape matches the Registry v2 error schema.
     */
    private static Response manifestUnknown(final String tag) {
        final String body = String.format(
            "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\","
            + "\"message\":\"manifest unknown\","
            + "\"detail\":{\"Tag\":\"%s\"}}]}",
            tag.replace("\"", "\\\"")
        );
        return ResponseBuilder.notFound()
            .header("X-Pantera-Cooldown", "blocked")
            .jsonBody(body)
            .build();
    }

    /**
     * Extract the {@code Docker-Content-Digest} header value if
     * present. Case-insensitive because some proxies re-case it.
     */
    private static Optional<String> digestHeader(final Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> DIGEST_HEADER.equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private CompletableFuture<Boolean> isBlocked(
        final String image, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            image,
            version,
            user == null ? "docker-manifest" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("Cooldown evaluation failed; treating as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", image)
                    .field("package.version", version)
                    .error(err)
                    .log();
                return false;
            });
    }

    /**
     * Drain a reactive-streams body to a byte array. Mirrors the helper
     * in {@link DockerTagsListHandler}.
     */
    private static CompletableFuture<byte[]> bodyBytes(
        final org.reactivestreams.Publisher<ByteBuffer> body
    ) {
        return Flowable.fromPublisher(body)
            .reduce(new ByteArrayOutputStream(), (stream, buffer) -> {
                try {
                    stream.write(new Remaining(buffer).bytes());
                    return stream;
                } catch (final java.io.IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
            .map(ByteArrayOutputStream::toByteArray)
            .onErrorReturnItem(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }
}
