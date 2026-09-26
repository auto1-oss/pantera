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
package com.auto1.pantera.files;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * file-proxy cooldown 403: plain text (generic file clients — curl, wget,
 * build scripts — have no structured error format), with
 * {@code Retry-After} and the {@code X-Pantera-Cooldown} marker.
 *
 * @since 2.2.9
 */
public final class FileProxyCooldownResponseFactory implements CooldownResponseFactory {

    /**
     * Timestamp format of the unblock time.
     */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public Response forbidden(final CooldownBlock block) {
        final String until = ISO.format(block.blockedUntil().atOffset(ZoneOffset.UTC));
        final long retryAfter = Math.max(
            1L, Duration.between(Instant.now(), block.blockedUntil()).getSeconds()
        );
        return ResponseBuilder.forbidden()
            .header("Retry-After", String.valueOf(retryAfter))
            .header(CooldownResponseFactory.HEADER, "blocked")
            .textBody("file blocked by cooldown policy until " + until)
            .build();
    }

    @Override
    public String repoType() {
        return "file-proxy";
    }
}
