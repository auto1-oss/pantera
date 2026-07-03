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

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker manifest-by-tag request detector.
 *
 * <p>Identifies {@code /v2/<name>/manifests/<reference>} requests where
 * {@code <reference>} is a <strong>tag</strong> (not a content digest).
 * Tags are the hot-path {@code docker pull foo:latest} resolution
 * endpoint; a Docker client calls
 * {@code GET /v2/<name>/manifests/<tag>} to resolve the tag to a
 * digest and fetch the manifest body in one round-trip — it does
 * <em>not</em> always call {@code /tags/list} first. Filtering
 * {@code /tags/list} alone therefore leaves the pull path unguarded.</p>
 *
 * <p>Reference shapes per the OCI / Docker distribution spec:</p>
 * <ul>
 *   <li><strong>Digest</strong>: {@code sha256:<hex>} or similar — always
 *       contains a {@code :} separator. Digests are immutable content
 *       identifiers; checking them against cooldown is handled by the
 *       artifact-fetch block path in {@code DockerProxyCooldownSlice}
 *       and is not the concern of this detector.</li>
 *   <li><strong>Tag</strong>: regex
 *       {@code [A-Za-z0-9_][A-Za-z0-9_.-]{0,127}} — never contains
 *       {@code :} since tags cannot include it. Simplest check:
 *       {@code !reference.contains(':')} ≡ "is a tag".</li>
 * </ul>
 *
 * <p>This detector returns {@code true} only for the tag case so
 * upstream routing can install a dedicated tag-resolution filter
 * without interfering with digest-addressed manifest fetches.</p>
 *
 * @since 2.2.0
 */
public final class DockerManifestByTagMetadataRequestDetector
    implements MetadataRequestDetector {

    /**
     * Pattern matching Docker manifest endpoint:
     * {@code /v2/<name>/manifests/<reference>}.
     */
    private static final Pattern MANIFESTS = Pattern.compile(
        "^/v2/(?<name>.+)/manifests/(?<reference>[^/]+)$"
    );

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "docker";

    /**
     * Reject anything containing {@code :} as a digest (sha256:..., sha512:...).
     */
    private static boolean isTag(final String reference) {
        return !reference.isEmpty() && reference.indexOf(':') < 0;
    }

    @Override
    public boolean isMetadataRequest(final String path) {
        final Matcher matcher = MANIFESTS.matcher(path);
        return matcher.matches() && isTag(matcher.group("reference"));
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        final Matcher matcher = MANIFESTS.matcher(path);
        if (matcher.matches() && isTag(matcher.group("reference"))) {
            return Optional.of(matcher.group("name"));
        }
        return Optional.empty();
    }

    /**
     * Extract the tag from a matching request path.
     *
     * @param path Request path
     * @return Tag if path is a manifest-by-tag request, otherwise empty
     */
    public Optional<String> extractTag(final String path) {
        final Matcher matcher = MANIFESTS.matcher(path);
        if (matcher.matches() && isTag(matcher.group("reference"))) {
            return Optional.of(matcher.group("reference"));
        }
        return Optional.empty();
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
