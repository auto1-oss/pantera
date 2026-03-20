/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AddArchiveSlice}.
 *
 * @since 0.4
 */
final class AddArchiveSliceTest {
    /**
     * Test storage.
     */
    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void acceptsAnyZipPath() {
        // Test that various .zip paths are accepted
        final String archive = "log-1.1.3.zip";
        final AstoRepository asto = new AstoRepository(
            this.storage, Optional.of("http://pantera:8080/")
        );
        MatcherAssert.assertThat(
            "Simple path works",
            new AddArchiveSlice(asto, "my-php"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, "/upload/package.zip"),
                Headers.EMPTY,
                new Content.From(new TestResource(archive).asBytes())
            )
        );
        MatcherAssert.assertThat(
            "Path with subdirectories works",
            new AddArchiveSlice(asto, "my-php"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, "/vendor/package/1.0.0/dist.zip"),
                Headers.EMPTY,
                new Content.From(new TestResource(archive).asBytes())
            )
        );
    }

    @Test
    void returnsBadRequestForNonZip() {
        MatcherAssert.assertThat(
            "Rejects unsupported archive formats",
            new AddArchiveSlice(new AstoRepository(this.storage), "my-php"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.BAD_REQUEST),
                new RequestLine(RqMethod.PUT, "/package.rar")
            )
        );
    }

    @Test
    void returnsBadRequestForPathTraversal() {
        MatcherAssert.assertThat(
            "Rejects path traversal attempts",
            new AddArchiveSlice(new AstoRepository(this.storage), "my-php"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.BAD_REQUEST),
                new RequestLine(RqMethod.PUT, "/../../../etc/passwd.zip")
            )
        );
    }

    @Test
    void returnsCreateStatus() {
        final String archive = "log-1.1.3.zip";
        final AstoRepository asto = new AstoRepository(
            this.storage, Optional.of("http://pantera:8080/")
        );
        final Queue<ArtifactEvent> queue = new LinkedList<>();
        MatcherAssert.assertThat(
            new AddArchiveSlice(asto, Optional.of(queue), "my-test-php"),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, String.format("/%s", archive)),
                Headers.EMPTY,
                new Content.From(new TestResource(archive).asBytes())
            )
        );
        MatcherAssert.assertThat("Queue has one item", queue.size() == 1);
    }
}
