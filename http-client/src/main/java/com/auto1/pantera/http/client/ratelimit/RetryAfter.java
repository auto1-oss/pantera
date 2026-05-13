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
package com.auto1.pantera.http.client.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parses HTTP {@code Retry-After} headers per RFC 7231 §7.1.3. Two
 * forms are accepted:
 *
 * <ul>
 *   <li><b>delta-seconds</b>: a non-negative integer (e.g. {@code "120"}).
 *       Parsed as seconds-from-now.</li>
 *   <li><b>HTTP-date</b>: an RFC 7231 IMF-fixdate string (e.g.
 *       {@code "Wed, 21 Oct 2026 07:28:00 GMT"}). Parsed and subtracted
 *       from the supplied clock; negative deltas (dates in the past)
 *       collapse to {@link Duration#ZERO}.</li>
 * </ul>
 *
 * <p>Malformed values, blanks, and nulls return {@link Duration#ZERO}.
 * The {@code recordResponse} call sites treat a zero Retry-After on a
 * 429 as "use the default gate duration"; a zero on a 503 means "503
 * is not a gating event."
 *
 * @since 2.2.0
 */
public final class RetryAfter {

    private RetryAfter() {
        // utility
    }

    /**
     * Parse {@code value} relative to {@code clock.instant()}.
     *
     * @param value Raw Retry-After header value (nullable).
     * @param clock Reference clock for HTTP-date subtraction.
     * @return Non-negative duration; {@link Duration#ZERO} when the
     *     input is absent, malformed, or names a past instant.
     */
    public static Duration parse(final String value, final Clock clock) {
        if (value == null) {
            return Duration.ZERO;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Duration.ZERO;
        }
        // Delta-seconds form: pure non-negative integer.
        if (isAllDigits(trimmed)) {
            try {
                final long seconds = Long.parseLong(trimmed);
                if (seconds < 0L) {
                    return Duration.ZERO;
                }
                return Duration.ofSeconds(seconds);
            } catch (final NumberFormatException ex) {
                return Duration.ZERO;
            }
        }
        // HTTP-date form.
        try {
            final Instant target = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed));
            final Duration delta = Duration.between(clock.instant(), target);
            return delta.isNegative() ? Duration.ZERO : delta;
        } catch (final DateTimeParseException ex) {
            return Duration.ZERO;
        }
    }

    private static boolean isAllDigits(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }
}
