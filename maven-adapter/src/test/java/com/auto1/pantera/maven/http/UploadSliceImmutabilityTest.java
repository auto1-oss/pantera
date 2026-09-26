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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Release immutability and checksum integrity of {@link UploadSlice}:
 * a published release file cannot be replaced with different bytes, an
 * identical re-upload stays idempotent, SNAPSHOTs and metadata stay
 * writable, and a client checksum that does not describe the stored file
 * never replaces the server-generated one.
 *
 * @since 2.2.9
 */
final class UploadSliceImmutabilityTest {

    /**
     * Release jar path.
     */
    private static final String JAR = "/com/example/lib/1.0/lib-1.0.jar";

    /**
     * Storage.
     */
    private Storage asto;

    /**
     * Number of reads of the release jar itself (not its sidecars).
     */
    private AtomicInteger jarReads;

    /**
     * Events.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Slice under test.
     */
    private Slice slice;

    @BeforeEach
    void init() {
        this.jarReads = new AtomicInteger();
        this.asto = new JarReadCounting(new InMemoryStorage(), this.jarReads);
        this.events = new ConcurrentLinkedQueue<>();
        this.slice = new UploadSlice(this.asto, Optional.of(this.events), "maven");
    }

    @Test
    void refusesToReplaceAReleaseWithDifferentContent() {
        this.put(JAR, "one");
        final RsStatus second = this.put(JAR, "two");
        MatcherAssert.assertThat(
            "second deploy with different bytes is a conflict",
            second, new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the published bytes are unchanged",
            this.read(JAR), new IsEqual<>("one")
        );
        MatcherAssert.assertThat(
            "the generated checksum still describes the published bytes",
            this.read(JAR + ".sha1"), new IsEqual<>(hex("SHA-1", "one"))
        );
    }

    @Test
    void identicalReleaseReuploadIsIdempotent() {
        this.put(JAR, "same");
        this.events.clear();
        MatcherAssert.assertThat(
            "retrying the same deploy succeeds",
            this.put(JAR, "same"), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "an idempotent retry is not a new publish",
            this.events.size(), new IsEqual<>(0)
        );
    }

    @Test
    void snapshotFilesStayWritable() {
        final String path = "/com/example/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar";
        this.put(path, "one");
        MatcherAssert.assertThat(
            "snapshot re-deploy accepted",
            this.put(path, "two"), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "snapshot bytes replaced",
            this.read(path), new IsEqual<>("two")
        );
    }

