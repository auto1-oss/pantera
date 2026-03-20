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
package com.auto1.pantera.npm.misc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Provides current date and time.
 * @since 0.7.6
 */
public final class DateTimeNowStr {

    /**
     * Current time.
     */
    private final String currtime;

    /**
     * Ctor.
     */
    public DateTimeNowStr() {
        this.currtime = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            .format(
                ZonedDateTime.ofInstant(
                    Instant.now(),
                    ZoneOffset.UTC
                )
            );
    }

    /**
     * Current date and time.
     * @return Current date and time.
     */
    public String value() {
        return this.currtime;
    }
}
