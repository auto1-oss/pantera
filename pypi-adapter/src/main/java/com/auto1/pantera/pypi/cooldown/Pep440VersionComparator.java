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
package com.auto1.pantera.pypi.cooldown;

import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEP 440 version comparator for Python package versions.
 *
 * <p>Implements the ordering rules defined in
 * <a href="https://peps.python.org/pep-0440/">PEP 440</a> — the canonical
 * specification used by pip, setuptools, and every PyPI tool. The
 * existing {@link com.auto1.pantera.cooldown.metadata.VersionComparators}
 * semver implementation is NOT correct for Python because PEP 440 has
 * concepts that semver does not (post-releases, dev-releases, explicit
 * "release" segment of variable length, optional epoch), and rules that
 * disagree with semver (post-releases &gt; release; rc is treated as a
 * distinct pre-release kind, etc.).</p>
 *
 * <p>Parsed components, in precedence order (left = most significant):</p>
 * <ol>
 *   <li><b>Epoch</b> — optional {@code N!} prefix (default {@code 0}).</li>
 *   <li><b>Release segment</b> — {@code N(.N)*} numeric tuple.</li>
 *   <li><b>Pre-release</b> — optional {@code a|b|rc|alpha|beta|c|pre|preview}
 *       with optional number. A release with no pre-release sorts
 *       <em>after</em> any pre-release of the same release segment.</li>
 *   <li><b>Post-release</b> — optional {@code .post}{@code N} (or
 *       {@code -N} / {@code postN}). A release with a post-release sorts
 *       <em>after</em> the same release without one.</li>
 *   <li><b>Dev-release</b> — optional {@code .dev}{@code N}. A release
 *       with a dev-release sorts <em>before</em> the same release
 *       without one.</li>
 * </ol>
 *
 * <p>Example orderings (smallest to largest):</p>
 * <pre>
 * 1.0.0.dev1  &lt;  1.0.0a1  &lt;  1.0.0b1  &lt;  1.0.0rc1
 *            &lt;  1.0.0     &lt;  1.0.0.post1  &lt;  1.0.1
 * 2.0.0b1     &lt;  2.0.0    &lt;  2.0.0.post1
 * 1!1.0.0     &gt;  99.0.0                      (explicit epoch wins)
 * </pre>
 *
 * <p>Unparseable versions are treated as older than any parseable
 * version, then compared lexically among themselves. This keeps the
 * comparator total so collection sorts remain stable.</p>
 *
 * @since 2.2.0
 */
public final class Pep440VersionComparator implements Comparator<String> {

    /**
     * Relaxed PEP 440 pattern. Public form without the epoch-optional
     * group noise so we can enforce the anchor + extract each piece in
     * named groups. Source: simplified from
     * {@code packaging.version.VERSION_PATTERN}.
     *
     * Groups:
     * - epoch    : optional integer before '!'
     * - release  : {@code N(.N)*}
     * - pre_lbl  : 'a', 'b', 'c', 'rc', 'alpha', 'beta', 'pre', 'preview'
     * - pre_num  : integer following pre_lbl
     * - post     : post-release number (matches both '.postN' / '-N' / 'postN' forms)
     * - dev      : dev-release number
     */
    private static final Pattern PATTERN = Pattern.compile(
        "^\\s*v?"
            + "(?:(?<epoch>\\d+)!)?"
            + "(?<release>\\d+(?:\\.\\d+)*)"
            + "(?:[-._]?(?<prelbl>a|b|c|rc|alpha|beta|pre|preview)[-._]?(?<prenum>\\d+)?)?"
            + "(?:(?:[-._]?post[-._]?(?<post1>\\d+)?)|(?:-(?<post2>\\d+)))?"
            + "(?:[-._]?dev[-._]?(?<dev>\\d+)?)?"
            + "(?:\\+[a-z0-9]+(?:[.-][a-z0-9]+)*)?"
            + "\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public int compare(final String v1, final String v2) {
        final Parsed p1 = parse(v1);
        final Parsed p2 = parse(v2);
        if (p1 == null && p2 == null) {
            return safe(v1).compareTo(safe(v2));
        }
        if (p1 == null) {
            return -1;
        }
        if (p2 == null) {
            return 1;
        }
        return p1.compareTo(p2);
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }

    /**
     * Parse a PEP 440 version string into its components. Returns
     * {@code null} for unparseable input (caller handles the
     * "unparseable &lt; parseable" ordering).
     *
     * @param version Candidate version string
     * @return Parsed components or {@code null}
     */
    static Parsed parse(final String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        final Matcher matcher = PATTERN.matcher(version);
        if (!matcher.matches()) {
            return null;
        }
        final int epoch = toInt(matcher.group("epoch"), 0);
        final int[] release = toRelease(matcher.group("release"));
        final PreRelease pre = toPreRelease(
            matcher.group("prelbl"), matcher.group("prenum")
        );
        final Integer post = parsePost(matcher.group("post1"), matcher.group("post2"));
        final Integer dev = parseDev(matcher);
        return new Parsed(epoch, release, pre, post, dev);
    }

