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
import com.auto1.pantera.http.Response;
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
import java.util.Optional;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Re-upload of a stored Conan file against the per-repo {@code immutable} flag.
 *
 * @since 2.2.10
 */
final class ConanImmutableUploadTest {

    /**
     * Bearer token of the test user.
     */
    private static final String TOKEN = "alice-token";

    /**
     * Repository name.
     */
    private static final String REPO = "repo-a";

    /**
     * Recipe file every test uploads.
     */
    private static final Key FILE = new Key.From("zlib/1.2.13/_/_/0/export/conanfile.py");

    /**
     * Vert.x instance.
     */
    private static Vertx vertx;

    /**
     * Shared tokenizer.
     */
    private static ItemTokenizer tokenizer;

    @BeforeAll
    static void start() {
        ConanImmutableUploadTest.vertx = Vertx.vertx();
        ConanImmutableUploadTest.tokenizer = new ItemTokenizer(
            ConanImmutableUploadTest.vertx, TestRsaKeys.publicKey(), TestRsaKeys.privateKey()
        );
    }

    @AfterAll
    static void stop() {
        ConanImmutableUploadTest.vertx.close();
    }

    @Test
    void immutableRefusesUploadUrlsAndPutOfStoredFile() throws Exception {
        final Storage asto = new InMemoryStorage();
        final Slice slice = ConanImmutableUploadTest.slice(asto, Optional.of(true));
        final URI url = ConanImmutableUploadTest.uploadUrl(slice);
        MatcherAssert.assertThat(
            "first upload is stored",
            ConanImmutableUploadTest.put(slice, url, "recipe").status(),
            new IsEqual<>(RsStatus.CREATED)
        );
        final Response urls = ConanImmutableUploadTest.urls(slice);
        MatcherAssert.assertThat(
            "upload_urls for a stored file is refused with the status it always used",
            urls.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the client is told why", urls.body().asString(),
            new StringContains("already exists")
        );
        MatcherAssert.assertThat(
            "a PUT of the stored file (with a still valid signature) is refused",
            ConanImmutableUploadTest.put(slice, url, "changed").status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the stored file is untouched",
            asto.value(ConanImmutableUploadTest.FILE).join().asString(),
            new IsEqual<>("recipe")
        );
    }

    @Test
    void mutableIssuesUrlsForStoredFileAndOverwrites() throws Exception {
        final Storage asto = new InMemoryStorage();
        final Slice slice = ConanImmutableUploadTest.slice(asto, Optional.of(false));
        ConanImmutableUploadTest.put(slice, ConanImmutableUploadTest.uploadUrl(slice), "recipe");
        final URI again = ConanImmutableUploadTest.uploadUrl(slice);
        MatcherAssert.assertThat(
            "a PUT over the stored file is accepted",
            ConanImmutableUploadTest.put(slice, again, "changed").status(),
            new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the stored file holds the new content",
            asto.value(ConanImmutableUploadTest.FILE).join().asString(),
            new IsEqual<>("changed")
        );
    }

    @Test
    void legacyCtorOverwrites() throws Exception {
        final Storage asto = new InMemoryStorage();
        final Slice slice = ConanImmutableUploadTest.slice(asto, Optional.empty());
        ConanImmutableUploadTest.put(slice, ConanImmutableUploadTest.uploadUrl(slice), "recipe");
        MatcherAssert.assertThat(
            ConanImmutableUploadTest.put(
                slice, ConanImmutableUploadTest.uploadUrl(slice), "changed"
            ).status(),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    /**
     * Conan slice granting the test user WRITE.
     * @param immutable Immutability switch, empty for the legacy ctor
     * @return Slice
     */
    private static Slice slice(final Storage asto, final Optional<Boolean> immutable) {
        final Policy<PermissionCollection> policy = user -> {
            final Permissions perms = new Permissions();
            perms.add(
                new AdapterBasicPermission(ConanImmutableUploadTest.REPO, Action.Standard.WRITE)
            );
            return perms;
        };
        final ConanSlice.FakeAuthTokens tokens =
            new ConanSlice.FakeAuthTokens(ConanImmutableUploadTest.TOKEN, "alice");
        final Slice res;
        if (immutable.isPresent()) {
            res = new ConanSlice(
                asto, policy, (user, pass) -> Optional.of(new AuthUser(user, "test")),
                tokens, ConanImmutableUploadTest.tokenizer, ConanImmutableUploadTest.REPO,
                Optional.empty(), immutable.get()
            );
        } else {
            res = new ConanSlice(
                asto, policy, (user, pass) -> Optional.of(new AuthUser(user, "test")),
                tokens, ConanImmutableUploadTest.tokenizer, ConanImmutableUploadTest.REPO
            );
        }
        return res;
    }

    private static Response urls(final Slice slice) {
        return slice.response(
            new RequestLine(RqMethod.POST, "/v1/conans/zlib/1.2.13/_/_/upload_urls"),
            ConanImmutableUploadTest.headers(true),
            new Content.From("{\"conanfile.py\": 6}".getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    private static URI uploadUrl(final Slice slice) {
        return URI.create(
            Json.createReader(
                new StringReader(ConanImmutableUploadTest.urls(slice).body().asString())
            ).readObject().getString("conanfile.py")
        );
    }

    private static Response put(final Slice slice, final URI url, final String content) {
        return slice.response(
            new RequestLine(
                RqMethod.PUT, String.format("%s?%s", url.getRawPath(), url.getRawQuery())
            ),
            ConanImmutableUploadTest.headers(false),
            new Content.From(content.getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    private static Headers headers(final boolean auth) {
        final Headers headers = Headers.from(new Header("Host", "localhost"));
        if (auth) {
            headers.add(new Authorization.Bearer(ConanImmutableUploadTest.TOKEN));
        }
        return headers;
    }
}
