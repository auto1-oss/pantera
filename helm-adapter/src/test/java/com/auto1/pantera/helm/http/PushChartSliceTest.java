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
package com.auto1.pantera.helm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.helm.test.ContentOfIndex;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.cactoos.list.ListOf;
import org.cactoos.set.SetOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PushChartSlice}.
 * @since 0.4
 */
final class PushChartSliceTest {

    /**
     * Storage for tests.
     */
    private Storage storage;

    /**
     * Artifact events.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
    }

    @Test
    void shouldNotUpdateAfterUpload() {
        final String tgz = "ark-1.0.1.tgz";
        MatcherAssert.assertThat(
            "Wrong status, expected OK",
            new PushChartSlice(this.storage, Optional.of(this.events), "my-helm"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/?updateIndex=false"),
                Headers.EMPTY,
                new Content.From(new TestResource(tgz).asBytes())
            )
        );
        MatcherAssert.assertThat(
            "Index was generated",
            this.storage.list(Key.ROOT).join(),
            new IsEqual<>(new ListOf<Key>(new Key.From("ark", tgz)))
        );
        MatcherAssert.assertThat("No events were added to queue", this.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/?updateIndex=true", "/"})
    void shouldUpdateIndexAfterUpload(final String uri) {
        final String tgz = "ark-1.0.1.tgz";
        MatcherAssert.assertThat(
            "Wrong status, expected OK",
            new PushChartSlice(this.storage, Optional.of(this.events), "test-helm"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, uri),
                Headers.EMPTY,
                new Content.From(new TestResource(tgz).asBytes())
            )
        );
        MatcherAssert.assertThat(
            "Index was not updated",
            new ContentOfIndex(this.storage).index()
                .entries().keySet(),
            new IsEqual<>(new SetOf<>("ark"))
        );
        MatcherAssert.assertThat("One event was added to queue", this.events.size() == 1);
    }
}
