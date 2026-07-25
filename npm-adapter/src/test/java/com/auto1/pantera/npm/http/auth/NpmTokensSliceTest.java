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
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.repository.StorageTokenRepository;
import java.io.StringReader;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link NpmTokensSlice} — {@code npm token list/create/revoke}.
 */
final class NpmTokensSliceTest {

    @Test
    void jwtOnlyModeAnswersHonestlyNotSilently() {
        final Response response = new NpmTokensSlice().response(
            new RequestLine(RqMethod.GET, "/-/npm/v1/tokens"),
            loginHeaders("alice"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "JWT-only mode returns an honest 501, never a silently-empty 200 list",
            response.status(),
            new IsEqual<>(RsStatus.NOT_IMPLEMENTED)
        );
    }

    @Test
    void createThenListShowsTheNewTokenMasked() throws Exception {
        final Storage storage = new InMemoryStorage();
        final NpmTokensSlice slice = new NpmTokensSlice(new StorageTokenRepository(storage));

        final Response created = slice.response(
            new RequestLine(RqMethod.POST, "/-/npm/v1/tokens"),
            loginHeaders("alice"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(created.status(), new IsEqual<>(RsStatus.CREATED));
        final JsonObject createdBody = Json.createReader(
            new StringReader(created.body().asString())
        ).readObject();
        final String rawToken = createdBody.getString("token");
        MatcherAssert.assertThat("a usable raw token is returned once", rawToken.isEmpty(), new IsEqual<>(false));

        final Response listed = slice.response(
            new RequestLine(RqMethod.GET, "/-/npm/v1/tokens"),
            loginHeaders("alice"), Content.EMPTY
        ).join();
        final JsonObject listedBody = Json.createReader(
            new StringReader(listed.body().asString())
        ).readObject();
        MatcherAssert.assertThat(listedBody.getInt("total"), new IsEqual<>(1));
        final String maskedToken = listedBody.getJsonArray("objects").getJsonObject(0).getString("token");
        MatcherAssert.assertThat(
            "the full raw secret is never re-exposed on list",
            maskedToken.equals(rawToken),
            new IsEqual<>(false)
        );
    }

    @Test
    void revokeRemovesTheTokenFromASubsequentList() throws Exception {
        final Storage storage = new InMemoryStorage();
        final NpmTokensSlice slice = new NpmTokensSlice(new StorageTokenRepository(storage));
        final JsonObject created = Json.createReader(
            new StringReader(
                slice.response(
                    new RequestLine(RqMethod.POST, "/-/npm/v1/tokens"),
                    loginHeaders("alice"), Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        final String id = created.getString("key");

        final Response revoked = slice.response(
            new RequestLine(RqMethod.DELETE, "/-/npm/v1/tokens/token/" + id),
            loginHeaders("alice"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(revoked.status(), new IsEqual<>(RsStatus.OK));

        final JsonObject listedAfter = Json.createReader(
            new StringReader(
                slice.response(
                    new RequestLine(RqMethod.GET, "/-/npm/v1/tokens"),
                    loginHeaders("alice"), Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(
            "the revoked token no longer appears in the list",
            listedAfter.getInt("total"),
            new IsEqual<>(0)
        );
    }

    @Test
    void revokeRejectsATokenBelongingToAnotherUser() throws Exception {
        final Storage storage = new InMemoryStorage();
        final NpmTokensSlice slice = new NpmTokensSlice(new StorageTokenRepository(storage));
        final JsonObject created = Json.createReader(
            new StringReader(
                slice.response(
                    new RequestLine(RqMethod.POST, "/-/npm/v1/tokens"),
                    loginHeaders("alice"), Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        final String id = created.getString("key");

        final Response revoked = slice.response(
            new RequestLine(RqMethod.DELETE, "/-/npm/v1/tokens/token/" + id),
            loginHeaders("mallory"), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a caller cannot revoke another user's token by guessing its id",
            revoked.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    private static Headers loginHeaders(final String username) {
        final Headers headers = new Headers();
        headers.add(AuthzSlice.LOGIN_HDR, username);
        return headers;
    }
}
