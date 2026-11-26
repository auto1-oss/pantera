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
package com.auto1.pantera.auth;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.scheduling.QuartzService;
import com.auto1.pantera.settings.YamlSettings;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sign-in chain that {@link YamlSettings} builds never hands a
 * credential with the form of an API key to a password provider, in
 * whatever order the providers are configured.
 *
 * <p>The password provider here is {@link RecordingAuthFactory}, which
 * stands for one that forwards the password to an external identity
 * provider. The key provider is the real {@code jwt-password} one.</p>
 *
 * @since 2.2.15
 */
final class ApiKeyOnlyChainTest {

    /**
     * RSA key fixtures.
     */
    private static final String FIXTURES = "auth/rsa/";

    /**
     * Provider order with the password provider first.
     */
    private static final String PASSWORDS_FIRST = "recording,jwt-password";

    /**
     * Provider order with the key provider first.
     */
    private static final String KEYS_FIRST = "jwt-password,recording";

    /**
     * Vert.x instance of the token signers.
     */
    private static Vertx vertx;

    /**
     * Signer with the key pair this installation trusts.
     */
    private static JWTAuth own;

    /**
     * Signer with a key pair of another installation.
     */
    private static JWTAuth foreign;

    /**
     * Settings directory.
     */
    @TempDir
    Path temp;

    @BeforeAll
    static void startVertx() throws Exception {
        ApiKeyOnlyChainTest.vertx = Vertx.vertx();
        JwtPasswordAuthFactory.setSharedVertx(ApiKeyOnlyChainTest.vertx);
        ApiKeyOnlyChainTest.own = ApiKeyOnlyChainTest.signer(
            "pub-2048.pem", "priv-2048-pkcs8.pem"
        );
        ApiKeyOnlyChainTest.foreign = ApiKeyOnlyChainTest.signer(
            "pub-4096.pem", "priv-4096-pkcs8.pem"
        );
    }

    @AfterAll
    static void stopVertx() {
        if (ApiKeyOnlyChainTest.vertx != null) {
            ApiKeyOnlyChainTest.vertx.close();
        }
    }

