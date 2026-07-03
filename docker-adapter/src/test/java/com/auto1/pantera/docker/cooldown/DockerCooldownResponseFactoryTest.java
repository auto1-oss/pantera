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
package com.auto1.pantera.docker.cooldown;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;

/**
 * Tests for {@link DockerCooldownResponseFactory}.
 *
 * @since 2.2.0
 */
final class DockerCooldownResponseFactoryTest {

    private DockerCooldownResponseFactory factory;

    @BeforeEach
    void setUp() {
        this.factory = new DockerCooldownResponseFactory();
    }

    @Test
    void returns403Status() {
        final Response response = this.factory.forbidden(block());
        assertThat(response.status(), is(RsStatus.FORBIDDEN));
    }

    @Test
    void returnsApplicationJsonContentType() {
        final Response response = this.factory.forbidden(block());
        final String contentType = response.headers()
            .values("Content-Type").get(0);
        assertThat(contentType, containsString("application/json"));
    }

    @Test
    void bodyContainsDeniedErrorCode() {
        final Response response = this.factory.forbidden(block());
        final String body = new String(response.body().asBytes());
        assertThat(body, containsString("\"code\":\"DENIED\""));
    }

    @Test
    void bodyContainsTagInCooldownMessage() {
        final Response response = this.factory.forbidden(block());
        final String body = new String(response.body().asBytes());
        assertThat(body, containsString("\"message\":\"Tag in cooldown\""));
    }

    @Test
    void bodyContainsBlockedUntilDetail() {
        final Response response = this.factory.forbidden(block());
        final String body = new String(response.body().asBytes());
        assertThat(body, containsString("\"blocked_until\":\""));
        assertThat(body, containsString("Z"));
    }

    @Test
    void bodyMatchesDockerV2ErrorFormat() {
        final Response response = this.factory.forbidden(block());
        final String body = new String(response.body().asBytes());
        assertThat(body, containsString("\"errors\":[{"));
    }

    @Test
    void includesRetryAfterHeader() {
        final Response response = this.factory.forbidden(block());
        final String retryAfter = response.headers()
            .values("Retry-After").get(0);
        assertThat(retryAfter, is(not(emptyOrNullString())));
        final long seconds = Long.parseLong(retryAfter);
        assertThat(seconds > 0, is(true));
    }

    @Test
    void includesCooldownBlockedHeader() {
        final Response response = this.factory.forbidden(block());
        final String cooldown = response.headers()
            .values("X-Pantera-Cooldown").get(0);
        assertThat(cooldown, equalTo("blocked"));
    }

    @Test
    void repoTypeIsDocker() {
        assertThat(this.factory.repoType(), equalTo("docker"));
    }

    private static CooldownBlock block() {
        return new CooldownBlock(
            "docker",
            "docker-proxy",
            "library/nginx",
            "1.27",
            CooldownReason.FRESH_RELEASE,
            Instant.now().minus(1, ChronoUnit.HOURS),
            Instant.now().plus(23, ChronoUnit.HOURS),
            Collections.emptyList()
        );
    }
}
