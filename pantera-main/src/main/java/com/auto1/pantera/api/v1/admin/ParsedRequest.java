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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.group.ArtifactNameParser;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a repository request asks for: the package (in the form an operator
 * types it — maven {@code groupId:artifactId}, npm {@code @scope/name},
 * go module path, composer {@code vendor/name}), the version when the path
 * names one, and whether it is an artifact or a metadata listing.
 *
 * @param pkg Package, null when the path names none
 * @param version Version, null for metadata
 * @param kind {@code artifact} or {@code metadata}
 * @since 2.2.9
 */
public record ParsedRequest(String pkg, String version, String kind) {

    /**
     * Parse a repository-relative path.
     *
     * @param type Repository type
     * @param path Decoded repository-relative path
     * @return Parsed request
     */
    static ParsedRequest of(final String type, final String path) {
        final String family = type.toLowerCase(Locale.ROOT)
            .replaceAll("-(group|proxy|local|remote)$", "");
        final String version = NegativeCacheKey.fromPath("s", type, ParsedRequest.leafView(family, path))
            .artifactVersion();
        final String kind = version.isEmpty() ? "metadata" : "artifact";
        final String clean = path.replaceFirst("^/+", "");
        final Optional<String> pkg;
        switch (family) {
            case "maven":
            case "gradle":
                pkg = ParsedRequest.maven(clean, version.isEmpty());
                break;
            case "npm":
                pkg = version.isEmpty()
                    ? Optional.of(clean.replaceAll("/+$", "")).filter(name -> !name.contains("/-/"))
                    : ArtifactNameParser.parse(type, path);
                break;
            case "pypi":
                pkg = clean.startsWith("simple/")
                    ? Optional.of(clean.substring("simple/".length()).replaceAll("/+$", ""))
                    : ArtifactNameParser.parse(type, path);
                break;
            case "go":
                pkg = ParsedRequest.go(clean);
                break;
            case "php":
                pkg = clean.startsWith("p2/") && clean.endsWith(".json")
                    ? Optional.of(clean.substring(3, clean.length() - 5).replaceAll("~dev$", ""))
                    : ArtifactNameParser.parse(type, path);
                break;
            default:
                pkg = ArtifactNameParser.parse(type, path);
                break;
        }
        return new ParsedRequest(
            pkg.filter(name -> !name.isBlank()).orElse(null),
            version.isEmpty() ? null : version, kind
        );
    }

    /**
     * Path as a leaf slice of the family sees it.
     *
     * @param family Family
     * @param path Path
     * @return Path without the family's routing alias
     */
    private static String leafView(final String family, final String path) {
        String res = path;
        if ("pypi".equals(family) && path.startsWith("/simple/")) {
            res = path.substring("/simple".length());
        }
        return res;
    }

    /**
     * Maven {@code groupId:artifactId} from a file or metadata path.
     *
     * @param clean Path without leading slash
     * @param metadata Whether the path is a metadata listing
     * @return Coordinate
     */
    private static Optional<String> maven(final String clean, final boolean metadata) {
        final String[] segs = clean.split("/");
        final int artifact;
        if (metadata && segs.length >= 3 && segs[segs.length - 1].startsWith("maven-metadata")) {
            artifact = segs.length - 2;
        } else if (!metadata && segs.length >= 4) {
            artifact = segs.length - 3;
        } else {
            artifact = -1;
        }
        final Optional<String> res;
        if (artifact <= 0) {
            res = Optional.empty();
        } else {
            res = Optional.of(
                String.join(".", Arrays.copyOfRange(segs, 0, artifact)) + ":" + segs[artifact]
            );
        }
        return res;
    }

    /**
     * Go module path (case-decoded) from a {@code @v}/{@code @latest} path.
     *
     * @param clean Path without leading slash
     * @return Module
     */
    private static Optional<String> go(final String clean) {
        int end = clean.indexOf("/@v/");
        if (end < 0) {
            end = clean.indexOf("/@latest");
        }
        final Optional<String> res;
        if (end <= 0) {
            res = Optional.empty();
        } else {
            final String escaped = clean.substring(0, end);
            final StringBuilder out = new StringBuilder(escaped.length());
            int idx = 0;
            while (idx < escaped.length()) {
                final char chr = escaped.charAt(idx);
                if (chr == '!' && idx + 1 < escaped.length()) {
                    out.append(Character.toUpperCase(escaped.charAt(idx + 1)));
                    idx = idx + 2;
                } else {
                    out.append(chr);
                    idx = idx + 1;
                }
            }
            res = Optional.of(out.toString());
        }
        return res;
    }
}
