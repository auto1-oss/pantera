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
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Re-push of a stored gem version against the per-repo {@code immutable} flag.
 *
 * @since 2.2.10
 */
final class SubmitGemSliceImmutableTest {

    /**
     * Gem fixture.
     */
    private static final String GEM = "builder-3.2.4.gem";

    /**
     * Key the fixture is stored under.
     */
    private static final Key STORED = new Key.From("gems", SubmitGemSliceImmutableTest.GEM);

    @Test
    void immutableRefusesRepushOfStoredVersion() {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final SubmitGemSlice slice = new SubmitGemSlice(
            storage, Optional.of(events), "gems", SyncArtifactIndexer.NOOP, true
        );
        MatcherAssert.assertThat(
            "first push is accepted",
            SubmitGemSliceImmutableTest.push(slice).status(),
            new IsEqual<>(RsStatus.CREATED)
        );
        final byte[] specs = storage.value(new Key.From("specs.4.8")).join().asBytes();
        final Response second = SubmitGemSliceImmutableTest.push(slice);
        MatcherAssert.assertThat(
            "re-push of the same version (identical bytes) is refused",
            second.status(), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the client is told why",
            second.body().asString(), new StringContains("Repushing of gem versions")
        );
        MatcherAssert.assertThat(
            "stored gem is untouched",
            Arrays.equals(
                storage.value(SubmitGemSliceImmutableTest.STORED).join().asBytes(),
                new TestResource(SubmitGemSliceImmutableTest.GEM).asBytes()
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "specs index is untouched",
            Arrays.equals(
                storage.value(new Key.From("specs.4.8")).join().asBytes(), specs
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no temporary upload is left behind",
            storage.list(new Key.From("gems")).join(),
            new IsEqual<>(List.of(SubmitGemSliceImmutableTest.STORED))
        );
        MatcherAssert.assertThat(
            "only the first push produced an artifact event",
            events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void mutableOverwritesStoredVersion() {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final SubmitGemSlice slice = new SubmitGemSlice(
            storage, Optional.of(events), "gems", SyncArtifactIndexer.NOOP, false
        );
        SubmitGemSliceImmutableTest.push(slice);
        MatcherAssert.assertThat(
            "re-push of the same version is accepted",
            SubmitGemSliceImmutableTest.push(slice).status(),
            new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "the gem is stored once, no temporary upload left",
            storage.list(new Key.From("gems")).join(),
            new IsEqual<>(List.of(SubmitGemSliceImmutableTest.STORED))
        );
        MatcherAssert.assertThat(
            "the specs index was regenerated",
            storage.exists(new Key.From("specs.4.8")).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "both pushes produced an artifact event",
            events.size(), new IsEqual<>(2)
        );
    }

    @Test
    void legacyCtorOverwrites() {
        final SubmitGemSlice slice = new SubmitGemSlice(
            new InMemoryStorage(), Optional.empty(), "gems"
        );
        SubmitGemSliceImmutableTest.push(slice);
        MatcherAssert.assertThat(
            SubmitGemSliceImmutableTest.push(slice).status(),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    /**
     * Push the gem fixture.
     * @param slice Upload slice
     * @return Response
     */
    private static Response push(final SubmitGemSlice slice) {
        return slice.response(
            new RequestLine(RqMethod.POST, "/api/v1/gems"),
            Headers.EMPTY,
            new Content.From(new TestResource(SubmitGemSliceImmutableTest.GEM).asBytes())
        ).join();
    }
}
