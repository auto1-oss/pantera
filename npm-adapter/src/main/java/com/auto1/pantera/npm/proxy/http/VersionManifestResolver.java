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

import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.npm.proxy.NpmProxy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
