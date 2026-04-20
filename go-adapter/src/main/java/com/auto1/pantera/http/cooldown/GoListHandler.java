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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates cooldown-aware filtering of Go {@code /@v/list} responses.
 *
 * <p>This is where {@link GoMetadataFilter} and {@link GoMetadataParser}
 * — the pair registered via the cooldown SPI in {@code CooldownWiring} —
 * are actually consumed on the serve path. Without this handler the
 * registered bundle would be dead infrastructure: {@code /@v/list}
 * responses would pass through unfiltered and blocked versions would
 * leak to {@code go list -m -versions &lt;module&gt;}, {@code go mod
 * download}, and MVS resolution. Future readers: do not delete the
 * {@code goBundle} registration in {@code CooldownWiring} — its parser
 * and filter are live via this class.</p>
 *
 * <p>The flow mirrors {@link GoLatestHandler}:</p>
 * <ol>
 *   <li>Fetch {@code /@v/list} from upstream via the shared slice.</li>
 *   <li>On non-2xx, forward status + body unchanged — never transform
 *       upstream errors.</li>
 *   <li>Parse the newline-delimited body via {@link GoMetadataParser}. If
 *       parsing fails, pass the upstream bytes through unchanged.</li>
 *   <li>Evaluate every parsed version against cooldown and collect the
 *       blocked set; hand that set plus the parsed list to
 *       {@link GoMetadataFilter#filter(List, Set)}.</li>
 *   <li>Re-serialise as newline-separated plain text (matching the
 *       wire format Go clients expect) and return 200 with
 *       {@code text/plain; charset=utf-8}. Trailing newline is
 *       preserved when the upstream body had one.</li>
 *   <li>If every version is blocked, return 403 with a Go-client-parseable
 *       text body, matching the convention used by
 *       {@link GoCooldownResponseFactory} and {@link GoLatestHandler}.</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class GoListHandler {

    /**
     * Content-type returned for the list payload. The Go protocol serves
     * {@code /@v/list} as plain UTF-8 text; explicit charset avoids any
     * upstream ambiguity being forwarded to the client.
     */
    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    /**
     * Upstream slice, shared with the main Go proxy so cache / auth /
     * resilience layers apply to the list fetch the same way.
     */
    private final Slice upstream;

    /**
     * Cooldown service for per-version block evaluation.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector for release-date lookups.
     */
    private final CooldownInspector inspector;

    /**
     * Repository type (e.g. {@code "go"}, {@code "go-proxy"}).
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Path detector for {@code /@v/list} endpoints.
     */
    private final GoMetadataRequestDetector detector;

    /**
     * Parser for newline-delimited {@code /@v/list} bodies.
     */
    private final GoMetadataParser parser;

    /**
     * Filter that removes blocked versions from the parsed list.
     */
    private final GoMetadataFilter filter;

    /**
     * Constructor.
     *
     * @param upstream Upstream Go module proxy slice
     * @param cooldown Cooldown evaluation service
     * @param inspector Cooldown inspector for release-date lookups
     * @param repoType Repository type identifier (e.g. {@code "go"})
     * @param repoName Repository name
     */
    public GoListHandler(
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
        this.detector = new GoMetadataRequestDetector();
        this.parser = new GoMetadataParser();
        this.filter = new GoMetadataFilter();
    }

    /**
     * Whether the handler should intercept the given path.
     *
     * @param path Request path
     * @return true for {@code /<module>/@v/list} paths with a non-empty module
     */
    public boolean matches(final String path) {
        return this.detector.isMetadataRequest(path)
            && this.detector.extractPackageName(path).isPresent();
    }

    /**
     * Handle a {@code /@v/list} request with cooldown-aware filtering.
     *
     * @param line Request line (must be an {@code /@v/list} path)
     * @param user Authenticated user (for cooldown bookkeeping)
     * @return Future response
     */
    public CompletableFuture<Response> handle(final RequestLine line, final String user) {
        final String path = line.uri().getPath();
        final String module = this.detector.extractPackageName(path).orElseThrow(
            () -> new IllegalArgumentException("Not a @v/list path: " + path)
        );
        return this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    // Non-2xx from upstream — forward status + body, no filtering.
                    return bodyBytes(resp.body()).thenApply(bytes ->
                        ResponseBuilder.from(resp.status())
                            .headers(resp.headers())
                            .body(bytes)
                            .build()
                    );
                }
                return bodyBytes(resp.body()).thenCompose(bytes ->
                    this.processUpstream(bytes, resp.headers(), module, user)
                );
            });
    }

    /**
     * Process a successful upstream response: parse, evaluate, filter,
     * and re-serialise. Falls back to pass-through on parse failure and
     * to 403 when every parsed version is blocked.
     */
    private CompletableFuture<Response> processUpstream(
        final byte[] upstreamBytes,
        final Headers upstreamHeaders,
        final String module,
        final String user
    ) {
        final List<String> versions;
        try {
            versions = this.parser.parse(upstreamBytes);
        } catch (final MetadataParseException ex) {
            EcsLogger.warn("com.auto1.pantera.go")
                .message("Failed to parse @v/list body — passing upstream body through")
                .eventCategory("web")
                .eventAction("list_filter")
                .eventOutcome("success")
                .field("event.reason", "upstream_malformed")
                .field("repository.name", this.repoName)
                .field("package.name", module)
                .error(ex)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(upstreamHeaders)
                    .body(upstreamBytes)
                    .build()
            );
        }
        if (versions.isEmpty()) {
            // Nothing to filter; forward the upstream bytes verbatim so
            // trailing-newline / whitespace quirks survive round-trip.
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(upstreamHeaders)
                    .body(upstreamBytes)
                    .build()
            );
        }
        return this.blockedVersions(module, versions, user).thenApply(blocked -> {
            if (blocked.isEmpty()) {
                // Fast path: nothing blocked, hand the upstream bytes back
                // unchanged so any trailing newline / ordering is preserved.
                return ResponseBuilder.ok()
                    .headers(upstreamHeaders)
                    .body(upstreamBytes)
                    .build();
            }
            final List<String> kept = this.filter.filter(versions, blocked);
            if (kept.isEmpty()) {
                return this.allBlockedResponse(module);
            }
            EcsLogger.info("com.auto1.pantera.go")
                .message("@v/list filtered: removed cooldown-blocked versions")
                .eventCategory("web")
                .eventAction("list_filter")
                .eventOutcome("success")
                .field("repository.name", this.repoName)
                .field("package.name", module)
                .field("metrics.total_versions", versions.size())
                .field("metrics.blocked_versions", blocked.size())
                .field("metrics.served_versions", kept.size())
                .log();
            final byte[] body = serialise(kept, endsWithNewline(upstreamBytes));
            return ResponseBuilder.ok()
                .header("Content-Type", CONTENT_TYPE)
                .body(body)
                .build();
        });
    }

    /**
     * 403 response for "every version blocked" — mirrors the analogous
     * branch in {@link GoLatestHandler#handle}. Go toolchain surfaces
     * the body verbatim to the operator, so a human-readable reason is
     * appropriate.
     */
    private Response allBlockedResponse(final String module) {
        EcsLogger.info("com.auto1.pantera.go")
            .message("@v/list has no non-blocked versions — returning 403")
            .eventCategory("web")
            .eventAction("list_filter")
            .eventOutcome("failure")
            .field("event.reason", "all_versions_blocked")
            .field("repository.name", this.repoName)
            .field("package.name", module)
            .log();
        return ResponseBuilder.forbidden()
            .header("X-Pantera-Cooldown", "all-blocked")
            .textBody(
                "All versions of '" + module
                    + "' are under cooldown; no versions available."
            )
            .build();
    }

    /**
     * Evaluate every candidate against cooldown and collect the blocked
     * set. Evaluation errors are treated as "not blocked" so a transient
     * inspector failure cannot cascade to denying every list response.
     */
    private CompletableFuture<Set<String>> blockedVersions(
        final String module, final List<String> candidates, final String user
    ) {
        final List<CompletableFuture<Boolean>> futures =
            new ArrayList<>(candidates.size());
        for (final String version : candidates) {
            futures.add(this.isBlocked(module, version, user));
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

    /**
     * Ask the cooldown service whether {@code version} is currently
     * blocked for this repo. Swallows per-version errors to "allowed".
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private CompletableFuture<Boolean> isBlocked(
        final String module, final String version, final String user
    ) {
        final CooldownRequest req = new CooldownRequest(
            this.repoType,
            this.repoName,
            module,
            version,
            user == null ? "go-list" : user,
            Instant.now()
        );
        return this.cooldown.evaluate(req, this.inspector)
            .thenApply(result -> result.blocked())
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.go")
                    .message("Cooldown evaluation failed; treating version as allowed")
                    .eventCategory("database")
                    .eventAction("cooldown_evaluate")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("package.version", version)
                    .error(err)
                    .log();
                return false;
            });
    }

    /**
     * Serialise the kept versions to newline-delimited UTF-8, preserving
     * the upstream's trailing-newline convention. Some Go clients are
     * strict about the list format, so we mirror what the origin emitted.
     */
    private static byte[] serialise(
        final List<String> versions, final boolean trailingNewline
    ) {
        final StringBuilder sb = new StringBuilder(versions.size() * 16);
        for (int idx = 0; idx < versions.size(); idx++) {
            if (idx > 0) {
                sb.append('\n');
            }
            sb.append(versions.get(idx));
        }
        if (trailingNewline) {
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Check whether {@code bytes} ends with a newline (so we round-trip
     * the upstream's framing exactly).
     */
    private static boolean endsWithNewline(final byte[] bytes) {
        return bytes != null && bytes.length > 0
            && bytes[bytes.length - 1] == (byte) '\n';
    }

    /**
     * Drain a reactive-streams body to a byte array. Mirrors the helper
     * in {@link GoLatestHandler}.
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
