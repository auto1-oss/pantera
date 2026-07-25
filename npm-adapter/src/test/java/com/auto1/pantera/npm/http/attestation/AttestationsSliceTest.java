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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AttestationsSlice} — {@code GET /-/npm/v1/attestations/<spec>}.
 */
final class AttestationsSliceTest {

    @Test
    void servesAStoredBundleWrappedInAttestationsEnvelope() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AttestationStore store = new AttestationStore(storage);
        store.store(
            "@scope/pkg", "1.0.0",
            "{\"predicateType\":\"https://slsa.dev/provenance/v1\"}".getBytes(StandardCharsets.UTF_8)
        ).join();
        final JsonObject body = Json.createReader(
            new StringReader(
                new AttestationsSlice(store, "npm-local").response(
                    new RequestLine(RqMethod.GET, "/-/npm/v1/attestations/@scope%2Fpkg@1.0.0"),
                    Headers.EMPTY, Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(
            "the response carries the top-level attestations array",
            body.containsKey("attestations"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(body.getJsonArray("attestations").size(), new IsEqual<>(1));
    }

    @Test
    void returnsNotFoundWhenNothingWasStored() {
        final AttestationStore store = new AttestationStore(new InMemoryStorage());
        MatcherAssert.assertThat(
            new AttestationsSlice(store, "npm-local").response(
                new RequestLine(RqMethod.GET, "/-/npm/v1/attestations/never-published@1.0.0"),
                Headers.EMPTY, Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }
}
