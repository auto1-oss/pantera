/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.TrimPathSlice;
import com.auto1.pantera.npm.Publish;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;

/**
 * UploadSliceTest.
 */
public final class UploadSliceTest {

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Test artifact events.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Npm publish implementation.
     */
    private Publish publish;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.events = new LinkedList<>();
        this.publish = new CliPublish(this.storage);
    }

    @Test
    void uploadsFileToRemote() throws Exception {
        final Slice slice = new TrimPathSlice(
            new UploadSlice(
                this.publish, this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ), "ctx"
        );
        final String json = Json.createObjectBuilder()
            .add("name", "@hello/simple-npm-project")
            .add("version", "1.0.1")
            .add("_id", "1.0.1")
            .add("readme", "Some text")
            .add("versions", Json.createObjectBuilder())
            .add("dist-tags", Json.createObjectBuilder())
            .add("_attachments", Json.createObjectBuilder())
            .build().toString();
        Assertions.assertEquals(
            RsStatus.OK,
            slice.response(
                RequestLine.from("PUT /ctx/package HTTP/1.1"),
                Headers.EMPTY,
                new Content.From(json.getBytes())
            ).join().status()
        );
        
        // Generate meta.json from per-version files
        final com.auto1.pantera.asto.Key packageKey = new KeyFromPath("package");
        new com.auto1.pantera.npm.PerVersionLayout(this.storage).generateMetaJson(packageKey)
            .thenCompose(meta -> this.storage.save(
                new KeyFromPath("package/meta.json"),
                new Content.From(meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        
        Assertions.assertTrue(
            this.storage.exists(new KeyFromPath("package/meta.json")).get()
        );
        Assertions.assertEquals(1, this.events.size());
    }

    @Test
    void shouldFailForBadRequest() {
        final Slice slice = new TrimPathSlice(
            new UploadSlice(
                this.publish, this.storage, Optional.of(this.events), UnpublishPutSliceTest.REPO
            ),
            "my-repo"
        );
        Assertions.assertThrows(
            Exception.class,
            () -> slice.response(
                RequestLine.from("PUT /my-repo/my-package HTTP/1.1"),
                Headers.EMPTY,
                new Content.From("{}".getBytes())
            ).join()
        );
        Assertions.assertTrue(this.events.isEmpty());
    }
}
