/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DigestComputer}.
 */
class DigestComputerTest {

    @Test
    void computesSha256() {
        final byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        final Map<String, String> digests = DigestComputer.compute(
            content, Set.of(DigestComputer.SHA256)
        );
        assertThat(
            digests,
            hasEntry(
                DigestComputer.SHA256,
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        );
    }

    @Test
    void computesSha1() {
        final byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        final Map<String, String> digests = DigestComputer.compute(
            content, Set.of(DigestComputer.SHA1)
        );
        assertThat(
            digests,
            hasEntry(
                DigestComputer.SHA1,
                "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
            )
        );
    }

    @Test
    void computesMd5() {
        final byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        final Map<String, String> digests = DigestComputer.compute(
            content, Set.of(DigestComputer.MD5)
        );
        assertThat(
            digests,
            hasEntry(DigestComputer.MD5, "5d41402abc4b2a76b9719d911017c592")
        );
    }

    @Test
    void computesMavenDigests() {
        final byte[] content = "test content".getBytes(StandardCharsets.UTF_8);
        final Map<String, String> digests = DigestComputer.compute(
            content, DigestComputer.MAVEN_DIGESTS
        );
        assertThat("should have SHA-256", digests.containsKey(DigestComputer.SHA256), is(true));
        assertThat("should have SHA-1", digests.containsKey(DigestComputer.SHA1), is(true));
        assertThat("should have MD5", digests.containsKey(DigestComputer.MD5), is(true));
        assertThat("should have 3 entries", digests.size(), equalTo(3));
    }


    @Test
    void returnsEmptyForNullAlgorithms() {
        final Map<String, String> digests = DigestComputer.compute(
            new byte[]{1, 2, 3}, null
        );
        assertThat(digests, equalTo(Collections.emptyMap()));
    }

    @Test
    void returnsEmptyForEmptyAlgorithms() {
        final Map<String, String> digests = DigestComputer.compute(
            new byte[]{1, 2, 3}, Collections.emptySet()
        );
        assertThat(digests, equalTo(Collections.emptyMap()));
    }

    @Test
    void throwsForUnsupportedAlgorithm() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DigestComputer.compute(
                new byte[]{1}, Set.of("UNSUPPORTED-ALGO")
            )
        );
    }

    @Test
    void throwsForNullContent() {
        assertThrows(
            NullPointerException.class,
            () -> DigestComputer.compute(null, Set.of(DigestComputer.SHA256))
        );
    }

    @Test
    void computesEmptyContent() {
        final Map<String, String> digests = DigestComputer.compute(
            new byte[0], Set.of(DigestComputer.SHA256)
        );
        assertThat(
            digests,
            hasEntry(
                DigestComputer.SHA256,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            )
        );
    }

    @Test
    void streamingDigestsMatchBatchDigests() {
        final byte[] content = "streaming test data for digest verification"
            .getBytes(StandardCharsets.UTF_8);
        final Map<String, String> batch = DigestComputer.compute(
            content, DigestComputer.MAVEN_DIGESTS
        );
        final Map<String, MessageDigest> digests =
            DigestComputer.createDigests(DigestComputer.MAVEN_DIGESTS);
        DigestComputer.updateDigests(digests, ByteBuffer.wrap(content));
        final Map<String, String> streaming = DigestComputer.finalizeDigests(digests);
        assertThat(streaming, equalTo(batch));
    }

    @Test
    void streamingDigestsWithMultipleChunks() {
        final byte[] full = "hello world streaming digest"
            .getBytes(StandardCharsets.UTF_8);
        final Map<String, String> batch = DigestComputer.compute(
            full, Set.of(DigestComputer.SHA256, DigestComputer.MD5)
        );
        final Map<String, MessageDigest> digests =
            DigestComputer.createDigests(
                Set.of(DigestComputer.SHA256, DigestComputer.MD5)
            );
        final int mid = full.length / 2;
        DigestComputer.updateDigests(
            digests, ByteBuffer.wrap(full, 0, mid)
        );
        DigestComputer.updateDigests(
            digests, ByteBuffer.wrap(full, mid, full.length - mid)
        );
        final Map<String, String> streaming = DigestComputer.finalizeDigests(digests);
        assertThat(streaming, equalTo(batch));
    }

    @Test
    void streamingDigestsWithEmptyContent() {
        final Map<String, String> batch = DigestComputer.compute(
            new byte[0], Set.of(DigestComputer.SHA256)
        );
        final Map<String, MessageDigest> digests =
            DigestComputer.createDigests(Set.of(DigestComputer.SHA256));
        final Map<String, String> streaming = DigestComputer.finalizeDigests(digests);
        assertThat(streaming, equalTo(batch));
    }

    @Test
    void createDigestsThrowsForUnsupported() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DigestComputer.createDigests(Set.of("BOGUS"))
        );
    }

    @Test
    void createDigestsReturnsEmptyForNull() {
        assertThat(
            DigestComputer.createDigests(null),
            equalTo(Collections.emptyMap())
        );
    }
}
