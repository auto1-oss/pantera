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
package com.auto1.pantera.http.client.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.client.FakeClientSlices;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.log.LogThrottle;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The token realm of Docker Hub ({@code auth.docker.io}) is not the upstream
 * host ({@code registry-1.docker.io}), so the configured credentials are not
 * sent to it unless it is allowlisted. That decision stands; what changed is
 * its record: it was written on every token request (340 times in four days
 * for one host) and did not say what to do. It is written for the first
 * occurrence per upstream and realm host, then at most once per interval
 * with the number of occurrences in between, and names the setting.
 *
 * @since 2.2.14
 */
final class BearerRealmUntrustedLogTest {

    private static final String LOGGER = "com.auto1.pantera.http.client.auth";

    private static final String ACTION = "bearer_realm_untrusted";

    private static final URI UPSTREAM = URI.create("https://registry-1.docker.io");

    private static final Headers CHALLENGE = Headers.from(
        new WwwAuthenticate("Bearer realm=\"https://auth.docker.io/token\"")
    );

    private LogCapture capture;

    private AtomicLong clock;

    private AtomicReference<Headers> token;

    @BeforeEach
    void setUp() {
        this.capture = LogCapture.of(LOGGER);
        this.clock = new AtomicLong(0L);
        this.token = new AtomicReference<>();
    }

    @AfterEach
    void tearDown() {
        this.capture.close();
    }

    @Test
    void untrustedRealmIsReportedOnceNotPerRequest() {
        final Authenticator auth = this.authenticator(Set.of());
        for (int idx = 0; idx < 5; idx += 1) {
            auth.authenticate(CHALLENGE).toCompletableFuture().join();
        }
        final List<Map<String, Object>> records = this.capture.action(ACTION);
        MatcherAssert.assertThat(
            "five token requests, one record",
            records.size(), new IsEqual<>(1)
        );
        final String message = String.valueOf(records.get(0).get("message"));
        MatcherAssert.assertThat(
            "the record names the setting to change",
            message, new StringContains("upstream_credential_allow_hosts")
        );
        MatcherAssert.assertThat(
            "and its environment fallback",
            message, new StringContains("PANTERA_UPSTREAM_CREDENTIAL_ALLOW_HOSTS")
        );
        MatcherAssert.assertThat(
            "and says that Docker Hub needs its realm host listed",
            message, new StringContains("Docker Hub need auth.docker.io")
        );
        MatcherAssert.assertThat(
            "the realm host is a field of the record",
            records.get(0).get("destination.address"), new IsEqual<>("auth.docker.io")
        );
    }

    @Test
    void afterTheIntervalOneRecordCountsWhatWasNotLogged() {
        final Authenticator auth = this.authenticator(Set.of());
        for (int idx = 0; idx < 4; idx += 1) {
            auth.authenticate(CHALLENGE).toCompletableFuture().join();
        }
        this.clock.set(Duration.ofHours(1).toMillis());
        auth.authenticate(CHALLENGE).toCompletableFuture().join();
        auth.authenticate(CHALLENGE).toCompletableFuture().join();
        final List<Map<String, Object>> records = this.capture.action(ACTION);
        MatcherAssert.assertThat(
            "the first occurrence and one record after the interval",
            records.size(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the later record says how many occurrences were not logged",
            String.valueOf(records.get(1).get("message")),
            new StringContains("3 more occurrence(s)")
        );
    }

    @Test
    void credentialsStayWithheldWhetherLoggedOrNot() {
        final Authenticator auth = this.authenticator(Set.of());
        auth.authenticate(CHALLENGE).toCompletableFuture().join();
        final boolean first = this.token.get().values(Authorization.NAME).isEmpty();
        auth.authenticate(CHALLENGE).toCompletableFuture().join();
        final boolean second = this.token.get().values(Authorization.NAME).isEmpty();
        MatcherAssert.assertThat(
            "the logged request carries no credentials", first, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "nor does the one that is not logged", second, new IsEqual<>(true)
        );
    }

    @Test
    void allowlistedRealmGetsCredentialsAndNoRecord() {
        this.authenticator(Set.of("auth.docker.io"))
            .authenticate(CHALLENGE).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the allowlisted realm receives the credentials",
            this.token.get().values(Authorization.NAME).isEmpty(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and nothing is logged",
            this.capture.action(ACTION), new IsEqual<>(List.of())
        );
    }

    private Authenticator authenticator(final Set<String> allowed) {
        return new BearerAuthenticator(
            new FakeClientSlices(
                (line, headers, body) -> {
                    this.token.set(headers);
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok().jsonBody("{\"access_token\":\"tok\"}").build()
                    );
                }
            ),
            new OAuthTokenFormat(),
            new BasicAuthenticator("bob", "12345"),
            new RealmTrust(UPSTREAM, allowed),
            new WithheldCredentialsLog(
                LOGGER, ACTION, "Bearer realm",
                new LogThrottle(Duration.ofHours(1), 16, this.clock::get)
            )
        );
    }
}
