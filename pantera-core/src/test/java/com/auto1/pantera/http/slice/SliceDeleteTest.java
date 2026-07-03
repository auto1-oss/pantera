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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.RepositoryEvents;
import java.util.LinkedList;
import java.util.Queue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SliceDelete}.
 *
 * @since 0.10
 */
final class SliceDeleteTest {

    /**
     * Storage.
     */
    private final Storage storage = new InMemoryStorage();

    @Test
    void deleteCorrectEntry() throws Exception {
        final Key key = new Key.From("foo");
        final Key another = new Key.From("bar");
        new BlockingStorage(this.storage).save(key, "anything".getBytes());
        new BlockingStorage(this.storage).save(another, "another".getBytes());
        MatcherAssert.assertThat(
            "Didn't respond with NO_CONTENT status",
            new SliceDelete(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NO_CONTENT),
                new RequestLine(RqMethod.DELETE, "/foo")
            )
        );
        MatcherAssert.assertThat(
            "Didn't delete from storage",
            new BlockingStorage(this.storage).exists(key),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Deleted another key",
            new BlockingStorage(this.storage).exists(another),
            new IsEqual<>(true)
        );
    }

    @Test
    void returnsNotFound() {
        MatcherAssert.assertThat(
            new SliceDelete(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.DELETE, "/bar")
            )
        );
    }

    @Test
    void logsEventOnDelete() {
        final Key key = new Key.From("foo");
        final Key another = new Key.From("bar");
        new BlockingStorage(this.storage).save(key, "anything".getBytes());
        new BlockingStorage(this.storage).save(another, "another".getBytes());
        final Queue<ArtifactEvent> queue = new LinkedList<>();
        MatcherAssert.assertThat(
            "Didn't respond with NO_CONTENT status",
            new SliceDelete(this.storage, new RepositoryEvents("files", "my-repo", queue)),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NO_CONTENT),
                new RequestLine(RqMethod.DELETE, "/foo")
            )
        );
        MatcherAssert.assertThat("Event was added to queue", queue.size() == 1);
    }
}

