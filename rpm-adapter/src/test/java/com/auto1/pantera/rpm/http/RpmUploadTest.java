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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.ParkedStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.TestRpm;
import com.auto1.pantera.scheduling.ArtifactEvent;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test for {@link RpmUpload}.
 */
public final class RpmUploadTest {

    /**
     * Test storage.
     */
    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void canUploadArtifact() throws Exception {
        final byte[] content = Files.readAllBytes(new TestRpm.Abc().path());
        final Optional<Queue<ArtifactEvent>> events = Optional.of(new LinkedList<>());
        Assertions.assertEquals(RsStatus.ACCEPTED,
            new RpmUpload(this.storage, new RepoConfig.Simple(), events)
                .response(
                    new RequestLine("PUT", "/uploaded.rpm"),
                    Headers.from(
                        new com.auto1.pantera.http.headers.Header(com.auto1.pantera.http.slice.EcsLoggingSlice.CTX_TRACE_ID_HEADER, "trace-rpm"),
                        new com.auto1.pantera.http.headers.Header(com.auto1.pantera.http.slice.EcsLoggingSlice.CTX_CLIENT_IP_HEADER, "10.0.0.1")
                    ),
                    new Content.From(content)
            ).join().status()
        );
        MatcherAssert.assertThat(
            "Content saved to storage",
            new BlockingStorage(this.storage).value(new Key.From("uploaded.rpm")),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat(
            "Metadata updated",
            new BlockingStorage(this.storage).list(new Key.From("repodata")).isEmpty(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat("Events queue has one item", events.get().size() == 1);
        MatcherAssert.assertThat(
            "B36: the publish event carries the request trace.id",
            events.get().peek().traceId(), new org.hamcrest.core.IsEqual<>("trace-rpm")
        );
        MatcherAssert.assertThat(
            "B36: the publish event carries the request client.ip",
            events.get().peek().clientIp(), new org.hamcrest.core.IsEqual<>("10.0.0.1")
        );
    }

    @Test
    void canReplaceArtifact() throws Exception {
        final byte[] content = Files.readAllBytes(new TestRpm.Abc().path());
        final Key key = new Key.From("replaced.rpm");
        new BlockingStorage(this.storage).save(key, "uploaded package".getBytes());
        Assertions.assertEquals(RsStatus.ACCEPTED,
            new RpmUpload(this.storage, new RepoConfig.Simple(), Optional.empty()).response(
                new RequestLine("PUT", "/replaced.rpm?override=true"),
                Headers.EMPTY,
                new Content.From(content)
            ).join().status()
        );
        MatcherAssert.assertThat(
            new BlockingStorage(this.storage).value(key),
            new IsEqual<>(content)
        );
    }

    @Test
    void dontReplaceArtifact() throws Exception {
        final byte[] content =
            "first package content".getBytes(StandardCharsets.UTF_8);
        final Key key = new Key.From("not-replaced.rpm");
        final Optional<Queue<ArtifactEvent>> events = Optional.of(new LinkedList<>());
        new BlockingStorage(this.storage).save(key, content);
        Assertions.assertEquals(RsStatus.CONFLICT,
            new RpmUpload(this.storage, new RepoConfig.Simple(), events).response(
                new RequestLine("PUT", "/not-replaced.rpm"),
                Headers.EMPTY,
                new Content.From("second package content".getBytes())
            ).join().status()
        );
        MatcherAssert.assertThat(
            new BlockingStorage(this.storage).value(key),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat("Events queue is empty", events.get().isEmpty());
    }

    @Test
    void immutableRepoRefusesOverrideFlag() throws Exception {
        final byte[] content = "first package content".getBytes(StandardCharsets.UTF_8);
        final Key key = new Key.From("immutable.rpm");
        final Optional<Queue<ArtifactEvent>> events = Optional.of(new LinkedList<>());
        new BlockingStorage(this.storage).save(key, content);
        MatcherAssert.assertThat(
            "immutable: ?override=true is refused with 409",
            this.upload(true, events, "/immutable.rpm?override=true",
                Files.readAllBytes(new TestRpm.Abc().path())).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "immutable: the stored package is untouched",
            new BlockingStorage(this.storage).value(key),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat(
            "immutable: nothing is staged for indexing",
            new BlockingStorage(this.storage).list(RpmUpload.TO_ADD).isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "immutable: nothing is published", events.get().isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void concurrentUploadsOfAPackageAcrossInstancesAreSerialised() throws Exception {
        // Two upload fronts over one storage stand in for two instances
        // sharing it. The first upload is parked inside its staging write
        // after its check passed; the second must wait for it in storage,
        // see the staged package and be refused. Without the storage lock
        // both pass the check and both stage, last one wins.
        final ParkedStorage parked = new ParkedStorage(new InMemoryStorage());
        final byte[] abc = Files.readAllBytes(new TestRpm.Abc().path());
        final byte[] time = Files.readAllBytes(new TestRpm.Time().path());
        final CompletableFuture<RsStatus> first = CompletableFuture.supplyAsync(
            () -> RpmUploadTest.upload(parked, "/race.rpm", abc).status()
        );
        parked.arrived().get(10, TimeUnit.SECONDS);
        final CompletableFuture<RsStatus> second = CompletableFuture.supplyAsync(
            () -> RpmUploadTest.upload(parked, "/race.rpm", time).status()
        );
        parked.contender().get(10, TimeUnit.SECONDS);
        parked.release();
        MatcherAssert.assertThat(
            "the first upload is accepted",
            first.get(60, TimeUnit.SECONDS), new IsEqual<>(RsStatus.ACCEPTED)
        );
        MatcherAssert.assertThat(
            "the second upload is refused",
            second.get(60, TimeUnit.SECONDS), new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the first package is what is stored",
            new BlockingStorage(parked).value(new Key.From("race.rpm")), new IsEqual<>(abc)
        );
    }

    @Test
    void conflictDrainsRefusedBody() throws Exception {
        final Key key = new Key.From("drained.rpm");
        new BlockingStorage(this.storage).save(
            key, "first package content".getBytes(StandardCharsets.UTF_8)
        );
        final java.util.concurrent.atomic.AtomicBoolean drained =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final Content body = new Content.From(
            io.reactivex.Flowable.just(
                java.nio.ByteBuffer.wrap("second".getBytes(StandardCharsets.UTF_8))
            ).doOnComplete(() -> drained.set(true))
        );
        MatcherAssert.assertThat(
            "the re-upload is refused with 409",
            new RpmUpload(
                this.storage, new RepoConfig.Simple(), Optional.empty(),
                com.auto1.pantera.index.SyncArtifactIndexer.NOOP, true
            ).response(new RequestLine("PUT", "/drained.rpm"), Headers.EMPTY, body)
                .join().status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the refused request body is consumed",
            drained.get(),
            new IsEqual<>(true)
        );
    }

    @Test
    void immutableRepoRefusesPlainReupload() throws Exception {
        final byte[] content = "first package content".getBytes(StandardCharsets.UTF_8);
        final Key key = new Key.From("immutable.rpm");
        new BlockingStorage(this.storage).save(key, content);
        MatcherAssert.assertThat(
            "immutable: a plain re-upload is refused with 409",
            this.upload(true, Optional.empty(), "/immutable.rpm",
                Files.readAllBytes(new TestRpm.Abc().path())).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "immutable: the stored package is untouched",
            new BlockingStorage(this.storage).value(key),
            new IsEqual<>(content)
        );
    }

    @Test
    void mutableRepoOverridesWithFlagAndKeepsOneMetadataEntry(
        @org.junit.jupiter.api.io.TempDir final java.nio.file.Path temp
    ) throws Exception {
        final byte[] content = Files.readAllBytes(new TestRpm.Abc().path());
        final Optional<Queue<ArtifactEvent>> events = Optional.of(new LinkedList<>());
        MatcherAssert.assertThat(
            "mutable: the first upload is accepted",
            this.upload(false, events, "/mutable.rpm", content).status(),
            new IsEqual<>(RsStatus.ACCEPTED)
        );
        MatcherAssert.assertThat(
            "mutable: ?override=true replaces the package",
            this.upload(false, events, "/mutable.rpm?override=true", content).status(),
            new IsEqual<>(RsStatus.ACCEPTED)
        );
        MatcherAssert.assertThat(
            "mutable: the stored package is the new upload",
            new BlockingStorage(this.storage).value(new Key.From("mutable.rpm")),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat(
            "mutable: the repodata lists the overwritten package once",
            this.storage,
            new com.auto1.pantera.rpm.hm.StorageHasMetadata(
                1, new RepoConfig.Simple().filelists(), temp
            )
        );
        MatcherAssert.assertThat(
            "mutable: both uploads are published", events.get().size(), new IsEqual<>(2)
        );
    }

    @Test
    void mutableRepoWithoutFlagConflicts() throws Exception {
        final byte[] content = "first package content".getBytes(StandardCharsets.UTF_8);
        final Key key = new Key.From("mutable.rpm");
        new BlockingStorage(this.storage).save(key, content);
        MatcherAssert.assertThat(
            "mutable: a re-upload without ?override=true is refused with 409",
            this.upload(false, Optional.empty(), "/mutable.rpm",
                Files.readAllBytes(new TestRpm.Abc().path())).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "mutable: the stored package is untouched",
            new BlockingStorage(this.storage).value(key),
            new IsEqual<>(content)
        );
    }

    @Test
    void skipsUpdateWhenParamSkipIsTrue() throws Exception {
        final byte[] content = Files.readAllBytes(new TestRpm.Abc().path());
        Assertions.assertEquals(RsStatus.ACCEPTED,
            new RpmUpload(this.storage, new RepoConfig.Simple(), Optional.empty()).response(
                new RequestLine("PUT", "/my-package.rpm?skip_update=true"),
                Headers.EMPTY,
                new Content.From(content)
            ).join().status()
        );
        MatcherAssert.assertThat(
            "Content saved to storage",
            new BlockingStorage(this.storage)
                .value(new Key.From(RpmUpload.TO_ADD, "my-package.rpm")),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat(
            "Metadata not updated",
            new BlockingStorage(this.storage).list(new Key.From("repodata")).isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void skipsUpdateIfModeIsCron() throws Exception {
        final byte[] content = Files.readAllBytes(new TestRpm.Abc().path());
        Assertions.assertEquals(RsStatus.ACCEPTED,
            new RpmUpload(
                this.storage, new RepoConfig.Simple(RepoConfig.UpdateMode.CRON), Optional.empty()
            ).response(
                new RequestLine("PUT", "/abc-package.rpm"),
                Headers.EMPTY,
                new Content.From(content)
            ).join().status()
        );
        MatcherAssert.assertThat(
            "Content saved to temp location",
            new BlockingStorage(this.storage)
                .value(new Key.From(RpmUpload.TO_ADD, "abc-package.rpm")),
            new IsEqual<>(content)
        );
        MatcherAssert.assertThat(
            "Metadata not updated",
            new BlockingStorage(this.storage).list(new Key.From("repodata")).isEmpty(),
            new IsEqual<>(true)
        );
    }

    private static com.auto1.pantera.http.Response upload(
        final Storage storage, final String path, final byte[] body
    ) {
        return new RpmUpload(
            storage, new RepoConfig.Simple(), Optional.empty(),
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP, true
        ).response(new RequestLine("PUT", path), Headers.EMPTY, new Content.From(body)).join();
    }

    private com.auto1.pantera.http.Response upload(
        final boolean immutable, final Optional<Queue<ArtifactEvent>> events,
        final String path, final byte[] body
    ) {
        return new RpmUpload(
            this.storage, new RepoConfig.Simple(), events,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP, immutable
        ).response(new RequestLine("PUT", path), Headers.EMPTY, new Content.From(body)).join();
    }
}
