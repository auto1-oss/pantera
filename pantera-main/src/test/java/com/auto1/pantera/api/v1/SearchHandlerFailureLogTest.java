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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.MapMessage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A failing artifact index answers 500 on every search endpoint with a fixed
 * message, while the exception lands in the log, never in the body.
 *
 * @since 2.2.11
 */
@ExtendWith(VertxExtension.class)
final class SearchHandlerFailureLogTest {

    /**
     * Exception text that must never reach the client.
     */
    private static final String SECRET = "ERROR: relation artifacts at db.internal.corp:5432";

    private HttpServer server;

    private WebClient client;

    private Capture capture;

    @BeforeEach
    void setUp(final Vertx vertx) throws Exception {
        final ArtifactIndex failing = (ArtifactIndex) Proxy.newProxyInstance(
            ArtifactIndex.class.getClassLoader(), new Class<?>[] {ArtifactIndex.class},
            (proxy, method, args) -> {
                final Object result;
                if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
                    result = CompletableFuture.failedFuture(new IllegalStateException(SECRET));
                } else if (method.getReturnType() == boolean.class) {
                    result = false;
                } else {
                    result = null;
                }
                return result;
            }
        );
        final Router router = Router.router(vertx);
        router.route().handler(
            rc -> {
                rc.setUser(
                    User.create(
                        new JsonObject()
                            .put(AuthTokenRest.SUB, "alice")
                            .put(AuthTokenRest.CONTEXT, "test")
                    )
                );
                rc.next();
            }
        );
        new SearchHandler(failing, Policy.FREE).register(router);
        this.server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        this.client = WebClient.create(vertx);
        this.capture = Capture.of("com.auto1.pantera.api");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.capture.close();
        this.client.close();
        this.server.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"/api/v1/search?q=lib", "/api/v1/search/locate?path=a/b.jar", "/api/v1/search/stats"}
    )
    void indexFailureLogsCauseAndHidesIt(final String uri) throws Exception {
        final HttpResponse<Buffer> rsp = this.client
            .get(this.server.actualPort(), "localhost", uri)
            .send()
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat("500", rsp.statusCode(), new IsEqual<>(500));
        MatcherAssert.assertThat(
            "no exception text in the body",
            rsp.bodyAsString(), new IsNot<>(new StringContains("db.internal.corp"))
        );
        final List<Map<String, Object>> causes = new ArrayList<>();
        synchronized (this.capture.events) {
            for (final Map<String, Object> rec : this.capture.events) {
                if ("java.lang.IllegalStateException".equals(rec.get("error.type"))) {
                    causes.add(rec);
                }
            }
        }
        MatcherAssert.assertThat("one cause record", causes.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "status", causes.get(0).get("http.response.status_code"), new IsEqual<>(500)
        );
    }

    /**
     * Appender capturing payload + context data of one logger.
     */
    private static final class Capture extends AbstractAppender implements AutoCloseable {

        /**
         * Events.
         */
        private final List<Map<String, Object>> events =
            Collections.synchronizedList(new ArrayList<>());

        /**
         * Logger.
         */
        private final Logger logger;

        /**
         * Ctor.
         * @param logger Logger
         */
        private Capture(final Logger logger) {
            super("SearchHandlerFailureLogTest", null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
        }

        static Capture of(final String name) {
            final Capture capture = new Capture((Logger) LogManager.getLogger(name));
            capture.start();
            capture.logger.addAppender(capture);
            capture.logger.setLevel(Level.DEBUG);
            return capture;
        }

        @Override
        public void append(final LogEvent event) {
            if (event.getMessage() instanceof MapMessage<?, ?> map) {
                final Map<String, Object> data = new HashMap<>();
                map.getData().forEach((key, value) -> data.put(String.valueOf(key), value));
                data.putAll(event.getContextData().toMap());
                this.events.add(data);
            }
        }

        @Override
        public void close() {
            this.logger.removeAppender(this);
            this.stop();
        }
    }
}
