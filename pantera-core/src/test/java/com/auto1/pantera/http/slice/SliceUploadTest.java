/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.RepositoryEvents;
import io.reactivex.Flowable;
import org.cactoos.map.MapEntry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Test case for {@link SliceUpload}.
 */
public final class SliceUploadTest {

    @Test
    void uploadsKeyByPath() throws Exception {
        final Storage storage = new InMemoryStorage();
        final String hello = "Hello";
        final byte[] data = hello.getBytes(StandardCharsets.UTF_8);
        final String path = "uploads/file.txt";
        MatcherAssert.assertThat(
            "Wrong HTTP status returned",
            new SliceUpload(storage).response(
                new RequestLine("PUT", path, "HTTP/1.1"),
                Headers.from(
                    new MapEntry<>("Content-Size", Long.toString(data.length))
                ),
                new Content.From(
                    Flowable.just(ByteBuffer.wrap(data))
                )
            ).join(),
            new RsHasStatus(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            new String(
                new Remaining(
                    Flowable.fromPublisher(storage.value(new Key.From(path)).get()).toList()
                        .blockingGet().get(0)
                ).bytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(hello)
        );
    }

    @Test
    void logsEventOnUpload() {
        final byte[] data = "Hello".getBytes(StandardCharsets.UTF_8);
        final Queue<ArtifactEvent> queue = new LinkedList<>();
        MatcherAssert.assertThat(
            "Wrong HTTP status returned",
            new SliceUpload(new InMemoryStorage(), new RepositoryEvents("files", "my-repo", queue))
                .response(
                    new RequestLine("PUT", "uploads/file.txt", "HTTP/1.1"),
                    Headers.from("Content-Size", Long.toString(data.length)),
                    new Content.From(Flowable.just(ByteBuffer.wrap(data)))
                ).join(),
            new RsHasStatus(RsStatus.CREATED)
        );
        MatcherAssert.assertThat("Event was added to queue", queue.size() == 1);
    }
}
