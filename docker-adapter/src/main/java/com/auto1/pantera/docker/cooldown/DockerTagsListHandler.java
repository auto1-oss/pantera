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
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.fasterxml.jackson.databind.JsonNode;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware filtering of Docker Registry v2
 * {@code /v2/<name>/tags/list} responses.
 *
 * <p>This is where {@link DockerMetadataParser},
 * {@link DockerMetadataFilter}, {@link DockerMetadataRewriter} and
 * {@link DockerMetadataRequestDetector} — the four components
 * registered via the cooldown SPI in {@code CooldownWiring} — are
 * actually consumed on the serve path. Prior to this handler the
 * registered Docker bundle was dead infrastructure: the Docker
 * proxy slice filtered nothing on {@code /tags/list} requests and
 * blocked tags leaked straight through to
 * {@code docker pull --all-tags} / {@code skopeo list-tags}.</p>
 *
 * <p>Mirrors the Go handler introduced in commit {@code 1eb53ceb}
 * ({@code GoListHandler} for {@code /@v/list}) and the PyPI handler
 * introduced in {@code 19bc60cb} ({@code PypiSimpleHandler} for
 * {@code /simple/<name>/}). The dispatch order in
 * {@code DockerProxyCooldownSlice} must route tags/list requests
 * through this class before the generic upstream-fetch flow.</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Fetch {@code /v2/<name>/tags/list} from upstream via the
 *       shared slice (same auth / cache / resilience layers as
 *       artifact fetches).</li>
 *   <li>On non-2xx, forward status + body unchanged — never
 *       transform upstream errors.</li>
 *   <li>Parse the JSON via {@link DockerMetadataParser}. On parse
 *       failure, pass upstream bytes through unchanged so a
 *       transient upstream format quirk never fails the whole
 *       response.</li>
 *   <li>Evaluate every parsed tag against cooldown in parallel;
 *       collect the blocked set.</li>
 *   <li>Run the filter; re-serialise via
 *       {@link DockerMetadataRewriter}.</li>
 *   <li>If every tag would be stripped → still return 200 with
 *       {@code "tags":[]}. Docker clients handle empty tag lists
 *       cleanly; returning 404 here would confuse tooling that
 *       treats 404 as "no such repo" (as opposed to "repo exists
 *       with zero allowed tags"). This diverges from the Go/PyPI
 *       all-blocked branch on purpose — the Docker Registry v2
 *       spec treats an empty {@code tags} array as a valid shape,
 *       whereas the PEP 503 Simple Index and Go {@code /@v/list}
 *       have no canonical "empty" shape.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class DockerTagsListHandler {

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
     * Path detector for {@code /v2/<name>/tags/list} endpoints.
     */
    private final DockerMetadataRequestDetector detector;

    /**
     * JSON parser for {@code /tags/list} bodies.
     */
    private final DockerMetadataParser parser;

    /**
     * Pure filter that drops blocked tags from the JSON array.
     */
    private final DockerMetadataFilter filter;

    /**
     * Serialiser back to JSON bytes.
     */
    private final DockerMetadataRewriter rewriter;

    /**
     * Ctor.
     *
     * @param upstream Upstream Docker registry proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type (e.g. {@code "docker-proxy"})
     * @param repoName Repository name
     */
    public DockerTagsListHandler(
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
        this.detector = new DockerMetadataRequestDetector();
        this.parser = new DockerMetadataParser();
        this.filter = new DockerMetadataFilter();
        this.rewriter = new DockerMetadataRewriter();
    }

    /**
     * Whether this handler should intercept the given request path.
     *
     * @param path Request path
     * @return true for {@code /v2/<name>/tags/list} paths with a
     *     non-empty image name
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a tags/list request with cooldown filtering.
     *
     * @param line Request line (must be {@code /v2/<name>/tags/list})
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(
        final RequestLine line, final String user
    ) {
        final String path = line.uri().getPath();
        final String image = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a /tags/list path: " + path)
        );
        return this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    return bodyBytes(resp.body()).thenApply(bytes ->
                        ResponseBuilder.from(resp.status())
                            .headers(resp.headers())
                            .body(bytes)
                            .build()
                    );
                }
                return bodyBytes(resp.body()).thenCompose(bytes ->
                    this.processUpstream(bytes, image, user)
                );
            });
    }

    /**
     * Parse → evaluate → filter → serialise. Pass-through on parse
     * failure or rewrite failure.
     *
     * <p>Unlike Go's {@code /@v/list} and PyPI's {@code /simple/}
     * all-blocked branches, an all-tags-blocked result still returns
     * 200 with {@code "tags":[]} — see class-level javadoc.</p>
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes, final String image, final String user
    ) {
        final JsonNode parsed;
        try {
            parsed = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.docker")
                .message("Failed to parse /tags/list JSON — passing upstream bytes through")
                .eventCategory("web")
                .eventAction("tags_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", image)
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build()
            );
        }
        final List<String> tags = this.parser.extractVersions(parsed);
        if (tags.isEmpty()) {
            // Empty tags array already — forward upstream verbatim so
            // any name/pagination fields round-trip exactly.
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.blockedTags(image, tags, user).thenApply(blocked -> {
            if (blocked.isEmpty()) {
                // Fast path: nothing blocked → upstream bytes verbatim.
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build();
            }
            final JsonNode filtered = this.filter.filter(parsed, blocked);
            try {
                final byte[] body = this.rewriter.rewrite(filtered);
                EcsLogger.info("com.auto1.pantera.docker")
                    .message("/tags/list filtered: removed cooldown-blocked tags"
                        + " (total=" + tags.size()
                        + ", blocked=" + blocked.size()
                        + ", served=" + (tags.size() - blocked.size()) + ")")
                    .eventCategory("web")
                    .eventAction("tags_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", image)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(body)
                    .build();
            } catch (final MetadataRewriteException ex) {
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("/tags/list rewrite failed — falling back to upstream body")
                    .eventCategory("web")
                    .eventAction("tags_filter")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", image)
                    .error(ex)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", this.rewriter.contentType())
                    .body(upstreamBytes)
                    .build();
            }
        });
    }

    /**
     * Evaluate every candidate against cooldown in parallel; collect
     * the blocked set. Swallows per-version errors to "allowed" so a
     * transient inspector failure never denies an entire tag list.
     */
    private CompletableFuture<Set<String>> blockedTags(
        final String image, final List<String> candidates, final String user
    ) {
        final List<CompletableFuture<Boolean>> futures =
            new ArrayList<>(candidates.size());
        for (final String tag : candidates) {
            futures.add(this.isBlocked(image, tag, user));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                final Set<String> blocked = new HashSet<>();
                for (int idx = 0; idx < candidates.size(); idx++) {
                    if (futures.get(idx).join()) {
                        blocked.add(candidates.get(idx));
                    }
                }
                return blocked;
            });
    }

    private CompletableFuture<Boolean> isBlocked(
        final String image, final String tag, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            image,
            tag,
            user == null ? "docker-tags" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("Cooldown evaluation failed; treating tag as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", image)
                    .field("package.version", tag)
                    .error(err)
                    .log();
                return false;
            });
    }

    /**
     * Drain a reactive-streams body to a byte array. Mirrors the
     * helper in {@code GoListHandler} and {@code PypiSimpleHandler}.
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
