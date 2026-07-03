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
package com.auto1.pantera.cooldown.metadata;

import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version comparators for different package formats.
 * Used to sort versions and determine the "latest" unblocked version.
 *
 * @since 1.0
 */
public final class VersionComparators {

    /**
     * Pattern for semantic versioning: major.minor.patch[-prerelease][+build].
     * Prerelease and build metadata can contain alphanumeric, hyphens, and dots.
     */
    private static final Pattern SEMVER = Pattern.compile(
        "^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([\\w.\\-]+))?(?:\\+([\\w.\\-]+))?$"
    );

    /**
     * Private constructor.
     */
    private VersionComparators() {
    }

    /**
     * Semantic version comparator (NPM, Composer style).
     * Compares major.minor.patch numerically, then prerelease lexically.
     * Versions without prerelease are considered newer than those with prerelease.
     *
     * @return Comparator that orders versions from oldest to newest
     */
    public static Comparator<String> semver() {
        return (v1, v2) -> {
            final Matcher m1 = SEMVER.matcher(v1);
            final Matcher m2 = SEMVER.matcher(v2);
            final boolean match1 = m1.matches();
            final boolean match2 = m2.matches();
            // Handle non-semver versions consistently to maintain transitivity:
            // - semver versions sort before non-semver versions
            // - non-semver versions sort lexically among themselves
            if (!match1 && !match2) {
                // Both non-semver: lexical comparison
                return v1.compareTo(v2);
            }
            if (!match1) {
                // v1 is non-semver, v2 is semver: v1 sorts after v2
                return 1;
            }
            if (!match2) {
                // v1 is semver, v2 is non-semver: v1 sorts before v2
                return -1;
            }
            // Both semver: compare numerically
            // Compare major
            int cmp = compareNumeric(m1.group(1), m2.group(1));
            if (cmp != 0) {
                return cmp;
            }
            // Compare minor
            cmp = compareNumeric(m1.group(2), m2.group(2));
            if (cmp != 0) {
                return cmp;
            }
            // Compare patch
            cmp = compareNumeric(m1.group(3), m2.group(3));
            if (cmp != 0) {
                return cmp;
            }
            // Compare prerelease: no prerelease > has prerelease
            final String pre1 = m1.group(4);
            final String pre2 = m2.group(4);
            if (pre1 == null && pre2 == null) {
                return 0;
            }
            if (pre1 == null) {
                return 1; // v1 is release, v2 is prerelease → v1 > v2
            }
            if (pre2 == null) {
                return -1; // v1 is prerelease, v2 is release → v1 < v2
            }
            return pre1.compareTo(pre2);
        };
    }

    /**
     * Maven version comparator.
     * Handles Maven's version ordering rules (numeric segments, qualifiers).
     *
     * @return Comparator that orders versions from oldest to newest
     */
    public static Comparator<String> maven() {
        // Simplified Maven comparator - handles common cases
        return (v1, v2) -> {
            final String[] parts1 = v1.split("[.-]");
            final String[] parts2 = v2.split("[.-]");
            final int len = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < len; i++) {
                final String p1 = i < parts1.length ? parts1[i] : "0";
                final String p2 = i < parts2.length ? parts2[i] : "0";
                // Try numeric comparison first
                try {
                    final long n1 = Long.parseLong(p1);
                    final long n2 = Long.parseLong(p2);
                    if (n1 != n2) {
                        return Long.compare(n1, n2);
                    }
                } catch (NumberFormatException e) {
                    // Fall back to string comparison for qualifiers
                    final int cmp = compareQualifier(p1, p2);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
            }
            return 0;
        };
    }

    /**
     * Simple lexical comparator.
     * Useful for Go modules and other formats with simple version strings.
     *
     * @return Comparator that orders versions lexically
     */
    public static Comparator<String> lexical() {
        return String::compareTo;
    }

    /**
     * Compare numeric strings, treating null/empty as 0.
     * Uses Long to handle version numbers that exceed Integer.MAX_VALUE.
     */
    private static int compareNumeric(final String s1, final String s2) {
        final long n1 = s1 == null || s1.isEmpty() ? 0L : Long.parseLong(s1);
        final long n2 = s2 == null || s2.isEmpty() ? 0L : Long.parseLong(s2);
        return Long.compare(n1, n2);
    }

    /**
     * Compare Maven qualifiers.
     * Order: alpha < beta < milestone < rc < snapshot < "" (release) < sp
     */
    private static int compareQualifier(final String q1, final String q2) {
        return Integer.compare(qualifierRank(q1), qualifierRank(q2));
    }

    /**
     * Get rank for Maven qualifier.
     */
    private static int qualifierRank(final String qualifier) {
        final String lower = qualifier.toLowerCase(Locale.ROOT);
        if (lower.startsWith("alpha") || "a".equals(lower)) {
            return 1;
        }
        if (lower.startsWith("beta") || "b".equals(lower)) {
            return 2;
        }
        if (lower.startsWith("milestone") || "m".equals(lower)) {
            return 3;
        }
        if (lower.startsWith("rc") || lower.startsWith("cr")) {
            return 4;
        }
        if ("snapshot".equals(lower)) {
            return 5;
        }
        if (lower.isEmpty() || "final".equals(lower) || "ga".equals(lower) || "release".equals(lower)) {
            return 6;
        }
        if (lower.startsWith("sp")) {
            return 7;
        }
        // Unknown qualifier - treat as release equivalent
        return 6;
    }
}
