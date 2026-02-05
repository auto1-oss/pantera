/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.goproxy.metadata;

import com.artipie.cache.MetadataMerger;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Go metadata merger for group repositories.
 * Merges @v/list version lists from multiple Go module proxies.
 *
 * <p>Merge rules:
 * <ul>
 *   <li>Concatenates all versions</li>
 *   <li>Deduplicates by version string</li>
 *   <li>Sorts using semantic versioning (v1.0.0 &lt; v1.1.0 &lt; v1.2.0)</li>
 * </ul>
 *
 * @since 1.18.0
 */
public final class GoMetadataMerger implements MetadataMerger {

    /**
     * Semver pattern for parsing versions.
     * Matches: vMAJOR.MINOR.PATCH[-PRERELEASE]
     */
    private static final Pattern SEMVER = Pattern.compile(
        "v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([\\w.]+))?"
    );

    @Override
    public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
        if (responses.isEmpty()) {
            return new byte[0];
        }
        final TreeSet<String> versions = new TreeSet<>(new SemverComparator());
        for (final Map.Entry<String, byte[]> entry : responses.entrySet()) {
            final String content = new String(entry.getValue(), StandardCharsets.UTF_8);
            content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .forEach(versions::add);
        }
        return versions.stream()
            .collect(Collectors.joining("\n"))
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Comparator for semantic version strings.
     */
    private static final class SemverComparator implements Comparator<String> {

        @Override
        public int compare(final String ver1, final String ver2) {
            final Matcher match1 = SEMVER.matcher(ver1);
            final Matcher match2 = SEMVER.matcher(ver2);
            if (!match1.matches() || !match2.matches()) {
                // Fallback to string comparison for non-semver strings
                return ver1.compareTo(ver2);
            }
            // Compare major
            int cmp = Integer.compare(
                Integer.parseInt(match1.group(1)),
                Integer.parseInt(match2.group(1))
            );
            if (cmp != 0) {
                return cmp;
            }
            // Compare minor
            cmp = Integer.compare(
                Integer.parseInt(match1.group(2)),
                Integer.parseInt(match2.group(2))
            );
            if (cmp != 0) {
                return cmp;
            }
            // Compare patch
            cmp = Integer.compare(
                Integer.parseInt(match1.group(3)),
                Integer.parseInt(match2.group(3))
            );
            if (cmp != 0) {
                return cmp;
            }
            // Compare prerelease (null means release, which comes after prerelease)
            final String pre1 = match1.group(4);
            final String pre2 = match2.group(4);
            if (pre1 == null && pre2 == null) {
                return 0;
            }
            if (pre1 == null) {
                return 1; // Release comes after prerelease
            }
            if (pre2 == null) {
                return -1; // Prerelease comes before release
            }
            return pre1.compareTo(pre2);
        }
    }
}
