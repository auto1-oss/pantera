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
package com.auto1.pantera;

import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.misc.RandomFreePort;
import com.auto1.pantera.http.rt.MethodRule;
import com.auto1.pantera.http.rt.RtRulePath;
import com.auto1.pantera.http.rt.SliceRoute;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.vertx.VertxSliceServer;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import javax.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Slices integration tests.
 */
@EnabledForJreRange(min = JRE.JAVA_11, disabledReason = "HTTP client is not supported prior JRE_11")
public final class SliceITCase {

    /**
     * Test target slice.
     */
    private static final Slice TARGET = new SliceRoute(
        new RtRulePath(
            MethodRule.GET,
            new BasicAuthzSlice(
                new SliceSimple(
                    () -> ResponseBuilder.ok()
                        .jsonBody(Json.createObjectBuilder().add("any", "any").build())
                        .build()
                ),
                (username, password) -> Optional.of(new com.auto1.pantera.http.auth.AuthUser(username, "test")),
                new OperationControl(Policy.FREE, new AdapterBasicPermission("test", Action.ALL))
            )
        )
    );

    /**
     * Vertx slice server instance.
     */
    private VertxSliceServer server;

    /**
     * Application port.
     */
    private int port;

    @BeforeEach
    void init() {
        this.port = RandomFreePort.get();
        this.server = new VertxSliceServer(SliceITCase.TARGET, this.port);
        this.server.start();
    }

    @Test
    @Timeout(10)
    void singleRequestWorks() throws Exception {
        this.getRequest();
    }

    @Test
    @Timeout(10)
    void doubleRequestWorks() throws Exception {
        this.getRequest();
        this.getRequest();
    }

    @AfterEach
    void stop() {
        this.server.stop();
        this.server.close();
    }

    private void getRequest() throws Exception {
        final HttpResponse<String> rsp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(
                URI.create(String.format("http://localhost:%d/any", this.port))
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        MatcherAssert.assertThat("status", rsp.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat("body", rsp.body(), new StringContains("{\"any\":\"any\"}"));
    }
}
