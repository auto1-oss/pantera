/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.webhook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link WebhookConfig}.
 */
class WebhookConfigTest {

    @Test
    void matchesAllEventsWhenEmpty() {
        final WebhookConfig cfg = new WebhookConfig(
            "https://example.com/hook", null, List.of(), null
        );
        assertThat(cfg.matchesEvent("artifact.published"), is(true));
        assertThat(cfg.matchesEvent("artifact.deleted"), is(true));
    }

    @Test
    void matchesSpecificEvent() {
        final WebhookConfig cfg = new WebhookConfig(
            "https://example.com/hook", null, List.of("artifact.published"), null
        );
        assertThat(cfg.matchesEvent("artifact.published"), is(true));
        assertThat(cfg.matchesEvent("artifact.deleted"), is(false));
    }

    @Test
    void matchesAllReposWhenEmpty() {
        final WebhookConfig cfg = new WebhookConfig(
            "https://example.com/hook", null, null, List.of()
        );
        assertThat(cfg.matchesRepo("central"), is(true));
        assertThat(cfg.matchesRepo("any-repo"), is(true));
    }

    @Test
    void matchesSpecificRepo() {
        final WebhookConfig cfg = new WebhookConfig(
            "https://example.com/hook", null, null, List.of("central")
        );
        assertThat(cfg.matchesRepo("central"), is(true));
        assertThat(cfg.matchesRepo("snapshots"), is(false));
    }

    @Test
    void returnsSigningSecret() {
        final WebhookConfig cfg = new WebhookConfig(
            "https://example.com/hook", "my-secret", null, null
        );
        assertThat(cfg.signingSecret(), equalTo(Optional.of("my-secret")));
    }

    @Test
    void returnsEmptySecretWhenNull() {
        final WebhookConfig cfg = new WebhookConfig(
            "https://example.com/hook", null, null, null
        );
        assertThat(cfg.signingSecret(), equalTo(Optional.empty()));
    }
}
