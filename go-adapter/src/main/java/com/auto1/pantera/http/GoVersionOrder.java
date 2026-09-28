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
package com.auto1.pantera.http;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go module version ordering, as the {@code go} command applies it.
 *
 * <p>{@link #compare(String, String)} is semantic-version precedence
 * (numeric major/minor/patch, where {@code v1.2} is shorthand for
 * {@code v1.2.0} as in golang.org/x/mod/semver, numeric prerelease identifiers compared
 * numerically, build metadata such as {@code +incompatible} ignored).
 * Strings that are not valid versions sort below every valid one.</p>
 *
 * <p>{@link #latest(Collection)} picks what {@code @latest} must answer:
 * the highest release; if there is none, the highest prerelease; if there
 * is none either, the highest pseudo-version.</p>
 *
 * @since 2.2.9
 */
final class GoVersionOrder implements Comparator<String> {

    /**
     * Semantic version with optional {@code v}, prerelease and build.
     */
    private static final Pattern SEMVER = Pattern.compile(
        "^v?(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?(?:\\.(0|[1-9]\\d*))?"
            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    );

    /**
     * Pseudo-version (golang.org/x/mod/module pseudo.go).
     */
    private static final Pattern PSEUDO = Pattern.compile(
        "^v[0-9]+\\.(0\\.0-|\\d+\\.\\d+-([^+]*\\.)?0\\.)\\d{14}-[A-Za-z0-9]+"
            + "(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$"
    );

    /**
     * Pick the version {@code @latest} must report.
     * @param versions Candidate versions
     * @return Latest version, empty when no candidate is a valid version
     */
    Optional<String> latest(final Collection<String> versions) {
        Optional<String> best = Optional.empty();
        for (final String candidate : versions) {
            if (SEMVER.matcher(candidate).matches()
                && (best.isEmpty() || this.preferred(candidate, best.get()))) {
                best = Optional.of(candidate);
            }
        }
        return best;
    }

    @Override
    public int compare(final String left, final String right) {
        final Matcher lhs = SEMVER.matcher(left);
        final Matcher rhs = SEMVER.matcher(right);
        final boolean lvalid = lhs.matches();
        final boolean rvalid = rhs.matches();
        final int result;
        if (lvalid && rvalid) {
            result = GoVersionOrder.compareValid(lhs, rhs);
        } else if (lvalid || rvalid) {
            result = lvalid ? 1 : -1;
        } else {
            result = left.compareTo(right);
        }
        return result;
    }

    /**
     * Whether {@code candidate} beats {@code current} for {@code @latest}.
     * @param candidate Candidate version
     * @param current Current best version
     * @return True if candidate is preferred
     */
    private boolean preferred(final String candidate, final String current) {
        final int rank = Integer.compare(
            GoVersionOrder.rank(candidate), GoVersionOrder.rank(current)
        );
        return rank > 0 || rank == 0 && this.compare(candidate, current) > 0;
    }

    /**
     * Rank of a valid version: release 2, prerelease 1, pseudo-version 0.
     * @param version Version
     * @return Rank
     */
    private static int rank(final String version) {
        final int rank;
        if (PSEUDO.matcher(version).matches()) {
            rank = 0;
        } else {
            final Matcher matcher = SEMVER.matcher(version);
            if (matcher.matches() && matcher.group(4) == null) {
                rank = 2;
            } else {
                rank = 1;
            }
        }
        return rank;
    }

    /**
     * Compare two matched versions.
     * @param lhs Left match
     * @param rhs Right match
     * @return Comparison result
     */
    private static int compareValid(final Matcher lhs, final Matcher rhs) {
        int result = 0;
        for (int group = 1; group <= 3 && result == 0; group += 1) {
            result = GoVersionOrder.compareNumbers(lhs.group(group), rhs.group(group));
        }
        if (result == 0) {
            result = GoVersionOrder.comparePrerelease(lhs.group(4), rhs.group(4));
        }
        return result;
    }

    /**
     * Compare prerelease strings: none beats any; else identifier by identifier.
     * @param left Left prerelease or null
     * @param right Right prerelease or null
     * @return Comparison result
     */
    private static int comparePrerelease(final String left, final String right) {
        final int result;
        if (left == null || right == null) {
            result = Boolean.compare(left == null, right == null);
        } else {
            final String[] lids = left.split("\\.");
            final String[] rids = right.split("\\.");
            int cmp = 0;
            for (int idx = 0; idx < Math.min(lids.length, rids.length) && cmp == 0; idx += 1) {
                cmp = GoVersionOrder.compareIdentifier(lids[idx], rids[idx]);
            }
            if (cmp == 0) {
                cmp = Integer.compare(lids.length, rids.length);
            }
            result = cmp;
        }
        return result;
    }

    /**
     * Compare prerelease identifiers: numeric ones numerically and below
     * alphanumeric ones, alphanumeric ones in ASCII order.
     * @param left Left identifier
     * @param right Right identifier
     * @return Comparison result
     */
    private static int compareIdentifier(final String left, final String right) {
        final boolean lnum = GoVersionOrder.numeric(left);
        final boolean rnum = GoVersionOrder.numeric(right);
        final int result;
        if (lnum && rnum) {
            result = GoVersionOrder.compareNumbers(left, right);
        } else if (lnum || rnum) {
            result = lnum ? -1 : 1;
        } else {
            result = left.compareTo(right);
        }
        return result;
    }

    /**
     * Compare decimal strings of any length without overflow; a missing
     * (null) component counts as zero.
     * @param left Left number
     * @param right Right number
     * @return Comparison result
     */
    private static int compareNumbers(final String left, final String right) {
        final String lnum = GoVersionOrder.stripZeros(Objects.requireNonNullElse(left, "0"));
        final String rnum = GoVersionOrder.stripZeros(Objects.requireNonNullElse(right, "0"));
        final int bylength = Integer.compare(lnum.length(), rnum.length());
        if (bylength != 0) {
            return bylength;
        }
        return lnum.compareTo(rnum);
    }

    /**
     * Drop leading zeros.
     * @param number Decimal string
     * @return Decimal string without leading zeros
     */
    private static String stripZeros(final String number) {
        int start = 0;
        while (start < number.length() - 1 && number.charAt(start) == '0') {
            start += 1;
        }
        return number.substring(start);
    }

    /**
     * Whether the identifier is all digits.
     * @param identifier Identifier
     * @return True if numeric
     */
    private static boolean numeric(final String identifier) {
        return !identifier.isEmpty() && identifier.chars().allMatch(Character::isDigit);
    }
}
