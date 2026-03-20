/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.webhook;

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
