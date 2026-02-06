/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.test.TestSettings;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Test case for {@link HealthSlice}.
 *
 * @since 0.10
 */
@SuppressWarnings({"PMD.ExcessivePublicCount", "PMD.TooManyMethods"})
final class HealthSliceTest {
    /**
     * Request line for health endpoint.
     */
    private static final RequestLine REQ_LINE = new RequestLine(RqMethod.GET, "/.health");

    @Test
    void returnsHealthyWhenStorageOkAndNoDb() {
        final Response response = new HealthSlice(
            new InMemoryStorage(), Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be healthy",
            json.getString("status"), Matchers.is("healthy")
        );
        final JsonObject components = json.getJsonObject("components");
        MatcherAssert.assertThat(
            "storage status should be ok",
            components.getJsonObject("storage").getString("status"),
            Matchers.is("ok")
        );
        MatcherAssert.assertThat(
            "storage latency_ms should be present",
            components.getJsonObject("storage").containsKey("latency_ms"),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "database status should be not_configured",
            components.getJsonObject("database").getString("status"),
            Matchers.is("not_configured")
        );
        MatcherAssert.assertThat(
            "valkey status should be not_configured",
            components.getJsonObject("valkey").getString("status"),
            Matchers.is("not_configured")
        );
        MatcherAssert.assertThat(
            "quartz status should be not_configured",
            components.getJsonObject("quartz").getString("status"),
            Matchers.is("not_configured")
        );
        MatcherAssert.assertThat(
            "http_client status should be not_configured",
            components.getJsonObject("http_client").getString("status"),
            Matchers.is("not_configured")
        );
    }

    @Test
    void returnsHealthyWithSettingsConstructor() {
        final Response response = new HealthSlice(new TestSettings()).response(
            REQ_LINE, Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be healthy",
            json.getString("status"), Matchers.is("healthy")
        );
    }

