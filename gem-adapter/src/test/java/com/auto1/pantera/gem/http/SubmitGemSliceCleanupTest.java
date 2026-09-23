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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A failed gem upload must not leave its temporary {@code gems/<uuid>.gem}
 * behind: it would be served, and indexed by the next upload.
 *
 * @since 2.2.9
 */
final class SubmitGemSliceCleanupTest {

    @Test
    void removesTemporaryUploadWhenIndexingFails() {
        final Storage storage = new InMemoryStorage();
        try {
            new SubmitGemSlice(storage, Optional.empty(), "gems").response(
                new RequestLine(RqMethod.POST, "/api/v1/gems"),
                Headers.EMPTY,
                new Content.From("not a gem".getBytes(StandardCharsets.UTF_8))
            ).join();
        } catch (final CompletionException failed) {
            // The upload itself is expected to fail; only the leftover matters.
        }
        MatcherAssert.assertThat(
            storage.list(new Key.From("gems")).join().isEmpty(),
            new IsEqual<>(true)
        );
    }
}