    private static int toInt(final String value, final int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static int[] toRelease(final String releaseGroup) {
        final String[] parts = releaseGroup.split("\\.");
        final int[] out = new int[parts.length];
        for (int idx = 0; idx < parts.length; idx++) {
            out[idx] = toInt(parts[idx], 0);
        }
        return out;
    }

    private static PreRelease toPreRelease(final String label, final String number) {
        if (label == null) {
            return null;
        }
        final String lower = label.toLowerCase(Locale.ROOT);
        final int rank;
        // Normalise PEP 440 pre-release labels to ordering ranks.
        if ("a".equals(lower) || "alpha".equals(lower)) {
            rank = 0;
        } else if ("b".equals(lower) || "beta".equals(lower)) {
            rank = 1;
        } else if ("c".equals(lower) || "rc".equals(lower)
            || "pre".equals(lower) || "preview".equals(lower)) {
            rank = 2;
        } else {
            rank = 3;
        }
        return new PreRelease(rank, toInt(number, 0));
    }

    /**
     * The post-release number may appear in either regex slot.
     * {@code null} means "no post-release"; {@code 0} means
     * {@code .post} with no explicit number, which PEP 440
     * normalises to 0.
     */
    private static Integer parsePost(final String slot1, final String slot2) {
        if (slot1 != null) {
            return toInt(slot1, 0);
        }
        if (slot2 != null) {
            return toInt(slot2, 0);
        }
        return null;
    }

    private static Integer parseDev(final Matcher matcher) {
        // The outer dev group may match even if inner number is absent,
        // which PEP 440 normalises to 0. We distinguish "no dev" from
        // "dev with no number" by checking whether the source text
        // contained 'dev'.
        final String source = matcher.group(0);
        if (source == null) {
            return null;
        }
        final int devIdx = source.toLowerCase(Locale.ROOT).indexOf("dev");
        if (devIdx < 0) {
            return null;
        }
        final String devNum = matcher.group("dev");
        return toInt(devNum, 0);
    }

    /**
     * Parsed PEP 440 components. Exposed package-private for tests.
     */
    static final class Parsed implements Comparable<Parsed> {
        private final int epoch;
        private final int[] release;
        private final PreRelease pre;
        private final Integer post;
        private final Integer dev;

        Parsed(
            final int epoch,
            final int[] release,
            final PreRelease pre,
            final Integer post,
            final Integer dev
        ) {
            this.epoch = epoch;
            this.release = release == null ? new int[0] : release.clone();
            this.pre = pre;
            this.post = post;
            this.dev = dev;
        }

        @Override
        public int compareTo(final Parsed other) {
            // Implementation mirrors packaging.version._cmpkey in CPython's
            // `packaging` library: the PEP 440 ordering key is
            //   (epoch, release, preKey, postKey, devKey, localKey).
            // Each "Key" uses ±∞ sentinels to model "no pre", "no post",
            // "no dev" — so e.g. a bare dev-release sorts BEFORE any
            // pre-release of the same base version, and a bare release
            // sorts AFTER any pre-release.
            int cmp = Integer.compare(this.epoch, other.epoch);
            if (cmp != 0) {
                return cmp;
            }
            cmp = compareReleases(this.release, other.release);
            if (cmp != 0) {
                return cmp;
            }
            cmp = comparePreKey(this, other);
            if (cmp != 0) {
                return cmp;
            }
            cmp = comparePostKey(this.post, other.post);
            if (cmp != 0) {
                return cmp;
            }
            return compareDevKey(this.dev, other.dev);
        }

        private static int compareReleases(final int[] lhs, final int... rhs) {
            final int len = Math.max(lhs.length, rhs.length);
            for (int idx = 0; idx < len; idx++) {
                final int left = idx < lhs.length ? lhs[idx] : 0;
                final int right = idx < rhs.length ? rhs[idx] : 0;
                final int cmp = Integer.compare(left, right);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        /**
         * PEP 440 pre-release key:
         * <ul>
         *   <li>{@code pre==null &amp;&amp; dev!=null} → {@code -∞}
         *       (bare dev sorts before pre-releases)</li>
         *   <li>{@code pre==null &amp;&amp; dev==null} → {@code +∞}
         *       (bare release sorts after any pre-release)</li>
         *   <li>otherwise the {@code (rank, number)} pair</li>
         * </ul>
         */
        private static int comparePreKey(final Parsed lhs, final Parsed rhs) {
            final int leftInf = preInfinity(lhs);
            final int rightInf = preInfinity(rhs);
            if (leftInf != 0 || rightInf != 0) {
                return Integer.compare(leftInf, rightInf);
            }
            final int rankCmp = Integer.compare(lhs.pre.rank(), rhs.pre.rank());
            if (rankCmp != 0) {
                return rankCmp;
            }
            return Integer.compare(lhs.pre.number(), rhs.pre.number());
        }

        /**
         * Returns {@code -1} for bare-dev ({@code -∞}), {@code +1} for
         * bare-release ({@code +∞}), and {@code 0} when a concrete
         * pre-release is present (caller handles tuple compare).
         */
        private static int preInfinity(final Parsed parsed) {
            if (parsed.pre != null) {
                return 0;
            }
            if (parsed.dev != null) {
                return -1;
            }
            return 1;
        }

        /**
         * PEP 440 post-release key: {@code null} → {@code -∞},
         * else the number.
         */
        private static int comparePostKey(final Integer lhs, final Integer rhs) {
            if (lhs == null && rhs == null) {
                return 0;
            }
            if (lhs == null) {
                return -1;
            }
            if (rhs == null) {
                return 1;
            }
            return Integer.compare(lhs, rhs);
        }

        /**
         * PEP 440 dev-release key: {@code null} → {@code +∞} (no dev
         * sorts AFTER dev with same pre/post tuple), else the number.
         */
        private static int compareDevKey(final Integer lhs, final Integer rhs) {
            if (lhs == null && rhs == null) {
                return 0;
            }
            if (lhs == null) {
                return 1;
            }
            if (rhs == null) {
                return -1;
            }
            return Integer.compare(lhs, rhs);
        }
    }

    /**
     * Pre-release normalisation record. Package-private for tests.
     */
    record PreRelease(int rank, int number) {
    }
}
