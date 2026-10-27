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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.cooldown.metadata.AllVersionsBlockedException;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.npm.Tarballs;
import com.auto1.pantera.npm.cooldown.NpmMetadataParser;
import com.auto1.pantera.npm.cooldown.NpmMetadataRewriter;
import com.auto1.pantera.npm.cooldown.NpmMetadataFilter;
import com.auto1.pantera.npm.misc.MetadataETag;
import com.auto1.pantera.npm.misc.PackumentDecision;
import com.auto1.pantera.npm.misc.PackumentSkeleton;
import com.auto1.pantera.npm.misc.PackumentVersion;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hu.akarnokd.rxjava2.interop.SingleInterop;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves {@code GET /<pkg>/<version-or-tag>} for npm proxy repositories
 * against the (cooldown-filtered) packument, and emits a single version's
 * manifest with its tarball URL rewritten to point back at Pantera.
 *
 * <p>Before this class, proxy repositories answered this endpoint with a
 * {@code {name, modified}} stub that still 200s — enough to satisfy older
 * npm/yarn clients that only sniff the shape, but not corepack, which reads
 * {@code dist.tarball} out of the response and fails when it is absent. This
 * class replaces the stub with a real per-version manifest, resolved the same
 * way {@code com.auto1.pantera.npm.http.SingleVersionSlice} does for hosted
 * repositories.</p>
 *
 * <p><b>Resolution goes through the packument, never a passthrough.</b>
 * Proxying {@code /<pkg>/<version>} straight to the upstream registry would
 * bypass cooldown filtering entirely — a version blocked by cooldown must
 * 404 here exactly as it is hidden from the full packument.</p>
 *
 * @since 2.3.0
 */
public final class VersionManifestResolver {

    /**
     * NPM Proxy facade.
     */
    private final NpmProxy npm;

    /**
     * Cooldown metadata filtering service; {@code null} when cooldown is
     * not wired for this repository.
     */
    private final CooldownMetadataService cooldownMetadata;

    /**
     * Repository type (e.g. {@code "npm"}); {@code null} when cooldown is
     * not wired for this repository.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param npm NPM Proxy facade
     * @param cooldownMetadata Cooldown metadata filtering service, or
     *  {@code null} if cooldown is not wired for this repository
     * @param repoType Repository type, or {@code null} if cooldown is not
     *  wired for this repository
     * @param repoName Repository name
     */
    public VersionManifestResolver(
        final NpmProxy npm,
        final CooldownMetadataService cooldownMetadata,
        final String repoType,
        final String repoName
    ) {
        this.npm = npm;
        this.cooldownMetadata = cooldownMetadata;
        this.repoType = repoType;
        this.repoName = repoName;
    }

    /**
     * Split {@code <pkg>/<ref>} into package and version-or-tag reference.
     *
     * <p>npm package names cannot contain {@code /} unless scoped, so the
     * split is exact: two segments with a leading {@code @} are a scoped
     * <em>package name</em> ({@code @types/node}), while two segments without
     * one are package + reference ({@code pnpm/11.5.1}).</p>
     *
     * @param rawPath Package path, with or without a leading slash
     * @return Parsed pair, or empty when the path is a plain packument request
     */
    static Optional<PackageRef> parse(final String rawPath) {
        Optional<PackageRef> result = Optional.empty();
        if (rawPath != null && !rawPath.isEmpty()) {
            final String trimmed;
            if (rawPath.startsWith("/")) {
                trimmed = rawPath.substring(1);
            } else {
                trimmed = rawPath;
            }
            final String[] segments = trimmed.split("/");
            String pkg = null;
            String ref = null;
            if (segments.length == 2 && !segments[0].startsWith("@")) {
                pkg = segments[0];
                ref = segments[1];
            } else if (segments.length == 3 && segments[0].startsWith("@")) {
                pkg = segments[0] + "/" + segments[1];
                ref = segments[2];
            }
            if (pkg != null && !ref.isEmpty() && !"-".equals(ref)) {
                result = Optional.of(
                    new PackageRef(
                        URLDecoder.decode(pkg, StandardCharsets.UTF_8),
                        URLDecoder.decode(ref, StandardCharsets.UTF_8)
                    )
                );
            }
        }
        return result;
    }

