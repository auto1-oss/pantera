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
package com.auto1.pantera.webhook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Webhook configuration.
 *
 * @param url Webhook endpoint URL
 * @param secret HMAC-SHA256 signing secret (nullable)
 * @param events List of event types to deliver (e.g., "artifact.published", "artifact.deleted")
 * @param repos Optional list of repo names to filter (empty = all repos)
 * @since 1.20.13
 */
public record WebhookConfig(
    String url,
    String secret,
    List<String> events,
    List<String> repos
) {

    /**
     * Ctor.
     */
    public WebhookConfig {
        Objects.requireNonNull(url, "url");
        events = events != null ? List.copyOf(events) : List.of();
        repos = repos != null ? List.copyOf(repos) : List.of();
    }

    /**
     * Check if this webhook should receive the given event type.
     * @param eventType Event type (e.g., "artifact.published")
     * @return True if this webhook should receive it
     */
    public boolean matchesEvent(final String eventType) {
        return this.events.isEmpty() || this.events.contains(eventType);
    }

    /**
     * Check if this webhook should receive events for the given repo.
     * @param repoName Repository name
     * @return True if this webhook should receive events for this repo
     */
    public boolean matchesRepo(final String repoName) {
        return this.repos.isEmpty() || this.repos.contains(repoName);
    }

    /**
     * Get the signing secret if configured.
     * @return Optional secret
     */
    public Optional<String> signingSecret() {
        return Optional.ofNullable(this.secret);
    }
}
