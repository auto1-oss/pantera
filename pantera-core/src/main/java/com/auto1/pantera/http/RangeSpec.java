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
 * HTTP Range header parser and validator.
 * Supports byte ranges in format: "bytes=start-end"
 * 
 * @since 1.0
 */
public final class RangeSpec {

    /**
     * Pattern for parsing Range header: "bytes=start-end"
     */
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    /**
     * Start byte (inclusive).
     */
    private final long start;

    /**
     * End byte (inclusive), -1 means to end of file.
     */
    private final long end;

    /**
     * Constructor.
     * @param start Start byte (inclusive)
     * @param end End byte (inclusive), -1 for end of file
     */
    public RangeSpec(final long start, final long end) {
        this.start = start;
        this.end = end;
    }

    /**
     * Parse Range header.
     * @param header Range header value (e.g., "bytes=0-1023")
     * @return RangeSpec if valid, empty otherwise
     */
    public static Optional<RangeSpec> parse(final String header) {
        if (header == null || header.isEmpty()) {
            return Optional.empty();
        }

        final Matcher matcher = RANGE_PATTERN.matcher(header.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            final long start = Long.parseLong(matcher.group(1));
            final long end;
            
            final String endStr = matcher.group(2);
            if (endStr == null || endStr.isEmpty()) {
                end = -1; // To end of file
            } else {
                end = Long.parseLong(endStr);
            }

            if (start < 0 || (end != -1 && end < start)) {
                return Optional.empty();
            }

            return Optional.of(new RangeSpec(start, end));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Check if range is valid for given file size.
     * @param fileSize Total file size in bytes
     * @return True if valid
     */
    public boolean isValid(final long fileSize) {
        return this.start < fileSize
            && !(this.end != -1 && this.end >= fileSize);
    }

    /**
     * Get start byte position.
     * @return Start byte (inclusive)
     */
    public long start() {
        return this.start;
    }

    /**
     * Get end byte position for given file size.
     * @param fileSize Total file size
     * @return End byte (inclusive)
     */
    public long end(final long fileSize) {
        return this.end == -1 ? fileSize - 1 : this.end;
    }

    /**
     * Get length of range for given file size.
     * @param fileSize Total file size
     * @return Number of bytes in range
     */
    public long length(final long fileSize) {
        return end(fileSize) - this.start + 1;
    }

    /**
     * Format as Content-Range header value.
     * @param fileSize Total file size
     * @return Content-Range header value (e.g., "bytes 0-1023/2048")
     */
    public String toContentRange(final long fileSize) {
        return String.format(
            "bytes %d-%d/%d",
            this.start,
            end(fileSize),
            fileSize
        );
    }

    @Override
    public String toString() {
        return String.format("bytes=%d-%s", this.start, this.end == -1 ? "" : this.end);
    }
}
