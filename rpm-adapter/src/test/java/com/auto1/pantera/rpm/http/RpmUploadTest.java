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
                .response(new RequestLine("PUT", "/uploaded.rpm"), Headers.EMPTY,
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
}
