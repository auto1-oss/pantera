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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;

/**
 * Tests for {@link WebhookDispatcher} HMAC computation.
 */
class WebhookDispatcherTest {

    @Test
    void computesHmacSha256() {
        final String hmac = WebhookDispatcher.computeHmac(
            "{\"event\":\"test\"}", "secret-key"
        );
        assertThat(hmac, not(emptyString()));
        // HMAC should be deterministic
        assertThat(
            WebhookDispatcher.computeHmac("{\"event\":\"test\"}", "secret-key"),
            equalTo(hmac)
        );
    }

    @Test
    void differentPayloadsProduceDifferentHmac() {
        final String hmac1 = WebhookDispatcher.computeHmac("payload1", "key");
        final String hmac2 = WebhookDispatcher.computeHmac("payload2", "key");
        assertThat(hmac1, not(equalTo(hmac2)));
    }

    @Test
    void differentSecretsProduceDifferentHmac() {
        final String hmac1 = WebhookDispatcher.computeHmac("payload", "key1");
        final String hmac2 = WebhookDispatcher.computeHmac("payload", "key2");
        assertThat(hmac1, not(equalTo(hmac2)));
    }
}
