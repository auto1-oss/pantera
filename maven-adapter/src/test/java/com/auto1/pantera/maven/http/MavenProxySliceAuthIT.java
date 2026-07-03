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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.client.auth.BasicAuthenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import java.net.URI;
import java.util.Optional;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MavenProxySlice} to verify it works with target requiring authentication.
 *
 * @since 0.7
 */
final class MavenProxySliceAuthIT {

    /**
     * Vertx instance.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Username and password.
     */
    private static final Pair<String, String> USER = new ImmutablePair<>("alice", "qwerty");

    /**
     * Jetty client.
     */
    private final JettyClientSlices client = new JettyClientSlices();

    /**
     * Vertx slice server instance.
     */
    private VertxSliceServer server;

    /**
     * Origin server port.
     */
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        final Storage storage = new InMemoryStorage();
        new TestResource("com/pantera/helloworld").addFilesTo(
            storage,
            new Key.From("com", "pantera", "helloworld")
        );
        this.server = new VertxSliceServer(
            MavenProxySliceAuthIT.VERTX,
            new LoggingSlice(
                new MavenSlice(
                    storage,
                    new PolicyByUsername(MavenProxySliceAuthIT.USER.getKey()),
                    new Authentication.Single(
                        MavenProxySliceAuthIT.USER.getKey(), MavenProxySliceAuthIT.USER.getValue()
                    ),
                    "test",
                    Optional.empty()
                )
            )
        );
        this.port = this.server.start();
        this.client.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.stop();
        this.server.stop();
    }

    @Test
    void shouldGet() {
        MatcherAssert.assertThat(
            new MavenProxySlice(
                this.client,
                URI.create(String.format("http://localhost:%d", this.port)),
                new BasicAuthenticator(
                    MavenProxySliceAuthIT.USER.getKey(), MavenProxySliceAuthIT.USER.getValue()
                )
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/com/pantera/helloworld/0.1/helloworld-0.1.pom")
            )
        );
    }

    @Test
    void shouldNotGetWithWrongUser() {
        MatcherAssert.assertThat(
            new MavenProxySlice(
                this.client,
                URI.create(String.format("http://localhost:%d", this.port)),
                new BasicAuthenticator("any", "any")
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/com/pantera/helloworld/0.1/helloworld-0.1.pom")
            )
        );
    }
}
