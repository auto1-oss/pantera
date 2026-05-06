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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware filtering of PyPI JSON API responses
 * ({@code /pypi/<name>/json}).
 *
 * <p>Closes the unbounded-resolution gap on the JSON-API surface —
 * tools like {@code poetry}, {@code pip-tools}, and pip's speed-paths
 * read {@code info.version} from this document to resolve
 * {@code <package>} with no pin. Without this filter a blocked version
 * would leak straight through.</p>
 *
 * <p>Mirrors {@link PypiSimpleHandler} and the Go handler pattern
 * ({@code GoLatestHandler}):</p>
 * <ol>
 *   <li>Fetch upstream via the shared slice.</li>
 *   <li>On non-2xx, forward body + status unchanged.</li>
 *   <li>Parse the JSON. If malformed → pass through.</li>
 *   <li>Evaluate every key in {@code releases} against cooldown;
 *       collect the blocked set.</li>
 *   <li>Run {@link PypiJsonMetadataFilter}. Possible outcomes:
 *       <ul>
 *         <li>{@code Filtered} — return 200 + rewritten JSON.</li>
 *         <li>{@code Passthrough} — return 200 + upstream bytes
 *             (cannot happen for parseable input; guards exotic
 *             upstream shapes).</li>
 *         <li>{@code AllBlocked} — return 404. pip's convention for
 *             "package not found" produces a clean error and avoids
 *             the weird edge cases of serving an empty-releases JSON.
 *             </li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class PypiJsonHandler {

    /**
     * JSON API Content-Type per PyPI convention.
     */
    private static final String CONTENT_TYPE = "application/json";

    /**
     * Upstream slice (shared with main PyPI proxy).
     */
    private final Slice upstream;

    /**
     * Cooldown evaluation service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector.
     */
    private final PypiJsonMetadataRequestDetector detector;

    /**
     * Pure filter.
     */
    private final PypiJsonMetadataFilter filter;

    /**
     * Jackson for parsing responses to enumerate release keys.
     */
    private final ObjectMapper mapper;

    /**
     * Ctor.
     *
     * @param upstream Upstream PyPI proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public PypiJsonHandler(
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
        this.detector = new PypiJsonMetadataRequestDetector();
        this.mapper = new ObjectMapper();
        this.filter = new PypiJsonMetadataFilter(this.mapper);
    }

    /**
     * Whether this handler should intercept the given path.
     *
     * @param path Request path
     * @return true for {@code /pypi/<name>/json}
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a JSON-API request with cooldown filtering.
     *
     * @param line Request line
     * @param user Authenticated user
     * @return Future response
     */
    public CompletableFuture<Response> handle(final RequestLine line, final String user) {
        final String path = line.uri().getPath();
        // PEP 503 normalization (lowercase + collapse runs of [-_.] to single
        // '-'): the artifact-publish path stores release dates under the
        // canonical name (see ProxySlice's NormalizedProjectName.Simple uses),
        // so the cooldown lookup must use the same form. A request for
        // /pypi/Foo_Bar/json with raw name "Foo_Bar" otherwise misses the DB
        // row for "foo-bar" and the filter silently falls open ("0 blocked"),
        // leaking blocked versions to pip / Poetry clients.
        final String pkg = new com.auto1.pantera.pypi.NormalizedProjectName.Simple(
            this.detector.extractPackageName(path).orElseThrow(
                () -> new IllegalArgumentException("Not a /pypi/<name>/json path: " + path)
            )
        ).value();
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
                    this.processUpstream(bytes, pkg, user)
                );
            });
    }

    /**
     * Parse → evaluate → filter → serialise.
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes, final String pkg, final String user
    ) {
        final List<String> versions = extractReleaseKeys(this.mapper, upstreamBytes);
        if (versions.isEmpty()) {
            // Either malformed JSON or no releases — pass through.
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.blockedVersions(pkg, versions, user).thenApply(blocked -> {
            final PypiJsonMetadataFilter.Result result =
                this.filter.filter(upstreamBytes, blocked);
            if (result instanceof PypiJsonMetadataFilter.Result.AllBlocked) {
                return this.allBlockedResponse(pkg);
            }
            if (result instanceof PypiJsonMetadataFilter.Filtered filtered) {
                EcsLogger.info("com.auto1.pantera.pypi")
                    .message("/pypi/<pkg>/json filtered: removed cooldown-blocked versions"
                        + " (total=" + versions.size()
                        + ", blocked=" + blocked.size() + ")")
                    .eventCategory("web")
                    .eventAction("json_filter")
                    .eventOutcome("success")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .log();
                return ResponseBuilder.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(filtered.bytes())
                    .build();
            }
            // Passthrough: upstream shape we couldn't parse — forward
            // verbatim rather than break clients.
            final PypiJsonMetadataFilter.Passthrough through =
                (PypiJsonMetadataFilter.Passthrough) result;
            return ResponseBuilder.ok()
                .header("Content-Type", CONTENT_TYPE)
                .body(through.bytes())
                .build();
        });
    }

    /**
     * Emit 404 when every version is blocked. pip / poetry / pip-tools
     * all handle 404 on {@code /pypi/<pkg>/json} cleanly as "package
     * not found". Returning 200 with an empty {@code releases} object
     * is valid JSON but produces weirder secondary failures because
     * some tools treat it as "package exists but has zero releases" —
     * a distinct-and-confusing error class.
     */
    private Response allBlockedResponse(final String pkg) {
        EcsLogger.info("com.auto1.pantera.pypi")
            .message("/pypi/<pkg>/json has no non-blocked versions — returning 404")
            .eventCategory("web")
            .eventAction("json_filter")
            .eventOutcome("failure")
            .field("event.reason", "all_versions_blocked")
            .field("repository.name", this.repoName)
            .field("package.name", pkg)
            .log();
        return ResponseBuilder.notFound()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody(
                "All versions of '" + pkg
                    + "' are under cooldown; no versions available."
            )
            .build();
    }

    /**
     * Extract version keys from {@code releases} without fully parsing.
     * Empty list on malformed input signals "pass upstream through".
     */
    private static List<String> extractReleaseKeys(
        final ObjectMapper mapper, final byte[] bytes
    ) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        try {
            final JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.has("releases")) {
                return List.of();
            }
            final JsonNode releases = root.get("releases");
            if (releases == null || !releases.isObject()) {
                return List.of();
            }
            final List<String> out = new ArrayList<>();
            final Iterator<String> it = releases.fieldNames();
            while (it.hasNext()) {
                out.add(it.next());
            }
            return out;
        } catch ( final Exception ex) {
            return List.of();
        }
    }

    /**
     * Evaluate each candidate for cooldown in parallel; collect blocked
     * set. Per-version evaluation errors → "allowed".
     */
    private CompletableFuture<Set<String>> blockedVersions(
        final String pkg, final List<String> candidates, final String user
    ) {
        final List<CompletableFuture<Boolean>> futures =
            new ArrayList<>(candidates.size());
        for (final String version : candidates) {
            futures.add(this.isBlocked(pkg, version, user));
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
        final String pkg, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            pkg,
            version,
            user == null ? "pypi-json" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("Cooldown evaluation failed; treating version as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .error(err)
                    .log();
                return false;
            });
    }

    /**
     * Drain a reactive-streams body to a byte array.
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
