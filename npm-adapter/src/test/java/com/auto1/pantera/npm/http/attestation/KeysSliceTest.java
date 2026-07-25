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
package com.auto1.pantera.npm.http.attestation;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.security.NpmSigningKeys;
import java.io.StringReader;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link KeysSlice} — {@code GET /-/npm/v1/keys}.
 */
final class KeysSliceTest {

    @Test
    void servesANonEmptyKeyringMatchingTheSigningKeypair() throws Exception {
        final Storage storage = new InMemoryStorage();
        final NpmSigningKeys keys = new NpmSigningKeys(storage);
        final NpmSigningKeys.SigningKeyPair pair = keys.keyPair().toCompletableFuture().join();

        final JsonObject body = Json.createReader(
            new StringReader(
                new KeysSlice(keys, "npm-local").response(
                    new RequestLine(RqMethod.GET, "/-/npm/v1/keys"), Headers.EMPTY, Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(body.getJsonArray("keys").size(), new IsEqual<>(1));
        final JsonObject key = body.getJsonArray("keys").getJsonObject(0);
        MatcherAssert.assertThat(key.getString("keyid"), new IsEqual<>(pair.keyId()));
        MatcherAssert.assertThat(key.getString("key"), new IsEqual<>(pair.publicKeyBase64()));
        MatcherAssert.assertThat(key.getString("keytype"), new IsEqual<>("ecdsa-sha2-nistp256"));
    }
}
