/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.AssertSlice;
import com.auto1.pantera.http.hm.RqLineHasUri;
import com.auto1.pantera.http.hm.RsHasHeaders;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.LoggingContext;
import com.auto1.pantera.settings.MetricsContext;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.cache.CachedUsers;
import com.auto1.pantera.test.TestPanteraCaches;
import com.auto1.pantera.test.TestSettings;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;

/**
 * Test case for {@link DockerRoutingSlice}.
 */
final class DockerRoutingSliceTest {

    /**
     * A JWT-shaped API token used by the token regression tests.
     */
    private static final String API_TOKEN =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

    /**
     * Token authentication that rejects everything — for tests where
     * tokens are irrelevant.
     */
    private static final TokenAuthentication NO_TOKENS =
        token -> CompletableFuture.completedFuture(Optional.empty());

    @Test
    void removesDockerPrefix() throws Exception {
        verify(
            new DockerRoutingSlice(
                new TestSettings(),
                NO_TOKENS,
                new AssertSlice(new RqLineHasUri(new RqLineHasUri.HasPath("/foo/bar")))
            ),
            "/v2/foo/bar"
        );
    }

    @Test
    void ignoresNonDockerRequests() throws Exception {
        final String path = "/repo/name";
        verify(
            new DockerRoutingSlice(
                new TestSettings(),
                NO_TOKENS,
                new AssertSlice(new RqLineHasUri(new RqLineHasUri.HasPath(path)))
            ),
            path
        );
    }

    @Test
    void emptyDockerRequest() {
        final String username = "alice";
        final String password = "letmein";
        MatcherAssert.assertThat(
            new DockerRoutingSlice(
                new SettingsWithAuth(new Authentication.Single(username, password)),
                NO_TOKENS,
                (line, headers, body) -> {
                    throw new UnsupportedOperationException();
                }
            ),
            new SliceHasResponse(
                new AllOf<>(
                    Arrays.asList(
                        new RsHasStatus(RsStatus.OK),
                        new RsHasHeaders(
                            Headers.from("Docker-Distribution-API-Version", "registry/2.0")
                        )
                    )
                ),
                new RequestLine(RqMethod.GET, "/v2/"),
                Headers.from(new Authorization.Basic(username, password)),
                Content.EMPTY
            )
        );
    }

    /**
     * Regression: {@code docker login} sends API tokens as the Basic
     * password against {@code /v2/} — the ping must accept a valid token
     * for the claimed user even when the password check would reject it.
     */
    @Test
    void emptyDockerRequestAcceptsApiTokenAsBasicPassword() {
        final String username = "alice";
        MatcherAssert.assertThat(
            new DockerRoutingSlice(
                new SettingsWithAuth(new Authentication.Single(username, "real-password")),
                token -> CompletableFuture.completedFuture(
                    Optional.of(new AuthUser(username, "token"))
                        .filter(user -> API_TOKEN.equals(token))
                ),
                (line, headers, body) -> {
                    throw new UnsupportedOperationException();
                }
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/v2/"),
                Headers.from(new Authorization.Basic(username, API_TOKEN)),
                Content.EMPTY
            )
        );
    }

    /**
     * Regression: the bare {@code /v2/} ping must accept Bearer tokens
     * exactly like the repository-scoped Docker endpoints do.
     */
    @Test
    void emptyDockerRequestAcceptsBearerToken() {
        MatcherAssert.assertThat(
            new DockerRoutingSlice(
                new SettingsWithAuth(new Authentication.Single("alice", "real-password")),
                token -> CompletableFuture.completedFuture(
                    Optional.of(new AuthUser("alice", "token"))
                        .filter(user -> API_TOKEN.equals(token))
                ),
                (line, headers, body) -> {
                    throw new UnsupportedOperationException();
                }
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/v2/"),
                Headers.from(new Header("Authorization", "Bearer " + API_TOKEN)),
                Content.EMPTY
            )
        );
    }

    @Test
    void revertsDockerRequest() throws Exception {
        final String path = "/v2/one/two";
        verify(
            new DockerRoutingSlice(
                new TestSettings(),
                NO_TOKENS,
                new DockerRoutingSlice.Reverted(
                    new AssertSlice(new RqLineHasUri(new RqLineHasUri.HasPath(path)))
                )
            ),
            path
        );
    }

    private static void verify(final Slice slice, final String path) {
        slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
    }

    /**
     * Fake settings with auth.
     */
    private static class SettingsWithAuth implements Settings {

        /**
         * Authentication.
         */
        private final Authentication auth;

        SettingsWithAuth(final Authentication auth) {
            this.auth = auth;
        }

        @Override
        public Storage configStorage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PanteraSecurity authz() {
            return new PanteraSecurity() {

                @Override
                public CachedUsers authentication() {
                    return new CachedUsers(SettingsWithAuth.this.auth);
                }

                @Override
                public Policy<?> policy() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<Storage> policyStorage() {
                    return Optional.empty();
                }
            };
        }

        @Override
        public YamlMapping meta() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Storage repoConfigsStorage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KeyStore> keyStore() {
            return Optional.empty();
        }

        @Override
        public MetricsContext metrics() {
            return null;
        }

        @Override
        public PanteraCaches caches() {
            return new TestPanteraCaches();
        }

        @Override
        public Optional<MetadataEventQueues> artifactMetadata() {
            return Optional.empty();
        }

        @Override
        public Optional<YamlSequence> crontab() {
            return Optional.empty();
        }

        @Override
        public LoggingContext logging() {
            return new LoggingContext(Yaml.createYamlMappingBuilder().build());
        }

        @Override
        public CooldownSettings cooldown() {
            return CooldownSettings.defaults();
        }

        @Override
        public Optional<DataSource> artifactsDatabase() {
            return Optional.empty();
        }

        @Override
        public com.auto1.pantera.settings.PrefixesConfig prefixes() {
            return new com.auto1.pantera.settings.PrefixesConfig();
        }

        @Override
        public java.nio.file.Path configPath() {
            return java.nio.file.Paths.get("/tmp/test-pantera.yaml");
        }
    }
}
