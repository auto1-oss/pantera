/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.jcabi.log.Logger;

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
            Logger.warn(
                this,
                "Rejected SNAPSHOT version %s in RELEASE repository: %s",
                version,
                path
            );
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
            Logger.warn(
                this,
                "Rejected RELEASE version %s in SNAPSHOT repository: %s",
                version,
                path
            );
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
        Logger.debug(
            this,
            "Version policy check passed for %s (%s policy, version: %s)",
            path,
            this.policy,
            version
        );

        return origin.response(line, headers, body);
    }
}
