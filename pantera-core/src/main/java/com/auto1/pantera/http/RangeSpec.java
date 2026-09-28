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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Range header parser and validator (RFC 9110 section 14.1.2).
 * Supports a single byte range in one of the forms:
 * <ul>
 *   <li>{@code bytes=start-end} (end is clamped to the last byte),</li>
 *   <li>{@code bytes=start-} (to the end of the representation),</li>
 *   <li>{@code bytes=-N} (suffix range: the last N bytes).</li>
 * </ul>
 *
 * @since 1.0
 */
public final class RangeSpec {

    /**
     * Pattern for parsing Range header: "bytes=start-end" or "bytes=-suffix".
     */
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /**
     * Start byte (inclusive); ignored for a suffix range.
     */
    private final long start;

    /**
     * End byte (inclusive), -1 means to end of file.
     */
    private final long end;

    /**
     * Suffix length for {@code bytes=-N}, -1 when this is not a suffix range.
     */
    private final long suffix;

    /**
     * Constructor.
     * @param start Start byte (inclusive)
     * @param end End byte (inclusive), -1 for end of file
     */
    public RangeSpec(final long start, final long end) {
        this(start, end, -1L);
    }

    /**
     * Primary constructor.
     * @param start Start byte (inclusive)
     * @param end End byte (inclusive), -1 for end of file
     * @param suffix Suffix length, -1 when not a suffix range
     */
    private RangeSpec(final long start, final long end, final long suffix) {
        this.start = start;
        this.end = end;
        this.suffix = suffix;
    }

    /**
     * Parse Range header.
     * @param header Range header value (e.g., "bytes=0-1023" or "bytes=-500")
     * @return RangeSpec if syntactically valid, empty otherwise
     */
    public static Optional<RangeSpec> parse(final String header) {
        if (header == null || header.isEmpty()) {
            return Optional.empty();
        }
        final Matcher matcher = RANGE_PATTERN.matcher(header.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final String first = matcher.group(1);
        final String last = matcher.group(2);
        Optional<RangeSpec> res = Optional.empty();
        try {
            if (first.isEmpty()) {
                if (!last.isEmpty()) {
                    res = Optional.of(new RangeSpec(0L, -1L, Long.parseLong(last)));
                }
            } else {
                final long from = Long.parseLong(first);
                final long upto;
                if (last.isEmpty()) {
                    upto = -1L;
                } else {
                    upto = Long.parseLong(last);
                }
                if (upto == -1L || upto >= from) {
                    res = Optional.of(new RangeSpec(from, upto));
                }
            }
        } catch (final NumberFormatException ex) {
            res = Optional.empty();
        }
        return res;
    }

    /**
     * Check if range is satisfiable for given file size.
     * A range whose first byte is inside the representation is satisfiable;
     * its end is clamped to the last byte. A suffix range is satisfiable
     * when its length is positive and the representation is not empty.
     * @param fileSize Total file size in bytes
     * @return True if satisfiable
     */
    public boolean isValid(final long fileSize) {
        final boolean valid;
        if (this.suffix >= 0) {
            valid = this.suffix > 0 && fileSize > 0;
        } else {
            valid = this.start < fileSize;
        }
        return valid;
    }

    /**
     * Get start byte position for given file size.
     * @param fileSize Total file size
     * @return Start byte (inclusive)
     */
    public long start(final long fileSize) {
        final long res;
        if (this.suffix >= 0) {
            res = Math.max(0L, fileSize - this.suffix);
        } else {
            res = this.start;
        }
        return res;
    }

    /**
     * Get end byte position for given file size, clamped to the last byte.
     * @param fileSize Total file size
     * @return End byte (inclusive)
     */
    public long end(final long fileSize) {
        final long res;
        if (this.suffix >= 0 || this.end == -1 || this.end >= fileSize) {
            res = fileSize - 1;
        } else {
            res = this.end;
        }
        return res;
    }

    /**
     * Get length of range for given file size.
     * @param fileSize Total file size
     * @return Number of bytes in range
     */
    public long length(final long fileSize) {
        return this.end(fileSize) - this.start(fileSize) + 1;
    }

    /**
     * Format as Content-Range header value.
     * @param fileSize Total file size
     * @return Content-Range header value (e.g., "bytes 0-1023/2048")
     */
    public String toContentRange(final long fileSize) {
        return String.format(
            "bytes %d-%d/%d",
            this.start(fileSize),
            this.end(fileSize),
            fileSize
        );
    }

    @Override
    public String toString() {
        final String res;
        if (this.suffix >= 0) {
            res = String.format("bytes=-%d", this.suffix);
        } else {
            res = String.format("bytes=%d-%s", this.start, this.end == -1 ? "" : this.end);
        }
        return res;
    }
}
