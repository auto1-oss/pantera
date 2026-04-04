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
package com.auto1.pantera.scheduling;

/**
 * Infers a version string from a dotted file-repository artifact name.
 *
 * <p>File-repo artifact names are storage paths with {@code /} replaced by
 * {@code .}, e.g.
 * {@code wkda.services.my-svc.1.5.0-SNAPSHOT.my-svc-1.5.0-20191106.pom}.
 * The detector walks dot-split tokens left to right and collects the first
 * contiguous run of tokens that each start with a digit (or {@code v} +
 * digit). If the preceding token ends with {@code -{digits}}, those digits
 * are prepended as the true version start (e.g. {@code nginx-1} + {@code 24}
 * + {@code 0} becomes {@code 1.24.0}).</p>
 *
 * <p>This class is the single source of truth for version detection from
 * dotted names. {@link RepositoryEvents} delegates here for live uploads,
 * and the backfill {@code VersionRepairRunner} mirrors this logic.</p>
 *
 * @since 2.1.0
 */
public final class FileVersionDetector {

    /**
     * Fallback when no version can be inferred.
     */
    public static final String UNKNOWN = "UNKNOWN";

    private FileVersionDetector() {
    }

    /**
     * Detect version from a dotted artifact name.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code a.b.c.1.5.0-SNAPSHOT.artifact-1.5.pom} &rarr; {@code 1.5.0-SNAPSHOT}</li>
     *   <li>{@code elinks-current-0.11.tar.gz} &rarr; {@code 0.11}</li>
     *   <li>{@code nginx-1.24.0.tar.gz} &rarr; {@code 1.24.0}</li>
     *   <li>{@code reports.2024.q1.pdf} &rarr; {@code 2024}</li>
     *   <li>{@code v1.0.0.app.tar.gz} &rarr; {@code v1.0.0}</li>
     *   <li>{@code config.application.yml} &rarr; {@code UNKNOWN}</li>
     * </ul>
     *
     * @param name Dotted artifact name (slashes already replaced by dots)
     * @return Detected version or {@value #UNKNOWN}
     */
    public static String detect(final String name) {
        if (name == null || name.isEmpty()) {
            return UNKNOWN;
        }
        final String[] tokens = name.split("\\.");
        int start = -1;
        int end = -1;
        for (int i = 0; i < tokens.length; i++) {
            final String tok = tokens[i];
            if (!tok.isEmpty() && isVersionToken(tok)) {
                if (start == -1) {
                    start = i;
                }
                end = i;
            } else if (start != -1) {
                break;
            }
        }
        if (start == -1) {
            return UNKNOWN;
        }
        final StringBuilder sb = new StringBuilder();
        if (start > 0) {
            final String prev = tokens[start - 1];
            final int lastHyphen = prev.lastIndexOf('-');
            if (lastHyphen >= 0 && lastHyphen < prev.length() - 1) {
                final String tail = prev.substring(lastHyphen + 1);
                if (isVersionToken(tail)) {
                    sb.append(tail).append('.');
                }
            }
        }
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append('.');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    /**
     * Check whether a dot-split token looks like part of a version:
     * starts with a digit, or starts with {@code v}/{@code V} followed
     * by a digit.
     *
     * @param token Token to test
     * @return True if version-like
     */
    static boolean isVersionToken(final String token) {
        if (token.isEmpty()) {
            return false;
        }
        final char first = token.charAt(0);
        if (Character.isDigit(first)) {
            return true;
        }
        return first == 'v' && token.length() > 1
            && Character.isDigit(token.charAt(1));
    }
}
