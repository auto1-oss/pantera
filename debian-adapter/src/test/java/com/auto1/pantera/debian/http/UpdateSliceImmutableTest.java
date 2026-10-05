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
package com.auto1.pantera.debian.http;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.debian.AstoGzArchive;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Upload to the key of a stored package against the per-repo
 * {@code immutable} flag.
 * @since 2.2.10
 */
final class UpdateSliceImmutableTest {

    /**
     * Repository settings.
     */
    private static final YamlMapping SETTINGS = Yaml.createYamlMappingBuilder()
        .add("Architectures", "amd64")
        .add("Components", "main").build();

    /**
     * Package fixture.
     */
    private static final String DEB = "aglfn_1.7-3_amd64.deb";

    /**
     * Key the fixture is uploaded to.
     */
    private static final Key KEY = new Key.From("main", UpdateSliceImmutableTest.DEB);

    /**
     * Packages index of the amd64 architecture.
     */
    private static final Key PACKAGES = new Key.From("dists/my_repo/main/binary-amd64/Packages.gz");

    /**
     * Release index.
     */
    private static final Key RELEASE = new Key.From("dists/my_repo/Release");

    /**
     * InRelease index.
     */
    private static final Key INRELEASE = new Key.From("dists/my_repo/InRelease");

    /**
     * Test storage.
     */
    private Storage asto;

    /**
     * Artifact events queue.
     */
    private Queue<ArtifactEvent> events;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.asto.save(UpdateSliceImmutableTest.RELEASE, Content.EMPTY).join();
        this.asto.save(UpdateSliceImmutableTest.INRELEASE, Content.EMPTY).join();
    }

    @Test
    void immutableRefusesReuploadToStoredKey() throws IOException {
        final byte[] deb = new TestResource(UpdateSliceImmutableTest.DEB).asBytes();
        MatcherAssert.assertThat(
            "first upload is accepted",
            this.upload(true, deb).status(), new IsEqual<>(RsStatus.OK)
        );
        final String index = new AstoGzArchive(this.asto).unpack(UpdateSliceImmutableTest.PACKAGES);
        final Response second = this.upload(true, deb);
        MatcherAssert.assertThat(
            "re-upload of the same key is refused", second.status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the client is told why", second.body().asString(),
            new StringContains("already exists")
        );
        MatcherAssert.assertThat(
            "Packages index is untouched",
            new AstoGzArchive(this.asto).unpack(UpdateSliceImmutableTest.PACKAGES),
            new IsEqual<>(index)
        );
        MatcherAssert.assertThat(
            "the stored package is still there",
            this.asto.value(UpdateSliceImmutableTest.KEY).join().asBytes(), new IsEqual<>(deb)
        );
        MatcherAssert.assertThat(
            "only the first upload produced an event", this.events.size(), new IsEqual<>(1)
        );
    }

    @Test
    void immutableRefusesBeforeSavingAnything() {
        final byte[] stored = "stored".getBytes(StandardCharsets.UTF_8);
        this.asto.save(UpdateSliceImmutableTest.KEY, new Content.From(stored)).join();
        MatcherAssert.assertThat(
            "upload to a stored key is refused",
            this.upload(true, new TestResource(UpdateSliceImmutableTest.DEB).asBytes()).status(),
            new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the stored file is untouched",
            this.asto.value(UpdateSliceImmutableTest.KEY).join().asBytes(), new IsEqual<>(stored)
        );
        MatcherAssert.assertThat(
            "nothing else was written (no index, no temporary upload)",
            new HashSet<>(this.asto.list(Key.ROOT).join()),
            new IsEqual<>(
                Set.of(
                    UpdateSliceImmutableTest.KEY, UpdateSliceImmutableTest.RELEASE,
                    UpdateSliceImmutableTest.INRELEASE
                )
            )
        );
    }

    @Test
    void mutableOverwriteKeepsPackageAndOneIndexRecord() throws IOException {
        final byte[] deb = new TestResource(UpdateSliceImmutableTest.DEB).asBytes();
        this.upload(false, deb);
        MatcherAssert.assertThat(
            "re-upload of the same key is accepted",
            this.upload(false, deb).status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the overwritten package is stored (not removed as a replaced duplicate)",
            this.asto.exists(UpdateSliceImmutableTest.KEY).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Packages.gz holds exactly one record for the package",
            UpdateSliceImmutableTest.count(
                new AstoGzArchive(this.asto).unpack(UpdateSliceImmutableTest.PACKAGES),
                "Package: aglfn"
            ),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "no temporary upload is left",
            this.asto.list(new Key.From(".upload")).join().isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void mutableFailedOverwriteKeepsStoredPackage() throws IOException {
        final byte[] deb = new TestResource(UpdateSliceImmutableTest.DEB).asBytes();
        this.upload(false, deb);
        final String index = new AstoGzArchive(this.asto).unpack(UpdateSliceImmutableTest.PACKAGES);
        MatcherAssert.assertThat(
            "a corrupted overwrite is a client error",
            this.upload(false, "abc123".getBytes(StandardCharsets.UTF_8)).status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the stored package survives the rejected overwrite",
            this.asto.value(UpdateSliceImmutableTest.KEY).join().asBytes(), new IsEqual<>(deb)
        );
        MatcherAssert.assertThat(
            "Packages index is untouched",
            new AstoGzArchive(this.asto).unpack(UpdateSliceImmutableTest.PACKAGES),
            new IsEqual<>(index)
        );
        MatcherAssert.assertThat(
            "no temporary upload is left",
            this.asto.list(new Key.From(".upload")).join().isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    void legacyCtorOverwrites() {
        final byte[] deb = new TestResource(UpdateSliceImmutableTest.DEB).asBytes();
        final UpdateSlice slice = new UpdateSlice(
            this.asto,
            new Config.FromYaml("my_repo", UpdateSliceImmutableTest.SETTINGS, new InMemoryStorage()),
            Optional.of(this.events)
        );
        slice.response(
            new RequestLine(RqMethod.PUT, "/main/" + UpdateSliceImmutableTest.DEB),
            Headers.EMPTY, new Content.From(deb)
        ).join();
        MatcherAssert.assertThat(
            slice.response(
                new RequestLine(RqMethod.PUT, "/main/" + UpdateSliceImmutableTest.DEB),
                Headers.EMPTY, new Content.From(deb)
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    /**
     * Upload a body to the fixture's key.
     * @param immutable Immutability switch
     * @param bytes Body
     * @return Response
     */
    private Response upload(final boolean immutable, final byte[] bytes) {
        return new UpdateSlice(
            this.asto,
            new Config.FromYaml("my_repo", UpdateSliceImmutableTest.SETTINGS, new InMemoryStorage()),
            Optional.of(this.events), SyncArtifactIndexer.NOOP, immutable
        ).response(
            new RequestLine(RqMethod.PUT, "/main/" + UpdateSliceImmutableTest.DEB),
            Headers.EMPTY,
            new Content.From(bytes)
        ).join();
    }

    /**
     * Count occurrences of a text.
     * @param text Text
     * @param what What to count
     * @return Occurrences
     */
    private static int count(final String text, final String what) {
        final Matcher matcher = Pattern.compile(Pattern.quote(what)).matcher(text);
        int res = 0;
        while (matcher.find()) {
            res += 1;
        }
        return res;
    }
}
