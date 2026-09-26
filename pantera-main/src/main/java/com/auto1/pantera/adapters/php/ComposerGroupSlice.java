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
package com.auto1.pantera.adapters.php;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.group.SliceResolver;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composer group repository slice.
 *
 * <p>Handles Composer-specific group behavior:
 * <ul>
 *   <li>{@code packages.json}: sequential-first across members; the first
 *       member that returns 200 wins. Its body is parsed, the
 *       {@code metadata-url} / {@code providers-url} fields are rewritten
 *       to point at the group's own basePath (so p2 fetches stay inside
 *       pantera), and the result is served.</li>
 *   <li>{@code /p2/...}: local (hosted) members first, in declared order,
 *       then proxy members — but a package name owned by a local member
 *       (its stable or {@code ~dev} file exists there) is never looked up
 *       on a proxy, so an upstream cannot add versions to a private package
 *       and private names are not sent upstream.</li>
 *   <li>Everything else: delegated to {@code GroupResolver}.</li>
 * </ul>
 *
 * <p>Member answers follow {@code GroupResolver}: 200, 401/403 and a
 * cooldown verdict are authoritative and relayed; 404 falls through; a
 * member failure (5xx, exception) is remembered, and a walk that ends
 * without an answer after a failure is 503 + {@code Retry-After}, never
 * 404 (see {@link ComposerMemberWalk}).</p>
 *
 * <p><b>v2.2.0 BREAKING:</b> previously this slice fanned out to ALL members
 * in parallel and merged every member's {@code packages.json} into a
 * union. That was symmetric with the maven-metadata.xml merge in
 * {@code MavenGroupSlice}; both are now sequential-first for the same
 * reasons (member namespaces are typically disjoint, JFrog/Nexus virtual
 * repos behave the same way, the union added per-request upstream
 * amplification with no real benefit).
 *
 * <p><b>Trace context contract.</b> Trace context (trace.id / span.id /
 * span.parent.id) is inherited from the {@code EcsLoggingSlice} MDC scope
 * set at request entry. Any async hop introduced in this slice MUST use
 * {@code ContextualExecutor.contextualize(...)} (or an equivalent MDC
 * capture-and-restore) to preserve trace.id across the executor
 * boundary — without it, log lines emitted from the worker thread
 * surface in Kibana with no trace correlation back to the originating
 * request.
 *
 * @since 1.0
 */
public final class ComposerGroupSlice implements Slice {

    /**
     * Composer v2 per-package metadata path: stable file or {@code ~dev}
     * file of {@code vendor/package}.
     */
    private static final Pattern P2_FILE = Pattern.compile(
        "^(?<base>.*/p2/[^/]+/[^/~$]+)(?<dev>~dev)?\\.json$"
    );

    /**
     * Delegate group slice for non-packages.json requests.
     * Uses the standard GroupResolver with artifact index, proxy awareness,
     * circuit breaker, and error handling.
     */
    private final Slice delegate;

    /**
     * Group repository name.
     */
    private final String group;

    /**
     * Member repository names.
     */
    private final List<String> members;

    /**
     * Members that are proxies (or groups containing proxies).
     */
    private final Set<String> proxies;

    /**
     * Sequential member walk.
     */
    private final ComposerMemberWalk walk;

    /**
     * Base path for metadata-url (e.g. "/test_prefix/php_group").
     * Built from global prefix + group name so Composer can resolve
     * p2 URLs as host-absolute paths.
     */
    private final String basePath;

    /**
     * Constructor for a group whose members are all hosted.
     *
     * @param delegate Delegate group slice (GroupResolver with index/proxy support)
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members List of member repository names
     * @param port Server port
     * @param globalPrefix Global URL prefix (e.g. "test_prefix"), empty string if none
     */
    public ComposerGroupSlice(
        final Slice delegate,
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final String globalPrefix
    ) {
        this(delegate, resolver, group, members, port, globalPrefix, Set.of());
    }