    @BeforeEach
    void reset() {
        RecordingAuthFactory.SEEN.clear();
        TokenRevocationRegistry.instance().clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ApiKeyOnlyChainTest.PASSWORDS_FIRST, ApiKeyOnlyChainTest.KEYS_FIRST
    })
    void expiredKeyReachesNoPasswordProvider(final String order) throws Exception {
        final String key = ApiKeyOnlyChainTest.key(
            ApiKeyOnlyChainTest.own, "alice", System.currentTimeMillis() / 1000L - 60L
        );
        final Optional<AuthUser> user = this.chain(order).user("alice", key);
        MatcherAssert.assertThat(
            "an expired key is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the expired key is not handed to a password provider",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ApiKeyOnlyChainTest.PASSWORDS_FIRST, ApiKeyOnlyChainTest.KEYS_FIRST
    })
    void keyOfAnotherInstallationReachesNoPasswordProvider(final String order)
        throws Exception {
        final String key = ApiKeyOnlyChainTest.key(
            ApiKeyOnlyChainTest.foreign, "alice", System.currentTimeMillis() / 1000L + 600L
        );
        final Optional<AuthUser> user = this.chain(order).user("alice", key);
        MatcherAssert.assertThat(
            "a key signed elsewhere is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the foreign key is not handed to a password provider",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ApiKeyOnlyChainTest.PASSWORDS_FIRST, ApiKeyOnlyChainTest.KEYS_FIRST
    })
    void keySentWithAnotherUserNameReachesNoPasswordProvider(final String order)
        throws Exception {
        final String key = ApiKeyOnlyChainTest.key(
            ApiKeyOnlyChainTest.own, "alice", System.currentTimeMillis() / 1000L + 600L
        );
        final Optional<AuthUser> user = this.chain(order).user("bob", key);
        MatcherAssert.assertThat(
            "a key sent with another user's name is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "that key is not handed to a password provider",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ApiKeyOnlyChainTest.PASSWORDS_FIRST, ApiKeyOnlyChainTest.KEYS_FIRST
    })
    void unreadableKeyReachesNoPasswordProvider(final String order) throws Exception {
        final Optional<AuthUser> user = this.chain(order).user(
            "alice", "eyJub3QiOiJhIHRva2VuIn0.bm90LWEtcGF5bG9hZA.bm90LWEtc2lnbmF0dXJl"
        );
        MatcherAssert.assertThat(
            "a credential that only has the form of a key is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "it is not handed to a password provider",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ApiKeyOnlyChainTest.PASSWORDS_FIRST, ApiKeyOnlyChainTest.KEYS_FIRST
    })
    void validKeySignsInWithoutAPasswordProvider(final String order) throws Exception {
        final String key = ApiKeyOnlyChainTest.key(
            ApiKeyOnlyChainTest.own, "alice", System.currentTimeMillis() / 1000L + 600L
        );
        final Optional<AuthUser> user = this.chain(order).user("alice", key);
        MatcherAssert.assertThat(
            "a valid key signs its owner in",
            user.map(AuthUser::name), new IsEqual<>(Optional.of("alice"))
        );
        MatcherAssert.assertThat(
            "no password provider is asked about a valid key",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ApiKeyOnlyChainTest.PASSWORDS_FIRST, ApiKeyOnlyChainTest.KEYS_FIRST
    })
    void passwordStillReachesThePasswordProvider(final String order) throws Exception {
        final Optional<AuthUser> user =
            this.chain(order).user("alice", RecordingAuthFactory.PASSWORD);
        MatcherAssert.assertThat(
            "a password is still checked by the password provider",
            user.map(AuthUser::name), new IsEqual<>(Optional.of("alice"))
        );
        MatcherAssert.assertThat(
            "the password provider saw the password once",
            RecordingAuthFactory.SEEN,
            new IsEqual<>(List.of(RecordingAuthFactory.PASSWORD))
        );
    }

    @Test
    void wrongPasswordIsAskedOnce() throws Exception {
        final Optional<AuthUser> user =
            this.chain(ApiKeyOnlyChainTest.PASSWORDS_FIRST).user("alice", "wrong password");
        MatcherAssert.assertThat(
            "a wrong password is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the password provider saw it once",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of("wrong password"))
        );
    }

    @Test
    void keyIsRejectedWhenNoKeyProviderIsConfigured() throws Exception {
        final String key = ApiKeyOnlyChainTest.key(
            ApiKeyOnlyChainTest.own, "alice", System.currentTimeMillis() / 1000L + 600L
        );
        final Optional<AuthUser> user = this.chain("recording").user("alice", key);
        MatcherAssert.assertThat(
            "without a key provider a key is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the key is not handed to a password provider",
            RecordingAuthFactory.SEEN, new IsEqual<>(List.of())
        );
    }

    /**
     * Sign-in chain of settings with the given providers.
     * @param order Provider types in configured order, separated by commas
     * @return The chain repository requests and the sign-in form use
     * @throws Exception On error
     */
    private Authentication chain(final String order) throws Exception {
        final StringBuilder yaml = new StringBuilder(256)
            .append("meta:\n")
            .append("  jwt:\n")
            .append("    private-key-path: ")
            .append(ApiKeyOnlyChainTest.path("priv-2048-pkcs8.pem")).append('\n')
            .append("    public-key-path: ")
            .append(ApiKeyOnlyChainTest.path("pub-2048.pem")).append('\n')
            .append("  credentials:\n");
        for (final String type : order.split(",")) {
            yaml.append("    - type: ").append(type).append('\n');
        }
        return new YamlSettings(
            Yaml.createYamlInput(yaml.toString()).readYamlMapping(),
            this.temp, new QuartzService()
        ).authz().authentication();
    }

    /**
     * Mint an API key.
     * @param signer Signer
     * @param subject Owner
     * @param expires Expiry, seconds since the epoch
     * @return The key
     */
    private static String key(final JWTAuth signer, final String subject, final long expires) {
        return signer.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, subject)
                .put(AuthTokenRest.CONTEXT, "test")
                .put(AuthTokenRest.TYPE, "api")
                .put("exp", expires),
            new JWTOptions().setAlgorithm("RS256")
        );
    }

    private static JWTAuth signer(final String pub, final String priv) throws Exception {
        return JWTAuth.create(
            ApiKeyOnlyChainTest.vertx,
            new JWTAuthOptions()
                .addPubSecKey(
                    new PubSecKeyOptions().setAlgorithm("RS256")
                        .setBuffer(Files.readString(Path.of(ApiKeyOnlyChainTest.path(pub))))
                )
                .addPubSecKey(
                    new PubSecKeyOptions().setAlgorithm("RS256")
                        .setBuffer(Files.readString(Path.of(ApiKeyOnlyChainTest.path(priv))))
                )
        );
    }

    private static String path(final String resource) {
        final URL url = ApiKeyOnlyChainTest.class.getClassLoader()
            .getResource(ApiKeyOnlyChainTest.FIXTURES + resource);
        if (url == null) {
            throw new IllegalStateException(
                "Missing test fixture: " + ApiKeyOnlyChainTest.FIXTURES + resource
            );
        }
        return Path.of(URI.create(url.toString())).toString();
    }
}