    @Test
    void returnsHealthyWithLatencies() {
        final Response response = new HealthSlice(
            new InMemoryStorage(), Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        final JsonObject json = parseJson(response);
        final JsonObject storage = json.getJsonObject("components")
            .getJsonObject("storage");
        MatcherAssert.assertThat(
            "storage latency_ms should be non-negative",
            storage.getJsonNumber("latency_ms").longValue(),
            Matchers.greaterThanOrEqualTo(0L)
        );
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void returnsUnhealthyWhenStorageSlow() {
        final Response response = new HealthSlice(
            new SlowStorage(), Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be SERVICE_UNAVAILABLE",
            response.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be unhealthy",
            json.getString("status"), Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "storage status should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("storage").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void returnsUnhealthyWhenStorageBroken() {
        final Response response = new HealthSlice(
            new FakeStorage(), Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be SERVICE_UNAVAILABLE",
            response.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be unhealthy",
            json.getString("status"), Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "storage component should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("storage").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void returnsDegradedWhenDbDownButStorageOk() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.of(new FakeDataSource(true))
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "storage should be ok",
            json.getJsonObject("components")
                .getJsonObject("storage").getString("status"),
            Matchers.is("ok")
        );
        MatcherAssert.assertThat(
            "database should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("database").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void returnsHealthyWhenDbOk() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.of(new FakeDataSource(false))
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be healthy",
            json.getString("status"), Matchers.is("healthy")
        );
        MatcherAssert.assertThat(
            "database should be ok",
            json.getJsonObject("components")
                .getJsonObject("database").getString("status"),
            Matchers.is("ok")
        );
    }

    @Test
    void returnsHealthyWhenAllFiveComponentsOk() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.of(new FakeDataSource(false)),
            Optional.of(() -> CompletableFuture.completedFuture(true)),
            Optional.of(() -> true),
            Optional.of(() -> true)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be healthy",
            json.getString("status"), Matchers.is("healthy")
        );
        final JsonObject components = json.getJsonObject("components");
        MatcherAssert.assertThat(
            "storage should be ok",
            components.getJsonObject("storage").getString("status"),
            Matchers.is("ok")
        );
        MatcherAssert.assertThat(
            "database should be ok",
            components.getJsonObject("database").getString("status"),
            Matchers.is("ok")
        );
        MatcherAssert.assertThat(
            "valkey should be ok",
            components.getJsonObject("valkey").getString("status"),
            Matchers.is("ok")
        );
        MatcherAssert.assertThat(
            "quartz should be ok",
            components.getJsonObject("quartz").getString("status"),
            Matchers.is("ok")
        );
        MatcherAssert.assertThat(
            "http_client should be ok",
            components.getJsonObject("http_client").getString("status"),
            Matchers.is("ok")
        );
    }

    @Test
    void returnsDegradedWhenValkeyDownOthersOk() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.of(new FakeDataSource(false)),
            Optional.of(() -> CompletableFuture.completedFuture(false)),
            Optional.of(() -> true),
            Optional.of(() -> true)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "valkey should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("valkey").getString("status"),
            Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "valkey latency_ms should be present",
            json.getJsonObject("components")
                .getJsonObject("valkey").containsKey("latency_ms"),
            Matchers.is(true)
        );
    }

    @Test
    void returnsUnhealthyWhenTwoNonStorageDown() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.of(new FakeDataSource(true)),
            Optional.of(() -> CompletableFuture.completedFuture(false)),
            Optional.of(() -> true),
            Optional.of(() -> true)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be SERVICE_UNAVAILABLE",
            response.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be unhealthy",
            json.getString("status"), Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "database should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("database").getString("status"),
            Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "valkey should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("valkey").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void notConfiguredComponentsNotCountedAsDown() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be healthy",
            json.getString("status"), Matchers.is("healthy")
        );
        final JsonObject components = json.getJsonObject("components");
        MatcherAssert.assertThat(
            "database should be not_configured",
            components.getJsonObject("database").getString("status"),
            Matchers.is("not_configured")
        );
        MatcherAssert.assertThat(
            "valkey should be not_configured",
            components.getJsonObject("valkey").getString("status"),
            Matchers.is("not_configured")
        );
        MatcherAssert.assertThat(
            "quartz should be not_configured",
            components.getJsonObject("quartz").getString("status"),
            Matchers.is("not_configured")
        );
        MatcherAssert.assertThat(
            "http_client should be not_configured",
            components.getJsonObject("http_client").getString("status"),
            Matchers.is("not_configured")
        );
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void valkeyProbeTimeoutReturnsUnhealthy() {
        final Supplier<CompletableFuture<Boolean>> neverCompletes =
            () -> new CompletableFuture<>();
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.of(neverCompletes),
            Optional.empty(),
            Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "valkey should be unhealthy due to timeout",
            json.getJsonObject("components")
                .getJsonObject("valkey").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void returnsDegradedWhenQuartzDownOthersOk() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(() -> false),
            Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "quartz should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("quartz").getString("status"),
            Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "quartz latency_ms should be present",
            json.getJsonObject("components")
                .getJsonObject("quartz").containsKey("latency_ms"),
            Matchers.is(true)
        );
    }

    @Test
    void returnsDegradedWhenHttpClientDownOthersOk() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(() -> false)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "http_client should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("http_client").getString("status"),
            Matchers.is("unhealthy")
        );
        MatcherAssert.assertThat(
            "http_client latency_ms should be present",
            json.getJsonObject("components")
                .getJsonObject("http_client").containsKey("latency_ms"),
            Matchers.is(true)
        );
    }

    @Test
    void httpClientExceptionReturnsUnhealthy() {
        final Supplier<Boolean> throwing = () -> {
            throw new RuntimeException("HTTP client exploded");
        };
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(throwing)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "http_client should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("http_client").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void valkeyExceptionReturnsUnhealthy() {
        final Supplier<CompletableFuture<Boolean>> throwing = () -> {
            throw new RuntimeException("Valkey connection lost");
        };
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.of(throwing),
            Optional.empty(),
            Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "valkey should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("valkey").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void quartzExceptionReturnsUnhealthy() {
        final Supplier<Boolean> throwing = () -> {
            throw new RuntimeException("Quartz scheduler crashed");
        };
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(throwing),
            Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK for degraded",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be degraded",
            json.getString("status"), Matchers.is("degraded")
        );
        MatcherAssert.assertThat(
            "quartz should be unhealthy",
            json.getJsonObject("components")
                .getJsonObject("quartz").getString("status"),
            Matchers.is("unhealthy")
        );
    }

    @Test
    void returnsUnhealthyWhenStorageDownEvenIfOthersOk() {
        final Response response = new HealthSlice(
            new FakeStorage(),
            Optional.of(new FakeDataSource(false)),
            Optional.of(() -> CompletableFuture.completedFuture(true)),
            Optional.of(() -> true),
            Optional.of(() -> true)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be SERVICE_UNAVAILABLE",
            response.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be unhealthy when storage is down",
            json.getString("status"), Matchers.is("unhealthy")
        );
    }

    @Test
    void jsonContainsAllFiveComponents() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        final JsonObject components = parseJson(response).getJsonObject("components");
        MatcherAssert.assertThat(
            "components should contain storage",
            components.containsKey("storage"), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "components should contain database",
            components.containsKey("database"), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "components should contain valkey",
            components.containsKey("valkey"), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "components should contain quartz",
            components.containsKey("quartz"), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "components should contain http_client",
            components.containsKey("http_client"), Matchers.is(true)
        );
    }

    @Test
    void returnsUnhealthyWhenThreeNonStorageDown() {
        final Response response = new HealthSlice(
            new InMemoryStorage(),
            Optional.of(new FakeDataSource(true)),
            Optional.of(() -> CompletableFuture.completedFuture(false)),
            Optional.of(() -> false),
            Optional.of(() -> true)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be SERVICE_UNAVAILABLE",
            response.status(), Matchers.is(RsStatus.SERVICE_UNAVAILABLE)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be unhealthy",
            json.getString("status"), Matchers.is("unhealthy")
        );
    }

    @Test
    void withServicesFactoryConfiguresAllProbes() {
        final Response response = HealthSlice.withServices(
            new InMemoryStorage(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(() -> true)
        ).response(REQ_LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "status should be OK",
            response.status(), Matchers.is(RsStatus.OK)
        );
        final JsonObject json = parseJson(response);
        MatcherAssert.assertThat(
            "overall status should be healthy",
            json.getString("status"), Matchers.is("healthy")
        );
        final JsonObject components = json.getJsonObject("components");
        MatcherAssert.assertThat(
            "http_client should be ok via withServices",
            components.getJsonObject("http_client").getString("status"),
            Matchers.is("ok")
        );
    }

    /**
     * Parses response body as JSON object.
     * @param response HTTP response
     * @return Parsed JSON object
     */
    private static JsonObject parseJson(final Response response) {
        final String body = new String(response.body().asBytes(), StandardCharsets.UTF_8);
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }

    /**
     * Implementation of broken storage.
     * All methods throw exception.
     *
     * @since 0.10
     */
    private static class FakeStorage implements Storage {
        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key,
            final Function<Storage, CompletionStage<T>> function) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Storage that hangs on list (never completes),
     * to test health check timeout behavior.
     */
    private static class SlowStorage implements Storage {
        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return new CompletableFuture<>();
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key,
            final Function<Storage, CompletionStage<T>> function) {
            return new CompletableFuture<>();
        }
    }

    /**
     * Fake DataSource that either throws or returns a valid connection.
     * @param broken If true, getConnection throws SQLException
     */
    private record FakeDataSource(boolean broken) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            if (this.broken) {
                throw new SQLException("connection refused");
            }
            return new FakeConnection();
        }

        @Override
        public Connection getConnection(final String username,
            final String password) throws SQLException {
            return this.getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(final PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(final int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(final Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(final Class<?> iface) {
            return false;
        }
    }

    /**
     * Minimal Connection stub that reports isValid=true.
     */
    @SuppressWarnings({"PMD.ExcessivePublicCount", "PMD.TooManyMethods"})
    private static final class FakeConnection implements Connection {
        @Override public boolean isValid(final int timeout) { return true; }
        @Override public void close() { }
        @Override public boolean isClosed() { return false; }
        @Override public java.sql.Statement createStatement() { return null; }
        @Override public java.sql.PreparedStatement prepareStatement(String s) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String s) { return null; }
        @Override public String nativeSQL(String s) { return s; }
        @Override public void setAutoCommit(boolean b) { }
        @Override public boolean getAutoCommit() { return true; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public java.sql.DatabaseMetaData getMetaData() { return null; }
        @Override public void setReadOnly(boolean b) { }
        @Override public boolean isReadOnly() { return false; }
        @Override public void setCatalog(String s) { }
        @Override public String getCatalog() { return null; }
        @Override public void setTransactionIsolation(int i) { }
        @Override public int getTransactionIsolation() { return Connection.TRANSACTION_NONE; }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public java.sql.Statement createStatement(int a, int b) { return null; }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b) { return null; }
        @Override public java.util.Map<String, Class<?>> getTypeMap() { return java.util.Collections.emptyMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) { }
        @Override public void setHoldability(int h) { }
        @Override public int getHoldability() { return 0; }
        @Override public java.sql.Savepoint setSavepoint() { return null; }
        @Override public java.sql.Savepoint setSavepoint(String n) { return null; }
        @Override public void rollback(java.sql.Savepoint s) { }
        @Override public void releaseSavepoint(java.sql.Savepoint s) { }
        @Override public java.sql.Statement createStatement(int a, int b, int c) { return null; }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b, int c) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b, int c) { return null; }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int k) { return null; }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int[] c) { return null; }
        @Override public java.sql.PreparedStatement prepareStatement(String s, String[] c) { return null; }
        @Override public java.sql.Clob createClob() { return null; }
        @Override public java.sql.Blob createBlob() { return null; }
        @Override public java.sql.NClob createNClob() { return null; }
        @Override public java.sql.SQLXML createSQLXML() { return null; }
        @Override public void setClientInfo(String n, String v) { }
        @Override public void setClientInfo(java.util.Properties p) { }
        @Override public String getClientInfo(String n) { return null; }
        @Override public java.util.Properties getClientInfo() { return new java.util.Properties(); }
        @Override public java.sql.Array createArrayOf(String t, Object[] e) { return null; }
        @Override public java.sql.Struct createStruct(String t, Object[] a) { return null; }
        @Override public void setSchema(String s) { }
        @Override public String getSchema() { return null; }
        @Override public void abort(java.util.concurrent.Executor e) { }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor e, int m) { }
        @Override public int getNetworkTimeout() { return 0; }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { throw new SQLException(); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }
}