    /**
     * Constructor.
     *
     * <p>Cooldown is deliberately NOT a group concern here either — it is a
     * proxy-repo feature (see {@code MavenGroupSlice} class javadoc for the
     * full rationale). Each {@code -proxy} member filters its own metadata
     * and records blocks under its own repo identity; this group only relays
     * a member's already-filtered {@code packages.json}/p2 response.
     *
     * @param delegate Delegate group slice (GroupResolver with index/proxy support)
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members List of member repository names
     * @param port Server port
     * @param globalPrefix Global URL prefix (e.g. "test_prefix"), empty string if none
     * @param proxies Members that are proxies or groups containing proxies
     */
    public ComposerGroupSlice(
        final Slice delegate,
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final String globalPrefix,
        final Set<String> proxies
    ) {
        this.delegate = delegate;
        this.group = group;
        this.members = members;
        this.proxies = Set.copyOf(proxies);
        this.walk = new ComposerMemberWalk(resolver, port, group);
        if (globalPrefix != null && !globalPrefix.isEmpty()) {
            this.basePath = "/" + globalPrefix + "/" + group;
        } else {
            this.basePath = "/" + group;
        }
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String method = line.method().value();
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            return ResponseBuilder.methodNotAllowed().completedFuture();
        }

        final String path = line.uri().getPath();

        // For packages.json, the first member that answers wins
        if (path.endsWith("/packages.json") || "/packages.json".equals(path)) {
            return body.asBytesFuture().thenCompose(
                ignored -> this.packagesJson(line, dropFullPathHeader(headers), 0,
                    new ComposerMemberWalk.State())
            );
        }

        // For p2 metadata requests, try each member directly.
        // The artifact index cannot match p2 paths (it stores package names,
        // not filesystem paths), so the delegate GroupResolver would skip local
        // members and return 404.
        if (path.contains("/p2/")) {
            return body.asBytesFuture().thenCompose(
                ignored -> this.p2(line, dropFullPathHeader(headers))
            );
        }