    /**
     * Resolve {@code pkg@ref} to one version manifest. The verdict (which
     * versions survive cooldown, where each dist-tag points) comes from the
     * packument's skeleton, shared with packument serves through the
     * filtered-metadata envelope; the manifest itself is streamed out of the
     * stored packument. Neither step holds the packument in memory.
     */
    CompletableFuture<Response> resolve(
        final String pkg, final String ref, final String tarballPrefix, final String vary,
        final Optional<String> clientETag, final AuditContext auditCtx, final String owner
    ) {
        return this.npm.getPackageMetadataOnly(pkg)
            .map(Optional::of)
            .toSingle(Optional.empty())
            .to(SingleInterop.get())
            .toCompletableFuture()
            .thenCompose(
                metadata -> metadata.isEmpty()
                    ? CompletableFuture.completedFuture(Optional.<PackumentDecision>empty())
                    : this.decision(pkg, auditCtx, owner)
            )
            .thenCompose(
                decision -> decision.map(
                    verdict -> this.emit(
                        this::packument, verdict, pkg, ref, tarballPrefix, vary, clientETag
                    )
                ).orElseGet(
                    () -> CompletableFuture.completedFuture(VersionManifestResolver.notFound(pkg, ref))
                )
            );
    }

    /**
     * Cooldown verdict for {@code pkg}; empty when every version is blocked.
     */
    private CompletableFuture<Optional<PackumentDecision>> decision(
        final String pkg, final AuditContext auditCtx, final String owner
    ) {
        final CompletableFuture<Optional<PackumentDecision>> result;
        if (this.cooldownMetadata == null || this.repoType == null) {
            AuditLogger.resolution(auditCtx, this.repoType, this.repoName, pkg, owner, List.of());
            result = this.skeleton(pkg).thenApply(bytes -> Optional.of(new PackumentDecision(bytes)));
        } else {
            result = this.cooldownMetadata.filterMetadataLazily(
                this.repoType, this.repoName, DownloadPackageSlice.SKELETON, pkg,
                () -> this.skeleton(pkg),
                new NpmMetadataParser(), new NpmMetadataFilter(), new NpmMetadataRewriter(),
                auditCtx, owner
            ).handle(
                (filtered, ex) -> {
                    final CompletableFuture<Optional<PackumentDecision>> verdict;
                    if (ex == null && filtered.isPresent()) {
                        verdict = CompletableFuture.completedFuture(
                            Optional.of(new PackumentDecision(filtered.get()))
                        );
                    } else if (ex != null && VersionManifestResolver.allBlocked(ex, pkg)) {
                        verdict = CompletableFuture.completedFuture(Optional.empty());
                    } else if (ex != null && DownloadPackageSlice.unparseable(ex)) {
                        // Never fall back to the unfiltered packument for a
                        // document cooldown cannot parse: fail the request.
                        verdict = CompletableFuture.failedFuture(ex);
                    } else {
                        if (ex != null) {
                            EcsLogger.warn("com.auto1.pantera.npm")
                                .message("Cooldown filter error (version resolver) - falling back to unfiltered")
                                .eventCategory("database")
                                .eventAction("filter_error")
                                .field("package.name", pkg)
                                .error(ex)
                                .field("log.source", "application")
                                .log();
                        }
                        verdict = this.skeleton(pkg).thenApply(
                            bytes -> Optional.of(new PackumentDecision(bytes))
                        );
                    }
                    return verdict;
                }
            ).thenCompose(java.util.function.Function.identity());
        }
        return result;
    }

    private CompletableFuture<byte[]> skeleton(final String pkg) {
        return this.packument(pkg).thenCompose(
            content -> content.map(body -> new PackumentSkeleton(body).bytes()).orElseGet(
                () -> CompletableFuture.failedFuture(
                    new IllegalStateException("packument vanished: " + pkg)
                )
            )
        );
    }

