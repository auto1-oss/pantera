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
package com.auto1.pantera.http.cache;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composite key for the unified negative cache (404 caching).
 *
 * <p>Every cached 404 is indexed by four fields:
 * <ul>
 *   <li>{@code scope} — the repository name (hosted, proxy, or group)</li>
 *   <li>{@code repoType} — the adapter type ({@code "maven"}, {@code "npm"}, etc.)</li>
 *   <li>{@code artifactName} — the canonical artifact identifier
 *       (e.g. {@code "@scope/pkg"}, {@code "org.spring:spring-core"})</li>
 *   <li>{@code artifactVersion} — the version string; empty for metadata endpoints</li>
 * </ul>
 *
 * <p>{@link #flat()} URL-encodes each field before joining with {@code ':'} so
 * embedded colons (Maven {@code groupId:artifactId}, version metadata) survive
 * round-trips. {@link #parse(String)} performs the inverse decode.
 *
 * @since 2.2.0
 */
public record NegativeCacheKey(
    String scope,
    String repoType,
    String artifactName,
    String artifactVersion
) {

    public NegativeCacheKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(repoType, "repoType");
        Objects.requireNonNull(artifactName, "artifactName");
        if (artifactVersion == null) {
            artifactVersion = "";
        }
    }

    /**
     * Flat string representation. Each segment is URL-encoded so embedded
     * {@code ':'} characters do not corrupt the delimiter structure.
     *
     * @return colon-delimited, URL-encoded key string
     */
    public String flat() {
        return enc(this.scope) + ':' + enc(this.repoType)
            + ':' + enc(this.artifactName) + ':' + enc(this.artifactVersion);
    }

    /**
     * Parse a flat key produced by {@link #flat()} back into structured form.
     * Returns {@code null} if the input does not have exactly four colon-
     * delimited segments.
     *
     * @param flat colon-delimited URL-encoded key
     * @return decoded composite key, or {@code null} if malformed
     */
    public static NegativeCacheKey parse(final String flat) {
        if (flat == null) {
            return null;
        }
        final String[] parts = flat.split(":", -1);
        if (parts.length != 4) {
            return null;
        }
        return new NegativeCacheKey(
            dec(parts[0]), dec(parts[1]), dec(parts[2]), dec(parts[3])
        );
    }

    /**
     * Go module path: {@code <module>/@v/<version>.<ext>} or {@code <module>/@latest}.
     */
    private static final Pattern GO_PATH = Pattern.compile(
        "^(.+)/@v/v?([^/]+?)\\.(?:info|mod|zip|ziphash)$"
    );

    /**
     * Maven artifact path: {@code <groupId-segments>/<artifactId>/<version>/<filename>}.
     * The artifactId-{version} prefix on the filename anchors the parse.
     */
    private static final Pattern MAVEN_PATH = Pattern.compile(
        "^(.+?)/([^/]+)/([^/]+)/\\2-\\3(?:[.-][^/]+)?$"
    );

    /**
     * NPM tarball path: {@code <pkg>/-/<basename>-<version>.tgz}, optionally scoped.
     */
    private static final Pattern NPM_TARBALL = Pattern.compile(
        "^((?:@[^/]+/)?[^/]+)/-/(?:[^/]+)-([^/]+?)\\.tgz$"
    );

    /**
     * PyPI wheel/sdist path: {@code packages/<pkg>-<version>(-<rest>)?\\.(whl|tar\\.gz)}.
     */
    private static final Pattern PYPI_FILE = Pattern.compile(
        "^(?:packages/(?:[a-f0-9/]+)?)?([^/]+?)-([^-/]+?)(?:-[^/]+)?\\.(?:whl|tar\\.gz)$"
    );

    /**
     * Build a key from a raw URL path. Best-effort parses the path into
     * {@code (artifactName, artifactVersion)} per adapter convention so the
     * admin UI shows separate fields. If parsing doesn't match, falls back
     * to storing the whole path as {@code artifactName} (still unique).
     *
     * @param scope Repository name
     * @param repoType Adapter type — drives which path-shape parser to try
     * @param path Request path (raw, may include leading slash)
     * @return composite key
     */
    public static NegativeCacheKey fromPath(
        final String scope, final String repoType, final String path
    ) {
        final String trimmed;
        if (path == null || path.isEmpty()) {
            trimmed = "";
        } else if (path.charAt(0) == '/') {
            trimmed = path.substring(1);
        } else {
            trimmed = path;
        }
        final String[] nameAndVersion = parseByType(repoType, trimmed);
        return new NegativeCacheKey(
            scope, repoType, nameAndVersion[0], nameAndVersion[1]
        );
    }

    private static String[] parseByType(final String repoType, final String path) {
        if (path.isEmpty()) {
            return new String[]{"", ""};
        }
        final String type = repoType == null ? "" : repoType.toLowerCase();
        if (type.startsWith("go")) {
            final Matcher m = GO_PATH.matcher(path);
            if (m.matches()) {
                return new String[]{m.group(1), m.group(2)};
            }
        } else if (type.startsWith("maven") || type.startsWith("gradle")) {
            final Matcher m = MAVEN_PATH.matcher(path);
            if (m.matches()) {
                return new String[]{m.group(1) + "/" + m.group(2), m.group(3)};
            }
        } else if (type.startsWith("npm")) {
            final Matcher m = NPM_TARBALL.matcher(path);
            if (m.matches()) {
                return new String[]{m.group(1), m.group(2)};
            }
        } else if (type.startsWith("pypi")) {
            final Matcher m = PYPI_FILE.matcher(path);
            if (m.matches()) {
                return new String[]{m.group(1), m.group(2)};
            }
        }
        return new String[]{path, ""};
    }

    /**
     * Minimal escape: only {@code :} (which would corrupt the delimiter) and
     * {@code %} (the escape char itself, for round-trip safety) are encoded.
     * All other characters — including {@code /}, {@code @}, alphanumerics —
     * pass through, keeping flat keys human-readable in admin tooling.
     */
    private static String enc(final String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.indexOf(':') < 0 && value.indexOf('%') < 0) {
            return value;
        }
        final StringBuilder sb = new StringBuilder(value.length() + 6);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == ':') {
                sb.append("%3A");
            } else if (c == '%') {
                sb.append("%25");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String dec(final String value) {
        if (value.isEmpty() || value.indexOf('%') < 0) {
            return value;
        }
        final StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            final char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                final char h1 = value.charAt(i + 1);
                final char h2 = value.charAt(i + 2);
                if ((h1 == '3') && (h2 == 'A' || h2 == 'a')) {
                    sb.append(':');
                    i += 3;
                    continue;
                }
                if ((h1 == '2') && (h2 == '5')) {
                    sb.append('%');
                    i += 3;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
