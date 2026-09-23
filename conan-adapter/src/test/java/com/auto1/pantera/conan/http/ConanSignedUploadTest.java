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
package com.auto1.pantera.conan.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.conan.ItemTokenizer;
import com.auto1.pantera.conan.TestRsaKeys;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.Vertx;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A signed Conan upload URL is only redeemable in the repository that issued
 * it, and only by a user with WRITE there.
 *
 * @since 2.2.9
 */
final class ConanSignedUploadTest {

    /**
     * Bearer token of the test user.
     */
    private static final String TOKEN = "alice-token";

    /**
     * Recipe file every test uploads.
     */
    private static final Key FILE = new Key.From("zlib/1.2.13/_/_/0/export/conanfile.py");

    /**
     * Vert.x instance.
     */
    private static Vertx vertx;

    /**
     * Shared tokenizer: every Conan repository signs with the same key pair.
     */
    private static ItemTokenizer tokenizer;

    @BeforeAll
    static void start() {
        ConanSignedUploadTest.vertx = Vertx.vertx();
        ConanSignedUploadTest.tokenizer = new ItemTokenizer(
            ConanSignedUploadTest.vertx, TestRsaKeys.publicKey(), TestRsaKeys.privateKey()
        );
    }

    @AfterAll
    static void stop() {
        ConanSignedUploadTest.vertx.close();
    }

    @Test
    void uploadUrlOfOneRepositoryCannotWriteIntoAnother() throws Exception {
        final Storage target = new InMemoryStorage();
        final Map<String, Action> grants = Map.of(
            "repo-a", Action.Standard.WRITE, "repo-b", Action.Standard.WRITE
        );
        final URI url = ConanSignedUploadTest.uploadUrl(
            ConanSignedUploadTest.slice(new InMemoryStorage(), "repo-a", grants)
        );
        MatcherAssert.assertThat(
            "status",
            ConanSignedUploadTest.put(
                ConanSignedUploadTest.slice(target, "repo-b", grants), url, true
            ),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "nothing written", target.exists(ConanSignedUploadTest.FILE).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void signedUploadWithoutCredentialsIsRefused() throws Exception {
        final Storage asto = new InMemoryStorage();
        final Slice slice = ConanSignedUploadTest.slice(
            asto, "repo-a", Map.of("repo-a", Action.Standard.WRITE)
        );
        MatcherAssert.assertThat(
            "status",
            ConanSignedUploadTest.put(slice, ConanSignedUploadTest.uploadUrl(slice), false),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "nothing written", asto.exists(ConanSignedUploadTest.FILE).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void signedUploadByUserWhoLostWriteIsRefused() throws Exception {
        final Storage asto = new InMemoryStorage();
        final URI url = ConanSignedUploadTest.uploadUrl(
            ConanSignedUploadTest.slice(asto, "repo-a", Map.of("repo-a", Action.Standard.WRITE))
        );
        MatcherAssert.assertThat(
            "status",
            ConanSignedUploadTest.put(
                ConanSignedUploadTest.slice(
                    asto, "repo-a", Map.of("repo-a", Action.Standard.READ)
                ),
                url, true
            ),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "nothing written", asto.exists(ConanSignedUploadTest.FILE).join(),
            new IsEqual<>(false)
        );
    }

    @Test
    void signedUploadWithWriteIsStored() throws Exception {
        final Storage asto = new InMemoryStorage();
        final Slice slice = ConanSignedUploadTest.slice(
            asto, "repo-a", Map.of("repo-a", Action.Standard.WRITE)
        );
        MatcherAssert.assertThat(
            "status",
            ConanSignedUploadTest.put(slice, ConanSignedUploadTest.uploadUrl(slice), true),
            new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "file written", asto.exists(ConanSignedUploadTest.FILE).join(),
            new IsEqual<>(true)
        );
    }

    private static Slice slice(final Storage asto, final String name,
        final Map<String, Action> grants) {
        final Policy<PermissionCollection> policy = user -> {
            final Permissions perms = new Permissions();
            grants.forEach((repo, act) -> perms.add(new AdapterBasicPermission(repo, act)));
            return perms;
        };
        return new ConanSlice(
            asto, policy,
            (user, pass) -> Optional.of(new AuthUser(user, "test")),
            new ConanSlice.FakeAuthTokens(ConanSignedUploadTest.TOKEN, "alice"),
            ConanSignedUploadTest.tokenizer, name
        );
    }

    private static URI uploadUrl(final Slice slice) throws Exception {
        final String body = slice.response(
            new RequestLine(RqMethod.POST, "/v1/conans/zlib/1.2.13/_/_/upload_urls"),
            ConanSignedUploadTest.headers(true),
            new Content.From("{\"conanfile.py\": 6}".getBytes(StandardCharsets.UTF_8))
        ).join().body().asString();
        return URI.create(
            Json.createReader(new StringReader(body)).readObject().getString("conanfile.py")
        );
    }

    private static RsStatus put(final Slice slice, final URI url, final boolean auth) {
        return slice.response(
            new RequestLine(
                RqMethod.PUT, String.format("%s?%s", url.getRawPath(), url.getRawQuery())
            ),
            ConanSignedUploadTest.headers(auth),
            new Content.From("recipe".getBytes(StandardCharsets.UTF_8))
        ).join().status();
    }

    private static Headers headers(final boolean auth) {
        final Headers headers = Headers.from(new Header("Host", "localhost"));
        if (auth) {
            headers.add(new Authorization.Bearer(ConanSignedUploadTest.TOKEN));
        }
        return headers;
    }
}