    private CompletableFuture<Optional<Content>> packument(final String pkg) {
        return this.npm.getPackageContentStream(pkg)
            .map(Optional::of)
            .toSingle(Optional.empty())
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    private static boolean allBlocked(final Throwable ex, final String pkg) {
        Throwable cause = ex;
        boolean blocked = false;
        while (cause != null) {
            if (cause instanceof AllVersionsBlockedException) {
                EcsLogger.info("com.auto1.pantera.npm")
                    .message("All versions blocked by cooldown (version resolver)")
                    .eventCategory("database")
                    .eventAction("all_versions_blocked")
                    .field("package.name", pkg)
                    .field("log.source", "application")
                    .log();
                blocked = true;
                break;
            }
            cause = cause.getCause();
        }
        return blocked;
    }

    /**
     * Answer {@code pkg@ref} from the verdict and the stored packument.
     *
     * @param packument Opens the stored packument of a package
     * @param decision Cooldown verdict on the packument's skeleton
     * @param pkg Package name
     * @param ref Version or dist-tag
     * @param tarballPrefix Client-facing tarball URL prefix
     * @param vary Vary header value
     * @param clientETag Client's If-None-Match
     * @return Manifest response, 404 when {@code ref} names no surviving version
     */
    CompletableFuture<Response> emit(
        final java.util.function.Function<String, CompletableFuture<Optional<Content>>> packument,
        final PackumentDecision decision, final String pkg, final String ref,
        final String tarballPrefix, final String vary, final Optional<String> clientETag
    ) {
        final Optional<String> version = decision.resolve(ref);
        if (version.isEmpty()) {
            return CompletableFuture.completedFuture(VersionManifestResolver.notFound(pkg, ref));
        }
        return packument.apply(pkg).thenCompose(
            content -> content.map(body -> new PackumentVersion(body, version.get()).manifest())
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
        ).thenApply(
            manifest -> manifest.map(
                node -> this.manifestResponse(node, pkg, tarballPrefix, vary, clientETag)
            ).orElseGet(() -> VersionManifestResolver.notFound(pkg, ref))
        );
    }

    private Response manifestResponse(
        final JsonNode manifest, final String pkg,
        final String tarballPrefix, final String vary, final Optional<String> clientETag
    ) {
        try {
            final ObjectMapper mapper = com.auto1.pantera.npm.misc.PackumentJson.MAPPER;
            final JsonNode dist = manifest.get("dist");
            if (dist != null && dist.isObject() && dist.has("tarball")) {
                ((ObjectNode) dist).put(
                    "tarball",
                    Tarballs.rewriteTarball(dist.get("tarball").asText(), tarballPrefix)
                );
            }
            final byte[] body = mapper.writeValueAsBytes(manifest);
            final String etag = new MetadataETag(body).calculate();
            final Response response;
            if (clientETag.isPresent() && clientETag.get().equals(etag)) {
                response = ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=300")
                    .varyHeader(vary)
                    .build();
            } else {
                response = ResponseBuilder.ok()
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("ETag", etag)
                    .header("Cache-Control", "public, max-age=300")
                    .varyHeader(vary)
                    .body(body)
                    .build();
            }
            return response;
        } catch (final IOException ex) {
            EcsLogger.warn("com.auto1.pantera.npm")
                .message("Failed to serialise version manifest")
                .eventCategory("web")
                .eventAction("version_resolution")
                .eventOutcome("failure")
                .field("package.name", pkg)
                .error(ex)
                .field("log.source", "application")
                .log();
            return ResponseBuilder.internalError().build();
        }
    }

    /**
     * Honest 404 body naming both the package and the unresolved reference —
     * matches {@code SingleVersionSlice}'s body shape exactly, and is never
     * the legacy {@code {name, modified}} stub.
     *
     * @param pkg Package name
     * @param ref Version string or dist-tag name
     * @return 404 response
     */
    private static Response notFound(final String pkg, final String ref) {
        return ResponseBuilder.notFound()
            .jsonBody(String.format(
                "{\"error\":\"version not found: %s\",\"package\":\"%s\"}", ref, pkg
            ))
            .build();
    }

    /**
     * Parsed package name / version-or-tag reference pair.
     */
    static final class PackageRef {

        /**
         * Package name (URL-decoded).
         */
        private final String pkgName;

        /**
         * Version string or tag name (URL-decoded).
         */
        private final String reference;

        /**
         * Ctor.
         *
         * @param pkgName Package name
         * @param reference Version string or tag name
         */
        PackageRef(final String pkgName, final String reference) {
            this.pkgName = pkgName;
            this.reference = reference;
        }

        /**
         * @return Package name
         */
        String pkg() {
            return this.pkgName;
        }

        /**
         * @return Version string or tag name
         */
        String ref() {
            return this.reference;
        }
    }
}