    @Test
    void metadataStaysWritable() {
        final String path = "/com/example/lib/maven-metadata.xml";
        this.put(path, "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>");
        MatcherAssert.assertThat(
            this.put(
                path,
                "<metadata><versioning><versions><version>1.0</version><version>2.0</version></versions></versioning></metadata>"
            ),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    @Test
    void refusesAChecksumThatDoesNotMatchTheStoredArtifact() {
        this.put(JAR, "jar-bytes");
        final RsStatus status = this.put(JAR + ".sha1", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        MatcherAssert.assertThat(
            "mismatching checksum rejected",
            status, new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the server-generated checksum is kept",
            this.read(JAR + ".sha1"), new IsEqual<>(hex("SHA-1", "jar-bytes"))
        );
    }

    @ParameterizedTest
    @CsvSource({"sha1,SHA-1", "md5,MD5", "sha256,SHA-256", "sha512,SHA-512"})
    void acceptsAMatchingChecksum(final String ext, final String algo) {
        this.put(JAR, "jar-bytes");
        MatcherAssert.assertThat(
            "matching checksum (as deployed by mvn) accepted",
            this.put(JAR + "." + ext, hex(algo, "jar-bytes").toUpperCase() + "  lib-1.0.jar\n"),
            new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "stored checksum is the server-generated one",
            this.read(JAR + "." + ext), new IsEqual<>(hex(algo, "jar-bytes"))
        );
    }

    @Test
    void metadataChecksumUploadDoesNotOverwriteMetadata() {
        final String meta = "/com/example/lib/maven-metadata.xml";
        this.put(meta, "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>");
        final String stored = this.read(meta);
        this.put(meta + ".sha256", "0000");
        MatcherAssert.assertThat(
            "metadata sha256 is the digest of the stored metadata",
            this.read(meta + ".sha256"), new IsEqual<>(hex("SHA-256", stored))
        );
    }

    @Test
    void verifiesChecksumsAgainstTheGeneratedSidecarsWithoutRereadingTheArtifact() {
        this.put(JAR, "jar-bytes");
        this.jarReads.set(0);
        this.put(JAR + ".sha1", hex("SHA-1", "jar-bytes"));
        this.put(JAR + ".md5", hex("MD5", "jar-bytes"));
        this.put(JAR + ".sha256", hex("SHA-256", "jar-bytes"));
        final RsStatus bad = this.put(JAR + ".sha512", hex("SHA-512", "other"));
        MatcherAssert.assertThat(
            "a mismatch is still detected from the sidecar",
            bad, new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the stored artifact is not re-read for checksum uploads",
            this.jarReads.get(), new IsEqual<>(0)
        );
    }

    @Test
    void fallsBackToTheStoredFileWhenTheSidecarIsMissing() {
        this.put(JAR, "jar-bytes");
        this.asto.delete(new Key.From(JAR.substring(1) + ".sha1")).join();
        MatcherAssert.assertThat(
            "mismatch detected from the stored bytes",
            this.put(JAR + ".sha1", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "match accepted from the stored bytes",
            this.put(JAR + ".sha1", hex("SHA-1", "jar-bytes")),
            new IsEqual<>(RsStatus.CREATED)
        );
    }

    @Test
    void redeployComparesAgainstTheStoredSha256Sidecar() {
        this.put(JAR, "one");
        this.jarReads.set(0);
        final RsStatus same = this.put(JAR, "one");
        final RsStatus other = this.put(JAR, "two");
        MatcherAssert.assertThat(
            "identical re-upload accepted", same, new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "different re-upload refused", other, new IsEqual<>(RsStatus.CONFLICT)
        );
        MatcherAssert.assertThat(
            "the stored release is not re-read to compare it",
            this.jarReads.get(), new IsEqual<>(0)
        );
    }

    @Test
    void redeployFallsBackToTheStoredFileWhenTheSha256SidecarIsMissing() {
        this.put(JAR, "one");
        this.asto.delete(new Key.From(JAR.substring(1) + ".sha256")).join();
        MatcherAssert.assertThat(
            "identical re-upload accepted",
            this.put(JAR, "one"), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "different re-upload refused",
            this.put(JAR, "two"), new IsEqual<>(RsStatus.CONFLICT)
        );
    }

    private RsStatus put(final String path, final String body) {
        final byte[] data = body.getBytes(StandardCharsets.UTF_8);
        return this.slice.response(
            new RequestLine(RqMethod.PUT, path),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join().status();
    }

    private String read(final String path) {
        return new String(
            this.asto.value(new Key.From(path.substring(1))).join().asBytes(),
            StandardCharsets.UTF_8
        );
    }

    private static String hex(final String algo, final String data) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance(algo).digest(data.getBytes(StandardCharsets.UTF_8))
            );
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Storage that counts reads of the release jar ({@link #JAR}).
     * @since 2.2.9
     */
    private static final class JarReadCounting extends Storage.Wrap {

        /**
         * Read counter.
         */
        private final AtomicInteger reads;

        /**
         * Ctor.
         * @param origin Delegate
         * @param reads Read counter
         */
        JarReadCounting(final Storage origin, final AtomicInteger reads) {
            super(origin);
            this.reads = reads;
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            if (key.string().equals(JAR.substring(1))) {
                this.reads.incrementAndGet();
            }
            return super.value(key);
        }
    }
}
