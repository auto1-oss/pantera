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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.asto.Meta;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Last-Modified header.
 *
 * <p>The value is an HTTP date in the fixed-length format of RFC 9110
 * ({@code Sun, 06 Nov 1994 08:49:37 GMT}): the day of month always has two
 * digits. {@link DateTimeFormatter#RFC_1123_DATE_TIME} drops the leading zero
 * ({@code Tue, 3 Nov 2026 ...}), which clients that parse the date strictly
 * reject.</p>
 *
 * @since 2.2.12
 */
public final class LastModified extends Header {

    /**
     * Header name.
     */
    public static final String NAME = "Last-Modified";

    /**
     * HTTP date format, always in GMT.
     */
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
        .withZone(ZoneOffset.UTC);

    /**
     * Ctor.
     * @param modified Time of the last modification
     */
    public LastModified(final Instant modified) {
        this(LastModified.HTTP_DATE.format(modified));
    }

    /**
     * The storage time of an object: its update time, else its creation
     * time. Never later than {@code now}, as a {@code Last-Modified} in the
     * future is invalid, and {@code now} for a storage that records neither.
     * @param meta Object metadata
     * @param now Time the response is produced
     */
    public LastModified(final Meta meta, final Instant now) {
        this(LastModified.stored(meta, now));
    }

    /**
     * Ctor.
     * @param value Header value
     */
    public LastModified(final String value) {
        super(new Header(LastModified.NAME, value));
    }

    /**
     * Storage time of an object, capped at {@code now}.
     * @param meta Object metadata
     * @param now Time the response is produced
     * @return Storage time
     */
    private static Instant stored(final Meta meta, final Instant now) {
        final Optional<? extends Instant> updated = meta.read(Meta.OP_UPDATED_AT);
        final Instant time;
        if (updated.isPresent()) {
            time = updated.get();
        } else {
            final Optional<? extends Instant> created = meta.read(Meta.OP_CREATED_AT);
            time = created.isPresent() ? created.get() : now;
        }
        return time.isAfter(now) ? now : time;
    }
}