        // For other requests (tarballs, artifacts), delegate to GroupResolver
        // which has artifact index, proxy awareness, circuit breaker, and error handling
        return this.delegate.response(line, headers, body);
    }

    /**
     * Resolve a p2 metadata file: hosted members first (declared order),
     * then — only when no hosted member owns the package — proxy members.
     *
     * <p>A member's cooldown verdict (any response carrying
     * {@code X-Pantera-Cooldown}, e.g. the all-versions-blocked
     * 404) is authoritative, as in {@code GroupResolver}: it ends the walk
     * and is relayed verbatim -- status, marker and reason body -- instead
     * of being discarded for a bare 404 or, worse, letting a later member
     * serve the blocked versions.</p>
     *
     * @param line Group request line
     * @param headers Sanitised request headers
     * @return Response
     */
    private CompletableFuture<Response> p2(final RequestLine line, final Headers headers) {
        final List<String> hosted = this.members.stream()
            .filter(m -> !this.proxies.contains(m)).toList();
        final List<String> proxied = this.members.stream()
            .filter(this.proxies::contains).toList();
        final ComposerMemberWalk.State state = new ComposerMemberWalk.State();
        return this.walk.first(hosted, line, headers, state).thenCompose(local -> {
            if (local.isPresent()) {
                return CompletableFuture.completedFuture(local.get());
            }
            if (proxied.isEmpty()) {
                return CompletableFuture.completedFuture(this.walk.exhausted(state, line));
            }
            if (state.failed()) {
                // A hosted member could not answer: the package may be ours.
                // Asking a proxy now could serve upstream versions of a
                // private name, so report the outage instead.
                this.proxiesSkipped(line, "a hosted member is unavailable");
                return CompletableFuture.completedFuture(this.walk.exhausted(state, line));
            }
            return this.ownedLocally(line, headers, hosted, state).thenCompose(owned -> {
                if (owned) {
                    this.proxiesSkipped(line, "the package is owned by a hosted member");
                    return CompletableFuture.completedFuture(this.walk.exhausted(state, line));
                }
                return this.walk.first(proxied, line, headers, state).thenApply(
                    upstream -> upstream.orElseGet(() -> this.walk.exhausted(state, line))
                );
            });
        });
    }

    /**
     * Whether a hosted member owns the package of a p2 request through its
     * other metadata file (the stable file for a {@code ~dev} request and
     * vice versa). Unknown (a hosted member failed) counts as owned and is
     * recorded in {@code state}.
     *
     * @param line Group request line
     * @param headers Sanitised request headers
     * @param hosted Hosted members
     * @param state Walk state
     * @return True when proxies must not be consulted
     */
    private CompletableFuture<Boolean> ownedLocally(
        final RequestLine line,
        final Headers headers,
        final List<String> hosted,
        final ComposerMemberWalk.State state
    ) {
        final Matcher matcher = P2_FILE.matcher(line.uri().getPath());
        if (hosted.isEmpty() || !matcher.matches()) {
            return CompletableFuture.completedFuture(false);
        }
        final String sibling = matcher.group("dev") == null
            ? matcher.group("base") + "~dev.json"
            : matcher.group("base") + ".json";
        return this.walk.first(
            hosted, new RequestLine(line.method().value(), sibling, line.version()),
            headers, state
        ).thenCompose(answer -> {
            if (answer.isEmpty()) {
                return CompletableFuture.completedFuture(state.failed());
            }
            return answer.get().body().asBytesFuture().thenApply(drained -> true);
        });
    }

    /**
     * Log that proxy members were deliberately not consulted.
     *
     * @param line Group request line
     * @param reason Why
     */
    private void proxiesSkipped(final RequestLine line, final String reason) {
        EcsLogger.info("com.auto1.pantera.adapters.php")
            .message("Composer group did not consult proxy members: " + reason)
            .eventCategory("web")
            .eventAction("group_proxy_skip")
            .field("repository.name", this.group)
            .field("url.path", line.uri().getPath())
            .field("log.source", "application")
            .log();
    }

    /**
     * Try members in declared order for {@code packages.json}; the first
     * member that returns 200 wins. The winning JSON is rewritten so
     * {@code metadata-url} / {@code providers-url} point at the group's own
     * basePath — without that, Composer would follow the upstream's URL and
     * bypass pantera entirely (cooldown filter + cache + auth). A member's
     * 401/403 is relayed; a member whose 200 cannot be parsed counts as a
     * member failure.
     *
     * <p>v2.2.0 sequential-first replacement for the previous fanout+merge —
     * see class javadoc.
     *
     * @param line Request line
     * @param headers Sanitised headers
     * @param idx Index of the member to try
     * @param state Walk state
     * @return Single-member response, packages-url rewritten to group basePath
     */
    private CompletableFuture<Response> packagesJson(
        final RequestLine line,
        final Headers headers,
        final int idx,
        final ComposerMemberWalk.State state
    ) {
        if (idx >= this.members.size()) {
            return CompletableFuture.completedFuture(this.walk.exhausted(state, line));
        }
        final String member = this.members.get(idx);
        return this.walk.first(List.of(member), line, headers, state).thenCompose(answer -> {
            if (answer.isEmpty()) {
                return this.packagesJson(line, headers, idx + 1, state);
            }
            final Response resp = answer.get();
            if (resp.status() != RsStatus.OK) {
                return CompletableFuture.completedFuture(resp);
            }
            return resp.body().asBytesFuture().thenCompose(bytes -> {
                final Optional<Response> rewritten = this.rewritePackagesJson(member, bytes);
                if (rewritten.isPresent()) {
                    return CompletableFuture.completedFuture(rewritten.get());
                }
                state.fail(false, 0L);
                return this.packagesJson(line, headers, idx + 1, state);
            });
        });
    }

    /**
     * Parse the winning member's {@code packages.json} bytes, rewrite
     * {@code metadata-url} / {@code providers-url} (and group-level
     * {@code providers}) to point at the group's own basePath, and emit the
     * resulting bytes as a 200. This rewrite is essential — without it,
     * Composer would follow the upstream's metadata-url and bypass pantera
     * entirely (cooldown filter + cache + auth).
     *
     * <p>UID injection on package versions is preserved: Composer v1 uses
     * the {@code uid} field for cache invalidation; we inject a stable UUID
     * if the upstream omitted it.</p>
     *
     * @param member Winning member
     * @param bytes Member body
     * @return Rewritten response, or empty when the body is not a JSON object
     */
    private Optional<Response> rewritePackagesJson(final String member, final byte[] bytes) {
        final JsonObject json;
        try (JsonReader reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            json = reader.readObject();
        } catch (Exception e) {
            EcsLogger.warn("com.auto1.pantera.adapters.php")
                .message("Failed to parse packages.json from member: " + member)
                .eventCategory("web")
                .eventAction("packages_parse")
                .eventOutcome("failure")
                .error(e)
                .field("repository.name", this.group)
                .field("log.source", "application")
                .log();
            return Optional.empty();
        }

        final JsonObjectBuilder out = Json.createObjectBuilder();
        // Copy every field except the URL fields we rewrite.
        json.forEach((key, value) -> {
            if (!"packages".equals(key)
                && !"metadata-url".equals(key)
                && !"providers-url".equals(key)
                && !"providers".equals(key)) {
                out.add(key, value);
            }
        });

        final boolean hasSatisFormat = json.containsKey("providers")
            && json.get("providers") instanceof JsonObject;

        if (hasSatisFormat) {
            // Satis format: copy provider table verbatim; rewrite providers-url
            // to the group basePath so client p2 lookups land back at this
            // group slice (which routes p2 lookups through p2()).
            out.add("packages", Json.createObjectBuilder());
            out.add("providers-url", this.basePath + "/p2/%package%.json");
            out.add("providers", json.getJsonObject("providers"));
            EcsLogger.debug("com.auto1.pantera.adapters.php")
                .message("Sequential winner (Satis format)")
                .eventCategory("web")
                .eventAction("packages_fetch")
                .eventOutcome("success")
                .field("repository.name", this.group)
                .field("member.name", member)
                .field("log.source", "application")
                .log();
        } else {
            // Traditional format: copy packages, inject uid where missing,
            // rewrite metadata-url to group basePath. Composer v1 needs an
            // absolute path, not relative.
            final JsonObjectBuilder packagesBuilder = Json.createObjectBuilder();
            if (json.containsKey("packages")
                && json.get("packages") instanceof JsonObject) {
                final JsonObject packages = json.getJsonObject("packages");
                packages.forEach((name, versionsObj) -> {
                    if (!(versionsObj instanceof JsonObject)) {
                        return;
                    }
                    final JsonObject versions = (JsonObject) versionsObj;
                    final JsonObjectBuilder pkgWithUids = Json.createObjectBuilder();
                    versions.forEach((version, versionData) -> {
                        if (!(versionData instanceof JsonObject)) {
                            return;
                        }
                        final JsonObject versionObj = (JsonObject) versionData;
                        final JsonObjectBuilder versionWithUid =
                            Json.createObjectBuilder(versionObj);
                        if (!versionObj.containsKey("uid")) {
                            versionWithUid.add("uid", UUID.randomUUID().toString());
                        }
                        pkgWithUids.add(version, versionWithUid.build());
                    });
                    packagesBuilder.add(name, pkgWithUids.build());
                });
            }
            out.add("metadata-url", this.basePath + "/p2/%package%.json");
            out.add("packages", packagesBuilder.build());
            EcsLogger.debug("com.auto1.pantera.adapters.php")
                .message("Sequential winner (traditional format)")
                .eventCategory("web")
                .eventAction("packages_fetch")
                .eventOutcome("success")
                .field("repository.name", this.group)
                .field("member.name", member)
                .field("log.source", "application")
                .log();
        }

        final byte[] outBytes = out.build().toString().getBytes(StandardCharsets.UTF_8);
        return Optional.of(
            ResponseBuilder.ok()
                .header("Content-Type", "application/json")
                .body(outBytes)
                .build()
        );
    }

    /**
     * Drop X-FullPath header to avoid TrimPathSlice recursion issues.
     *
     * @param headers Original headers
     * @return Headers without X-FullPath
     */
    private static Headers dropFullPathHeader(final Headers headers) {
        return new Headers(
            headers.asList().stream()
                .filter(h -> !"X-FullPath".equalsIgnoreCase(h.getKey()))
                .toList()
        );
    }

}
