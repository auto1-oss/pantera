/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Content;
import com.artipie.asto.cache.Cache;
import com.artipie.http.Headers;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.hm.SliceHasResponse;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.RsStatus;
import com.artipie.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;

/**
 * Integration test for {@link GradleProxySlice}.
 *
 * @since 1.0
 */
@EnabledIfSystemProperty(named = "test.integration", matches = "true")
class GradleProxyIT {

    private Vertx vertx;
    private JettyClientSlices client;
    private VertxSliceServer server;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
        this.client = new JettyClientSlices();
        this.client.start();
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
        if (this.client != null) {
            this.client.stop();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    void proxiesRequestToMavenCentral() {
        final GradleProxySlice slice = new GradleProxySlice(
            this.client,
            URI.create("https://repo1.maven.org/maven2"),
            Authenticator.ANONYMOUS
        );
        
        MatcherAssert.assertThat(
            "Should proxy request to Maven Central",
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.GET,
                    "/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.pom"
                ),
                Headers.EMPTY,
                Content.EMPTY
            )
        );
    }

    @Test
    void cachesArtifact() {
        final GradleProxySlice slice = new GradleProxySlice(
            this.client,
            URI.create("https://repo1.maven.org/maven2"),
            Authenticator.ANONYMOUS,
            Cache.NOP
        );
        
        // First request
        MatcherAssert.assertThat(
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.GET,
                    "/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar"
                ),
                Headers.EMPTY,
                Content.EMPTY
            )
        );
        
        // Second request should use cache
        MatcherAssert.assertThat(
            slice,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.GET,
                    "/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar"
                ),
                Headers.EMPTY,
                Content.EMPTY
            )
        );
    }
}
