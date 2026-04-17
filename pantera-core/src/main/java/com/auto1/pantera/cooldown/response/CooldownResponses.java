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
package com.auto1.pantera.cooldown.response;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Helper to build cooldown HTTP responses.
 */
public final class CooldownResponses {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private CooldownResponses() {
    }

    public static Response forbidden(final CooldownBlock block) {
        // Calculate human-readable message
        // Note: blockedAt is when the block was created, blockedUntil is when it expires
        // The release date can be inferred: releaseDate = blockedUntil - cooldownPeriod
        final String message = String.format(
            "Security Policy: Package %s@%s is blocked due to %s. "
            + "Block created: %s. Available after: %s (remaining: %s). "
            + "This is a security measure to protect against supply chain attacks on fresh releases.",
            block.artifact(),
            block.version(),
            formatReason(block.reason()),
            ISO.format(block.blockedAt().atOffset(ZoneOffset.UTC)),
            ISO.format(block.blockedUntil().atOffset(ZoneOffset.UTC)),
            formatRemainingTime(block.blockedUntil())
        );
        
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("error", "COOLDOWN_BLOCKED")
            .add("message", message)
            .add("repository", block.repoName())
            .add("repositoryType", block.repoType())
            .add("artifact", block.artifact())
            .add("version", block.version())
            .add("reason", block.reason().name().toLowerCase(Locale.US))
            .add("reasonDescription", formatReason(block.reason()))
            .add("blockedAt", ISO.format(block.blockedAt().atOffset(ZoneOffset.UTC)))
            .add("blockedUntil", ISO.format(block.blockedUntil().atOffset(ZoneOffset.UTC)))
            .add("remainingTime", formatRemainingTime(block.blockedUntil()));
        final JsonArrayBuilder deps = Json.createArrayBuilder();
        block.dependencies().forEach(dep -> deps.add(
            Json.createObjectBuilder()
                .add("artifact", dep.artifact())
                .add("version", dep.version())
        ));
        json.add("dependencies", deps);
        return ResponseBuilder.forbidden()
            .jsonBody(json.build().toString())
            .build();
    }
    
    /**
     * Format reason enum to human-readable string.
     */
    private static String formatReason(final CooldownReason reason) {
        return switch (reason) {
            case FRESH_RELEASE -> "fresh release (package was published recently)";
            case NEWER_THAN_CACHE -> "newer than cached version";
            default -> reason.name().toLowerCase(Locale.US).replace('_', ' ');
        };
    }
    
    /**
     * Format remaining time until block expires.
     */
    private static String formatRemainingTime(final Instant until) {
        final Instant now = Instant.now();
        if (until.isBefore(now)) {
            return "expired";
        }
        final long hours = java.time.Duration.between(now, until).toHours();
        if (hours < 1) {
            final long minutes = java.time.Duration.between(now, until).toMinutes();
            return minutes + " minutes";
        }
        if (hours < 24) {
            return hours + " hours";
        }
        final long days = hours / 24;
        return days + " days";
    }
}
