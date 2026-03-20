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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven version policy enforcement slice.
 * Validates RELEASE vs SNAPSHOT policies on artifact uploads.
 * 
 * <p>Policies:
 * <ul>
 *   <li>RELEASE: Only non-SNAPSHOT versions allowed</li>
 *   <li>SNAPSHOT: Only SNAPSHOT versions allowed</li>
 *   <li>MIXED: Both allowed (default)</li>
 * </ul>
 * 
 * @since 1.0
 */
public final class VersionPolicySlice implements Slice {

    /**
     * Pattern to extract version from Maven path.
     * Matches: /group/artifact/version/artifact-version-classifier.ext
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        ".*/([^/]+)/([^/]+)/([^/]+)/\\2-\\3.*"
    );

    /**
     * Version policy.
     */
    public enum Policy {
        /**
         * Only RELEASE versions (non-SNAPSHOT).
         */
        RELEASE,

        /**
         * Only SNAPSHOT versions.
         */
        SNAPSHOT,

        /**
         * Both RELEASE and SNAPSHOT allowed.
         */
        MIXED
    }

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Version policy.
     */
    private final Policy policy;

    /**
     * Constructor.
     * @param origin Origin slice
     * @param policy Version policy
     */
    public VersionPolicySlice(final Slice origin, final Policy policy) {
        this.origin = origin;
        this.policy = policy;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Only enforce on PUT/POST (uploads)
        final String method = line.method().value();
        if (!"PUT".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            return origin.response(line, headers, body);
        }

        // Mixed policy - allow everything
        if (this.policy == Policy.MIXED) {
            return origin.response(line, headers, body);
        }

        // Extract version from path
        final String path = line.uri().getPath();
        final Matcher matcher = VERSION_PATTERN.matcher(path);

        if (!matcher.matches()) {
            // Cannot determine version - allow (might be metadata file)
            return origin.response(line, headers, body);
        }

        final String version = matcher.group(3);
        final boolean isSnapshot = version.endsWith("-SNAPSHOT");

        // Enforce policy
        if (this.policy == Policy.RELEASE && isSnapshot) {
            EcsLogger.warn("com.auto1.pantera.maven")
                .message("Rejected SNAPSHOT version in RELEASE repository (policy: RELEASE)")
                .eventCategory("repository")
                .eventAction("version_policy_check")
                .eventOutcome("failure")
                .field("package.version", version)
                .field("package.path", path)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest()
                    .textBody(
                        String.format(
                            "SNAPSHOT versions are not allowed in RELEASE repository. " +
                            "Rejected version: %s",
                            version
                        )
                    )
                    .build()
            );
        }

        if (this.policy == Policy.SNAPSHOT && !isSnapshot) {
            EcsLogger.warn("com.auto1.pantera.maven")
                .message("Rejected RELEASE version in SNAPSHOT repository (policy: SNAPSHOT)")
                .eventCategory("repository")
                .eventAction("version_policy_check")
                .eventOutcome("failure")
                .field("package.version", version)
                .field("package.path", path)
                .log();
            return CompletableFuture.completedFuture(
                ResponseBuilder.badRequest()
                    .textBody(
                        String.format(
                            "RELEASE versions are not allowed in SNAPSHOT repository. " +
                            "Rejected version: %s (must end with -SNAPSHOT)",
                            version
                        )
                    )
                    .build()
            );
        }

        // Policy check passed
        EcsLogger.debug("com.auto1.pantera.maven")
            .message("Version policy check passed (policy: " + this.policy.toString() + ")")
            .eventCategory("repository")
            .eventAction("version_policy_check")
            .eventOutcome("success")
            .field("package.path", path)
            .field("package.version", version)
            .log();

        return origin.response(line, headers, body);
    }
}
