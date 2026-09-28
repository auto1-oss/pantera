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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.TestRpm;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A GET of {@code repodata/repomd.xml} on a repository without metadata
 * creates it empty. That must never race an upload writing the metadata,
 * another GET doing the same, or another node that got there first.
 *
 * @since 2.2.9
 */
final class EmptyRepodataRaceTest {

    /**
     * Repository metadata index.
     */
    private static final Key REPOMD = new Key.From("repodata", "repomd.xml");

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void getDuringFirstUploadServesTheUploadedPackage() throws Exception {
        final CompletableFuture<Void> reached = new CompletableFuture<>();
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final RpmSlice slice = EmptyRepodataRaceTest.slice(
            new GatedAddList(new InMemoryStorage(), reached, gate), events
        );
        final CompletableFuture<Response> put = slice.response(
            new RequestLine(RqMethod.PUT, "/abc-1.01-26.git20200127.fc32.ppc64le.rpm"),
            EmptyRepodataRaceTest.auth(),
            new Content.From(Files.readAllBytes(new TestRpm.Abc().path()))
        );
        // The upload has stored the package and is now writing metadata.
        reached.get(30, TimeUnit.SECONDS);
        final CompletableFuture<Response> get = slice.response(
            new RequestLine(RqMethod.GET, "/repodata/repomd.xml"),
            EmptyRepodataRaceTest.auth(), Content.EMPTY
        );
        gate.complete(null);
        MatcherAssert.assertThat(
            "upload accepted", put.join().status(), new IsEqual<>(RsStatus.ACCEPTED)
        );
        MatcherAssert.assertThat(
            "repomd served", get.join().status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "package listed in primary",
            EmptyRepodataRaceTest.primary(slice),
            new StringContains("<name>abc</name>")
        );
        MatcherAssert.assertThat(
            "upload indexed",
            events.stream().map(ArtifactEvent::artifactName).collect(Collectors.toList())
                .toString(),
            new StringContains("abc")
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void emptyMetadataNeverReplacesMetadataWrittenByUpload() {
        final Storage asto = new InMemoryStorage();
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final CompletableFuture<Void> upload = new CompletableFuture<>();
        // The upload holds the repository's metadata queue while it writes.
        new RepodataQueue(asto).run(() -> upload);
        final CompletableFuture<Void> checked = new CompletableFuture<>();
        final CompletableFuture<Response> get = EmptyRepodataRaceTest.slice(
            new GatedExists(asto, gate, checked), new ConcurrentLinkedQueue<>()
        ).response(
            new RequestLine(RqMethod.GET, "/repodata/repomd.xml"),
            EmptyRepodataRaceTest.auth(), Content.EMPTY
        );
        // The read has seen the metadata missing; the upload now writes it.
        checked.join();
        asto.save(
            EmptyRepodataRaceTest.REPOMD,
            new Content.From("<repomd uploaded/>".getBytes(StandardCharsets.UTF_8))
        ).join();
        gate.complete(null);
        upload.complete(null);
        MatcherAssert.assertThat(
            get.join().body().asString(), new IsEqual<>("<repomd uploaded/>")
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentGetsOfEmptyRepositoryBothSucceed() {
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        final RpmSlice slice = EmptyRepodataRaceTest.slice(
            new GatedExists(new InMemoryStorage(), gate, new CompletableFuture<>()),
            new ConcurrentLinkedQueue<>()
        );
        final CompletableFuture<Response> first = slice.response(
            new RequestLine(RqMethod.GET, "/repodata/repomd.xml"),
            EmptyRepodataRaceTest.auth(), Content.EMPTY
        );
        final CompletableFuture<Response> second = slice.response(
            new RequestLine(RqMethod.GET, "/repodata/repomd.xml"),
            EmptyRepodataRaceTest.auth(), Content.EMPTY
        );
        gate.complete(null);
        MatcherAssert.assertThat(
            "first served", first.join().status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "second served", second.join().status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void lostRaceToAnotherNodeServesItsMetadata() {
        final RpmSlice slice = EmptyRepodataRaceTest.slice(
            new OtherNodeWins(new InMemoryStorage()), new ConcurrentLinkedQueue<>()
        );
        final Response rsp = slice.response(
            new RequestLine(RqMethod.GET, "/repodata/repomd.xml"),
            EmptyRepodataRaceTest.auth(), Content.EMPTY
        ).join();
        MatcherAssert.assertThat("status", rsp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "other node's repomd", rsp.body().asString(), new IsEqual<>("<repomd other/>")
        );
    }

    private static RpmSlice slice(final Storage asto, final Queue<ArtifactEvent> events) {
        return new RpmSlice(
            asto, Policy.FREE,
            (name, pass) -> Optional.of(new AuthUser(name, "test")),
            new RepoConfig.Simple(),
            Optional.of(events)
        );
    }

    private static Headers auth() {
        return Headers.from(new Authorization.Basic("alice", "pw"));
    }

    private static String primary(final RpmSlice slice) throws Exception {
        final Matcher href = Pattern.compile("href=\"(repodata/[^\"]*primary[^\"]*)\"").matcher(
            slice.response(
                new RequestLine(RqMethod.GET, "/repodata/repomd.xml"),
                EmptyRepodataRaceTest.auth(), Content.EMPTY
            ).join().body().asString()
        );
        href.find();
        return new String(
            new GZIPInputStream(
                new ByteArrayInputStream(
                    slice.response(
                        new RequestLine(RqMethod.GET, "/" + href.group(1)),
                        EmptyRepodataRaceTest.auth(), Content.EMPTY
                    ).join().body().asBytes()
                )
            ).readAllBytes(),
            StandardCharsets.UTF_8
        );
    }

    /**
     * Storage that parks the first listing of the upload area until the
     * gate opens, and reports when it got there.
     * @since 2.2.9
     */
    private static final class GatedAddList extends Storage.Wrap {

        /**
         * Reached signal.
         */
        private final CompletableFuture<Void> reached;

        /**
         * Gate.
         */
        private final CompletableFuture<Void> gate;

        /**
         * Whether the first listing was parked already.
         */
        private final AtomicBoolean parked;

        GatedAddList(
            final Storage origin, final CompletableFuture<Void> reached,
            final CompletableFuture<Void> gate
        ) {
            super(origin);
            this.reached = reached;
            this.gate = gate;
            this.parked = new AtomicBoolean(false);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            final CompletableFuture<Collection<Key>> res;
            if (RpmUpload.TO_ADD.equals(prefix) && this.parked.compareAndSet(false, true)) {
                this.reached.complete(null);
                res = this.gate.thenCompose(nothing -> super.list(prefix));
            } else {
                res = super.list(prefix);
            }
            return res;
        }
    }

    /**
     * Storage whose answer to "does repomd.xml exist" is computed at call
     * time but delivered only once the gate opens.
     * @since 2.2.9
     */
    private static final class GatedExists extends Storage.Wrap {

        /**
         * Gate.
         */
        private final CompletableFuture<Void> gate;

        /**
         * Completed once repomd.xml existence was first checked.
         */
        private final CompletableFuture<Void> checked;

        GatedExists(
            final Storage origin, final CompletableFuture<Void> gate,
            final CompletableFuture<Void> checked
        ) {
            super(origin);
            this.gate = gate;
            this.checked = checked;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            final CompletableFuture<Boolean> res = super.exists(key);
            final CompletableFuture<Boolean> out;
            if (EmptyRepodataRaceTest.REPOMD.equals(key)) {
                final boolean now = res.join();
                this.checked.complete(null);
                out = this.gate.thenApply(nothing -> now);
            } else {
                out = res;
            }
            return out;
        }
    }

    /**
     * Storage on which another node publishes repomd.xml while this node
     * is generating it, so this node's metadata move fails.
     * @since 2.2.9
     */
    private static final class OtherNodeWins extends Storage.Wrap {

        /**
         * Storage.
         */
        private final Storage origin;

        OtherNodeWins(final Storage origin) {
            super(origin);
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            final CompletableFuture<Void> res;
            if (destination.string().startsWith("repodata/")) {
                res = this.origin.save(
                    EmptyRepodataRaceTest.REPOMD,
                    new Content.From("<repomd other/>".getBytes(StandardCharsets.UTF_8))
                ).thenCompose(
                    nothing -> CompletableFuture.failedFuture(
                        new IllegalStateException("repodata locked by another node")
                    )
                );
            } else {
                res = super.move(source, destination);
            }
            return res;
        }
    }
}
