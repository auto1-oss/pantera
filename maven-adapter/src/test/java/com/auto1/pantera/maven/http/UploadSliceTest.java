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
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.ContentIs;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.maven.security.InMemoryKeyringStore;
import com.auto1.pantera.maven.security.KeyringStoreRegistry;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.jcabi.xml.XMLDocument;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test for {@link UploadSlice}.
 */
class UploadSliceTest {

    /**
     * Test storage.
     */
    private Storage asto;

    /**
     * Update maven slice.
     */
    private Slice ums;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.ums = new UploadSlice(this.asto);
    }

    @AfterEach
    void resetKeyringRegistry() {
        // WS4-maven tests install a fixture keyring — never leak it into
        // other test classes running in the same JVM.
        KeyringStoreRegistry.uninstall();
    }

    @Test
    void savesDataDirectly() {
        final byte[] data = "jar content".getBytes();
        MatcherAssert.assertThat(
            "Wrong response status, CREATED is expected",
            this.ums,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, "/com/pantera/asto/0.1/asto-0.1.jar"),
                Headers.from(new ContentLength(data.length)),
                new Content.From(data)
            )
        );
        MatcherAssert.assertThat(
            "Uploaded data were not saved to storage",
            this.asto.value(new Key.From("com/pantera/asto/0.1/asto-0.1.jar")).join(),
            new ContentIs(data)
        );
    }

    @Test
    void normalizesEpochMillisLastUpdated() {
        // Epoch-millis lastUpdated from old Artipie clients must be repaired
        final String epochMillisXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metadata>\n"
            + "  <groupId>com.example</groupId>\n"
            + "  <artifactId>my-lib</artifactId>\n"
            + "  <versioning>\n"
            + "    <latest>1.0.0</latest>\n"
            + "    <release>1.0.0</release>\n"
            + "    <versions><version>1.0.0</version></versions>\n"
            + "    <lastUpdated>1737801234567</lastUpdated>\n" // 13-digit epoch millis
            + "  </versioning>\n"
            + "</metadata>\n";
        final byte[] data = epochMillisXml.getBytes(StandardCharsets.UTF_8);
        final Key metaKey = new Key.From("com/example/my-lib/maven-metadata.xml");

        this.ums.response(
            new RequestLine(RqMethod.PUT, "/com/example/my-lib/maven-metadata.xml"),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();

        final String stored = new String(
            this.asto.value(metaKey).join().asBytesFuture().join(),
            StandardCharsets.UTF_8
        );
        final List<String> lastUpdated = new XMLDocument(stored).xpath("//lastUpdated/text()");
        MatcherAssert.assertThat(
            "lastUpdated must be present after normalization",
            lastUpdated.isEmpty(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "lastUpdated must match yyyyMMddHHmmss (14 digits), not epoch millis",
            lastUpdated.get(0).matches("\\d{14}"),
            Matchers.is(true)
        );
    }

    @Test
    void addsLatestTagWhenMissing() {
        // Maven clients sometimes omit <latest>; Pantera must add it
        final String noLatestXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metadata>\n"
            + "  <groupId>com.example</groupId>\n"
            + "  <artifactId>my-lib</artifactId>\n"
            + "  <versioning>\n"
            + "    <release>1.3.0</release>\n"
            + "    <versions><version>1.3.0</version></versions>\n"
            + "    <lastUpdated>20260101000000</lastUpdated>\n"
            + "  </versioning>\n"
            + "</metadata>\n";
        final byte[] data = noLatestXml.getBytes(StandardCharsets.UTF_8);
        final Key metaKey = new Key.From("com/example/my-lib/maven-metadata.xml");

        this.ums.response(
            new RequestLine(RqMethod.PUT, "/com/example/my-lib/maven-metadata.xml"),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();

        final String stored = new String(
            this.asto.value(metaKey).join().asBytesFuture().join(),
            StandardCharsets.UTF_8
        );
        final List<String> latest = new XMLDocument(stored).xpath("//latest/text()");
        MatcherAssert.assertThat(
            "latest must be added when missing",
            latest.isEmpty(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "latest must be set to the highest version",
            latest.get(0),
            Matchers.is("1.3.0")
        );
    }

    @Test
    void lastUpdatedNormalizedEvenWhenLatestUnchanged() {
        // When <latest> is already correct, <lastUpdated> must still be normalised
        final String staleTimestampXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metadata>\n"
            + "  <groupId>com.example</groupId>\n"
            + "  <artifactId>my-lib</artifactId>\n"
            + "  <versioning>\n"
            + "    <latest>2.0.0</latest>\n"
            + "    <release>2.0.0</release>\n"
            + "    <versions><version>2.0.0</version></versions>\n"
            + "    <lastUpdated>1700000000000</lastUpdated>\n" // stale epoch millis
            + "  </versioning>\n"
            + "</metadata>\n";
        final byte[] data = staleTimestampXml.getBytes(StandardCharsets.UTF_8);
        final Key metaKey = new Key.From("com/example/my-lib/maven-metadata.xml");

        this.ums.response(
            new RequestLine(RqMethod.PUT, "/com/example/my-lib/maven-metadata.xml"),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();

        final String stored = new String(
            this.asto.value(metaKey).join().asBytesFuture().join(),
            StandardCharsets.UTF_8
        );
        final String lastUpdated = new XMLDocument(stored).xpath("//lastUpdated/text()").get(0);
        MatcherAssert.assertThat(
            "lastUpdated must be normalised even when latest tag was already correct",
            lastUpdated.matches("\\d{14}"),
            Matchers.is(true)
        );
    }

    @Test
    void contractLastUpdatedAlwaysMatchesMavenFormat() {
        // Contract: <lastUpdated> must always be exactly 14 digits (yyyyMMddHHmmss)
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metadata>\n"
            + "  <groupId>g</groupId><artifactId>a</artifactId>\n"
            + "  <versioning>\n"
            + "    <latest>1.0</latest><release>1.0</release>\n"
            + "    <versions><version>1.0</version></versions>\n"
            + "    <lastUpdated>20230101120000</lastUpdated>\n"
            + "  </versioning>\n"
            + "</metadata>\n";
        final byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        final Key metaKey = new Key.From("g/a/maven-metadata.xml");

        this.ums.response(
            new RequestLine(RqMethod.PUT, "/g/a/maven-metadata.xml"),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();

        final String stored = new String(
            this.asto.value(metaKey).join().asBytesFuture().join(),
            StandardCharsets.UTF_8
        );
        final String lastUpdated = new XMLDocument(stored).xpath("//lastUpdated/text()").get(0);
        MatcherAssert.assertThat(
            "Contract: lastUpdated must match ^\\d{14}$ (yyyyMMddHHmmss UTC)",
            lastUpdated.matches("\\d{14}"),
            Matchers.is(true)
        );
    }

    @Test
    void stripsMetadataPropertiesFromFilename() {
        // Test that semicolon-separated metadata properties are stripped from the filename
        // to avoid exceeding filesystem filename length limits (typically 255 bytes)
        final byte[] data = "graphql content".getBytes();
        final String pathWithMetadata =
            "/wkda/common/graphql/vehicle/1.0.0-395-202511111100/" +
            "vehicle-1.0.0-395-202511111100.graphql;" +
            "vcs.revision=6177d00b21602d4a23f004ce5bd1dc56e5154ed4;" +
            "build.timestamp=1762855225704;" +
            "build.name=libraries+::+graphql-schema-specification-build-deploy+::+master;" +
            "build.number=395;" +
            "vcs.branch=master;" +
            "vcs.url=git@github.com:wkda/graphql-schema-specification.git";

        MatcherAssert.assertThat(
            "Wrong response status, CREATED is expected",
            this.ums,
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, pathWithMetadata),
                Headers.from(new ContentLength(data.length)),
                new Content.From(data)
            )
        );

        // Verify the file was saved WITHOUT the metadata properties
        final Key expectedKey = new Key.From(
            "wkda/common/graphql/vehicle/1.0.0-395-202511111100/" +
            "vehicle-1.0.0-395-202511111100.graphql"
        );
        MatcherAssert.assertThat(
            "Uploaded data should be saved without metadata properties",
            this.asto.value(expectedKey).join(),
            new ContentIs(data)
        );
    }

    // ===== Primary artifact indexing — structural filename-prefix detection =====
    //
    // These tests lock in the v2.1.3 fix: the write path uses the same structural
    // invariant as the read-side parser (filename starts with "{artifactId}-")
    // instead of a hardcoded extension whitelist. Upload of .yaml, .json, etc.
    // must produce ArtifactEvent; companion files (sources, javadoc, checksums,
    // signatures, metadata) must NOT.

    @Test
    void yamlUploadProducesArtifactEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "apiVersion: v1".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/wkda/common/api/retail-financing-application-dtos/1.0.0-TEST/"
                    + "retail-financing-application-dtos-1.0.0-TEST.yaml"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            ".yaml upload must produce exactly one ArtifactEvent",
            events.size(),
            Matchers.is(1)
        );
        final ArtifactEvent event = events.peek();
        MatcherAssert.assertThat(
            "event.name must be groupId.artifactId (not path with slashes)",
            event.artifactName(),
            Matchers.is("wkda.common.api.retail-financing-application-dtos")
        );
        MatcherAssert.assertThat(
            "event.version must be the version directory",
            event.artifactVersion(),
            Matchers.is("1.0.0-TEST")
        );
    }

    @Test
    void sourcesJarUploadDoesNotProduceEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "sources".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/1.0/foo-1.0-sources.jar"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            "sources.jar is a companion file — must NOT produce an ArtifactEvent",
            events.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    void javadocJarUploadDoesNotProduceEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "javadoc".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/1.0/foo-1.0-javadoc.jar"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            "javadoc.jar is a companion file — must NOT produce an ArtifactEvent",
            events.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    void checksumUploadDoesNotProduceEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "abc123".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/1.0/foo-1.0.jar.sha1"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            "checksum is a companion file — must NOT produce an ArtifactEvent",
            events.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    void signatureUploadDoesNotProduceEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "sig".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/1.0/foo-1.0.jar.asc"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            "PGP signature is a companion file — must NOT produce an ArtifactEvent",
            events.isEmpty(),
            Matchers.is(true)
        );
    }

    @Test
    void classifierJarUploadProducesEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "tests".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/1.0/foo-1.0-tests.jar"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            "classifier jar (tests, native, etc.) is a legit primary — must produce event",
            events.size(),
            Matchers.is(1)
        );
    }

    @Test
    void pomUploadStillProducesEventBackwardsCompat() {
        // Original supported case — the whitelist used to match .pom explicitly.
        // Structural detection must still index it.
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final byte[] data = "<project/>".getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/1.0/foo-1.0.pom"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            ".pom upload must still produce an ArtifactEvent",
            events.size(),
            Matchers.is(1)
        );
    }

    @Test
    void metadataXmlUploadDoesNotProduceEvent() {
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = new UploadSlice(this.asto, Optional.of(events), "libs-release-local");
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<metadata><groupId>com.example</groupId><artifactId>foo</artifactId>\n"
            + "<versioning><latest>1.0</latest><release>1.0</release>\n"
            + "<versions><version>1.0</version></versions>\n"
            + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>\n";
        final byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        slice.response(
            new RequestLine(
                RqMethod.PUT,
                "/com/example/foo/maven-metadata.xml"
            ),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
        MatcherAssert.assertThat(
            "maven-metadata.xml is not a primary artifact — must NOT produce event",
            events.isEmpty(),
            Matchers.is(true)
        );
    }

    // ===== WS4-maven.4: GA-level maven-metadata.xml regeneration =====

    @Test
    @DisplayName("WS4-maven.4: two different versions deployed for the same GA both "
        + "end up in the regenerated <versions>, and <latest> tracks the highest")
    void metadataRegeneratesWithBothVersionsAfterTwoDeploys() {
        put(this.ums, "/com/example/lib/1.0/lib-1.0.jar", "v1".getBytes(StandardCharsets.UTF_8));
        put(this.ums, "/com/example/lib/1.1/lib-1.1.jar", "v2".getBytes(StandardCharsets.UTF_8));

        final String xml = metadataXml(this.asto, "com/example/lib");
        final List<String> versions = new XMLDocument(xml).xpath("//version/text()");
        MatcherAssert.assertThat(
            "both deployed versions must be present — neither deploy may drop the other",
            versions.containsAll(List.of("1.0", "1.1")),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "<latest> must track the highest version",
            new XMLDocument(xml).xpath("//latest/text()").get(0),
            Matchers.is("1.1")
        );
    }

    @Test
    @DisplayName("WS4-maven.4: a stale client-sent maven-metadata.xml listing only an "
        + "old version does not shrink <versions> after a newer version was deployed")
    void staleClientMetadataDoesNotShrinkVersions() {
        put(this.ums, "/com/example/lib/1.0/lib-1.0.jar", "v1".getBytes(StandardCharsets.UTF_8));
        put(this.ums, "/com/example/lib/2.0/lib-2.0.jar", "v2".getBytes(StandardCharsets.UTF_8));
        // A stale client PUTs a maven-metadata.xml listing ONLY 1.0 (as if
        // it never saw 2.0) — the regenerator, not the client XML, is
        // authoritative for <versions>.
        final byte[] stale = ("<?xml version=\"1.0\"?><metadata><groupId>com.example</groupId>"
            + "<artifactId>lib</artifactId><versioning><latest>1.0</latest><release>1.0</release>"
            + "<versions><version>1.0</version></versions>"
            + "<lastUpdated>20260101000000</lastUpdated></versioning></metadata>")
            .getBytes(StandardCharsets.UTF_8);
        put(this.ums, "/com/example/lib/maven-metadata.xml", stale);
        // The stale metadata PUT itself is accepted (backward compatible),
        // but the NEXT primary deploy re-establishes the authoritative view.
        put(this.ums, "/com/example/lib/2.1/lib-2.1.jar", "v3".getBytes(StandardCharsets.UTF_8));

        final List<String> versions = new XMLDocument(
            metadataXml(this.asto, "com/example/lib")
        ).xpath("//version/text()");
        MatcherAssert.assertThat(
            "1.0, 2.0 and 2.1 must all still be listed after the stale metadata PUT",
            versions.containsAll(List.of("1.0", "2.0", "2.1")),
            Matchers.is(true)
        );
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    @DisplayName("WS4-maven.4: concurrent deploys of different versions for the same GA "
        + "converge — both versions survive a real multi-threaded race")
    void concurrentDeploysOfDifferentVersionsBothSurvive() throws InterruptedException {
        // No artificial join timeout: on a shared/loaded CI runner the
        // common ForkJoinPool backing InMemoryStorage's supplyAsync calls
        // can be contended by unrelated tests running in the same JVM
        // (CLAUDE.md: never assert absolute wall-clock latency — a bounded
        // join here would produce a false failure under load, not prove
        // anything about the metadata regenerator's correctness). The
        // @Timeout above converts a genuine hang into a deterministic
        // failure instead.
        final int versionCount = 5;
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(versionCount);
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> threads = new java.util.ArrayList<>(versionCount);
        for (int i = 0; i < versionCount; i++) {
            final String version = "1." + i;
            threads.add(new Thread(() -> {
                ready.countDown();
                await(go);
                put(
                    this.ums,
                    "/com/example/burst/" + version + "/burst-" + version + ".jar",
                    ("v" + version).getBytes(StandardCharsets.UTF_8)
                );
            }));
        }
        threads.forEach(Thread::start);
        ready.await();
        go.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }
        final List<String> versions = new XMLDocument(
            metadataXml(this.asto, "com/example/burst")
        ).xpath("//version/text()");
        for (int i = 0; i < versionCount; i++) {
            MatcherAssert.assertThat(
                "version 1." + i + " must survive the concurrent burst — zero lost versions",
                versions.contains("1." + i),
                Matchers.is(true)
            );
        }
    }

    // ===== WS4-maven.5: checksum verification on hosted store =====

    @Test
    @DisplayName("WS4-maven.5: a checksum sidecar matching the stored primary is accepted")
    void matchingChecksumSidecarIsAccepted() throws Exception {
        final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        put(this.ums, "/com/example/chk/1.0/chk-1.0.jar", jar);
        final String sha1 = new ContentDigest(new Content.From(jar), Digests.SHA1)
            .hex().toCompletableFuture().join();

        final Response resp = put(
            this.ums, "/com/example/chk/1.0/chk-1.0.jar.sha1",
            sha1.getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(resp.status(), Matchers.is(RsStatus.CREATED));
        MatcherAssert.assertThat(
            "matching checksum sidecar must be persisted",
            this.asto.exists(new Key.From("com/example/chk/1.0/chk-1.0.jar.sha1")).join(),
            Matchers.is(true)
        );
    }

    @Test
    @DisplayName("WS4-maven.5: a checksum sidecar NOT matching the stored primary is "
        + "rejected 400 and does not overwrite the sidecar")
    void mismatchedChecksumSidecarIsRejected() throws Exception {
        final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        put(this.ums, "/com/example/chk/1.0/chk-1.0.jar", jar);
        // The primary upload already auto-generated a CORRECT .sha1 sidecar
        // (shouldGenerateChecksums) — capture it so we can prove the
        // rejected mismatched upload below leaves it untouched.
        final Key sidecarKey = new Key.From("com/example/chk/1.0/chk-1.0.jar.sha1");
        final String autoGenerated = new String(
            this.asto.value(sidecarKey).join().asBytesFuture().join(), StandardCharsets.UTF_8
        );

        final Response resp = put(
            this.ums, "/com/example/chk/1.0/chk-1.0.jar.sha1",
            "0000000000000000000000000000000000000000".getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(resp.status(), Matchers.is(RsStatus.BAD_REQUEST));
        MatcherAssert.assertThat(
            "the mismatched claim must NOT overwrite the correct auto-generated sidecar",
            new String(this.asto.value(sidecarKey).join().asBytesFuture().join(), StandardCharsets.UTF_8),
            Matchers.is(autoGenerated)
        );
    }

    // ===== WS4-maven.6: release-redeploy immutability =====

    @Test
    @DisplayName("WS4-maven.6: releaseImmutable rejects a release redeploy with 409 "
        + "and leaves the original bytes untouched")
    void releaseImmutableRejectsRedeploy() {
        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "libs-release", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(false, true)
        );
        put(slice, "/com/example/rel/1.0/rel-1.0.jar", "original".getBytes(StandardCharsets.UTF_8));

        final Response redeploy = put(
            slice, "/com/example/rel/1.0/rel-1.0.jar", "corrupted".getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(redeploy.status(), Matchers.is(RsStatus.CONFLICT));
        MatcherAssert.assertThat(
            "original bytes must survive the rejected redeploy",
            this.asto.value(new Key.From("com/example/rel/1.0/rel-1.0.jar")).join(),
            new ContentIs("original".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    @DisplayName("WS4-maven.6: releaseImmutable never blocks a SNAPSHOT redeploy")
    void releaseImmutableAllowsSnapshotRedeploy() {
        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "libs-snapshot", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(false, true)
        );
        put(slice, "/com/example/snap/1.0-SNAPSHOT/snap-1.0-SNAPSHOT.jar",
            "first".getBytes(StandardCharsets.UTF_8));
        final Response redeploy = put(
            slice, "/com/example/snap/1.0-SNAPSHOT/snap-1.0-SNAPSHOT.jar",
            "second".getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(
            "SNAPSHOT redeploy must succeed even with releaseImmutable enabled",
            redeploy.status(), Matchers.is(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            this.asto.value(new Key.From("com/example/snap/1.0-SNAPSHOT/snap-1.0-SNAPSHOT.jar")).join(),
            new ContentIs("second".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    @DisplayName("WS4-maven.6: releaseImmutable=false (default) preserves the legacy "
        + "overwrite behaviour — regression guard")
    void releaseMutableByDefaultAllowsOverwrite() {
        put(this.ums, "/com/example/mut/1.0/mut-1.0.jar", "first".getBytes(StandardCharsets.UTF_8));
        final Response redeploy = put(
            this.ums, "/com/example/mut/1.0/mut-1.0.jar", "second".getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(redeploy.status(), Matchers.is(RsStatus.CREATED));
        MatcherAssert.assertThat(
            this.asto.value(new Key.From("com/example/mut/1.0/mut-1.0.jar")).join(),
            new ContentIs("second".getBytes(StandardCharsets.UTF_8))
        );
    }

    // ===== WS4-maven.1/.2: PGP signature verification on hosted store =====

    @Test
    @DisplayName("WS4-maven.2: verifyPgp=false (default) never consults the keyring — "
        + "regression guard proving byte-identical pre-2.3.0 behaviour")
    void verifyPgpDisabledNeverConsultsKeyring() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        KeyringStoreRegistry.install(id -> {
            lookups.incrementAndGet();
            return Optional.empty();
        });
        final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        put(this.ums, "/com/example/nopgp/1.0/nopgp-1.0.jar", jar);
        final Response resp = put(
            this.ums, "/com/example/nopgp/1.0/nopgp-1.0.jar.asc",
            "not even a real signature".getBytes(StandardCharsets.UTF_8)
        );
        MatcherAssert.assertThat(
            "verifyPgp=false: .asc is saved as-is, no verification attempted",
            resp.status(), Matchers.is(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "verifyPgp=false: the keyring must never be consulted",
            lookups.get(), Matchers.is(0)
        );
    }

    @Test
    @DisplayName("WS4-maven.2: a signature from a key registered in the keyring verifies "
        + "and both primary + .asc are persisted")
    void verifyPgpAcceptsTrustedSignature() throws Exception {
        final PgpFixture pgp = PgpFixture.generate("trusted@example.com");
        final InMemoryKeyringStore keyring = new InMemoryKeyringStore();
        keyring.addAsciiArmored(pgp.armoredPublicKey());
        KeyringStoreRegistry.install(keyring);

        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "signed", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(true, false)
        );
        final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        put(slice, "/com/example/signed/1.0/signed-1.0.jar", jar);
        final Response resp = put(
            slice, "/com/example/signed/1.0/signed-1.0.jar.asc", pgp.signDetached(jar)
        );

        MatcherAssert.assertThat(resp.status(), Matchers.is(RsStatus.CREATED));
        MatcherAssert.assertThat(
            "primary must still be present",
            this.asto.exists(new Key.From("com/example/signed/1.0/signed-1.0.jar")).join(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            ".asc must be persisted",
            this.asto.exists(new Key.From("com/example/signed/1.0/signed-1.0.jar.asc")).join(),
            Matchers.is(true)
        );
    }

    @Test
    @DisplayName("WS4-maven.2: a signature from a key NOT in the keyring is rejected 403 "
        + "and the primary is removed from storage")
    void verifyPgpRejectsUntrustedSignature() throws Exception {
        final PgpFixture pgp = PgpFixture.generate("untrusted@example.com");
        // Empty keyring — the signer is never registered.
        KeyringStoreRegistry.install(new InMemoryKeyringStore());

        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "signed", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(true, false)
        );
        final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        put(slice, "/com/example/signed2/1.0/signed2-1.0.jar", jar);
        final Response resp = put(
            slice, "/com/example/signed2/1.0/signed2-1.0.jar.asc", pgp.signDetached(jar)
        );

        MatcherAssert.assertThat(resp.status(), Matchers.is(RsStatus.FORBIDDEN));
        MatcherAssert.assertThat(
            "primary must be removed after a failed PGP verification",
            this.asto.exists(new Key.From("com/example/signed2/1.0/signed2-1.0.jar")).join(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            ".asc must NOT be persisted",
            this.asto.exists(new Key.From("com/example/signed2/1.0/signed2-1.0.jar.asc")).join(),
            Matchers.is(false)
        );
    }

    // ===== H1 fix: verifyPgp primary quarantine (both upload-order bypasses closed) =====

    @Test
    @DisplayName("H1: with verifyPgp on, a primary is NOT servable in the window between "
        + "its own PUT and a valid .asc arriving — it only becomes servable once verified")
    void verifyPgpQuarantinesPrimaryUntilSignatureArrives() throws Exception {
        final PgpFixture pgp = PgpFixture.generate("quarantine@example.com");
        final InMemoryKeyringStore keyring = new InMemoryKeyringStore();
        keyring.addAsciiArmored(pgp.armoredPublicKey());
        KeyringStoreRegistry.install(keyring);

        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "quarantine", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(true, false)
        );
        final Key primaryKey = new Key.From("com/example/q/1.0/q-1.0.jar");
        final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);

        put(slice, "/com/example/q/1.0/q-1.0.jar", jar);
        MatcherAssert.assertThat(
            "H1 invariant: an uploaded primary must not be servable before a "
                + "verified signature commits it",
            this.asto.exists(primaryKey).join(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "the primary must be quarantined (present under the staging namespace) — "
                + "not silently dropped",
            this.asto.exists(new Key.From(UploadSlice.STAGING_PREFIX + "/" + primaryKey.string())).join(),
            Matchers.is(true)
        );

        put(slice, "/com/example/q/1.0/q-1.0.jar.asc", pgp.signDetached(jar));
        MatcherAssert.assertThat(
            "once a verified signature lands, the primary is promoted and becomes servable",
            this.asto.exists(primaryKey).join(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "quarantine copy must be gone after promotion (moved, not copied)",
            this.asto.exists(new Key.From(UploadSlice.STAGING_PREFIX + "/" + primaryKey.string())).join(),
            Matchers.is(false)
        );
    }

    @Test
    @DisplayName("H1 bypass (b) closed: a primary uploaded with verifyPgp on, whose .asc "
        + "never arrives, is never served and never enters maven-metadata.xml")
    void verifyPgpBypassOmissionPrimaryNeverServedWithoutSignature() {
        KeyringStoreRegistry.install(new InMemoryKeyringStore());
        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "omitted", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(true, false)
        );
        final byte[] jar = "unsigned-jar-bytes".getBytes(StandardCharsets.UTF_8);

        final Response resp = put(slice, "/com/example/omit/1.0/omit-1.0.jar", jar);
        MatcherAssert.assertThat(
            "the primary PUT itself succeeds (client sees 201) — only serving is gated",
            resp.status(), Matchers.is(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "bypass (b): omitting the .asc entirely must NOT publish the primary",
            this.asto.exists(new Key.From("com/example/omit/1.0/omit-1.0.jar")).join(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "an unverified primary must never reach maven-metadata.xml's <versions>",
            this.asto.exists(new Key.From("com/example/omit/maven-metadata.xml")).join(),
            Matchers.is(false)
        );
    }

    @Test
    @DisplayName("H1 bypass (a) closed — attack case: an untrusted .asc uploaded BEFORE the "
        + "primary does not let a later, unrelated primary upload slip through unverified")
    void verifyPgpBypassReorderUntrustedSignatureBeforePrimaryNeverPublishes() throws Exception {
        final PgpFixture attacker = PgpFixture.generate("attacker@example.com");
        // Empty keyring — the attacker's key is never trusted.
        KeyringStoreRegistry.install(new InMemoryKeyringStore());
        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "reorder-attack", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(true, false)
        );
        final byte[] maliciousJar = "malicious-jar-bytes".getBytes(StandardCharsets.UTF_8);

        // Reorder bypass: .asc PUT arrives first — under the pre-fix code this
        // hit the `!primaryExists` branch and was saved unverified.
        final Response ascResp = put(
            slice, "/com/example/atk/1.0/atk-1.0.jar.asc", attacker.signDetached(maliciousJar)
        );
        MatcherAssert.assertThat(ascResp.status(), Matchers.is(RsStatus.CREATED));

        final Response primaryResp = put(slice, "/com/example/atk/1.0/atk-1.0.jar", maliciousJar);
        MatcherAssert.assertThat(
            "the primary upload itself must be rejected once its staged signature fails verification",
            primaryResp.status(), Matchers.is(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "bypass (a): the primary must never become servable regardless of upload order",
            this.asto.exists(new Key.From("com/example/atk/1.0/atk-1.0.jar")).join(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            ".asc must never become servable either",
            this.asto.exists(new Key.From("com/example/atk/1.0/atk-1.0.jar.asc")).join(),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "the quarantined primary must be cleaned up after the rejection",
            this.asto.exists(new Key.From(UploadSlice.STAGING_PREFIX + "/com/example/atk/1.0/atk-1.0.jar")).join(),
            Matchers.is(false)
        );
    }

    @Test
    @DisplayName("H1 bypass (a) — legitimate order reversal: a TRUSTED .asc uploaded BEFORE "
        + "its matching primary still publishes once the primary arrives")
    void verifyPgpBypassReorderTrustedSignatureBeforePrimaryStillPublishes() throws Exception {
        final PgpFixture pgp = PgpFixture.generate("early-signer@example.com");
        final InMemoryKeyringStore keyring = new InMemoryKeyringStore();
        keyring.addAsciiArmored(pgp.armoredPublicKey());
        KeyringStoreRegistry.install(keyring);
        final Slice slice = new UploadSlice(
            this.asto, Optional.empty(), "reorder-ok", com.auto1.pantera.index.SyncArtifactIndexer.NOOP,
            new MavenHostedPolicy(true, false)
        );
        final byte[] jar = "well-behaved-jar-bytes".getBytes(StandardCharsets.UTF_8);

        final Response ascResp = put(
            slice, "/com/example/ok/1.0/ok-1.0.jar.asc", pgp.signDetached(jar)
        );
        MatcherAssert.assertThat(ascResp.status(), Matchers.is(RsStatus.CREATED));
        MatcherAssert.assertThat(
            "no real primary exists yet, so the signature itself is quarantined, not verified in a vacuum",
            this.asto.exists(new Key.From("com/example/ok/1.0/ok-1.0.jar")).join(),
            Matchers.is(false)
        );

        final Response primaryResp = put(slice, "/com/example/ok/1.0/ok-1.0.jar", jar);
        MatcherAssert.assertThat(primaryResp.status(), Matchers.is(RsStatus.CREATED));
        MatcherAssert.assertThat(
            "order must not matter for a genuinely matching, trusted signature",
            this.asto.exists(new Key.From("com/example/ok/1.0/ok-1.0.jar")).join(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            this.asto.exists(new Key.From("com/example/ok/1.0/ok-1.0.jar.asc")).join(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "checksums must be generated once promoted, exactly like the non-quarantined path",
            this.asto.exists(new Key.From("com/example/ok/1.0/ok-1.0.jar.sha256")).join(),
            Matchers.is(true)
        );
    }

    @Test
    @DisplayName("H1: the quarantine namespace itself is not directly addressable through "
        + "the Maven API — a crafted GET for the staged path is a plain 404, never the bytes")
    void verifyPgpStagingNamespaceNotDirectlyAddressable() {
        // Simulates a primary sitting in quarantine (as stagePrimaryForVerification
        // would leave it) without going through the upload flow, to isolate the
        // routing guard from the upload logic under test elsewhere in this class.
        final byte[] staged = "quarantined-bytes-must-not-leak".getBytes(StandardCharsets.UTF_8);
        this.asto.save(
            new Key.From(UploadSlice.STAGING_PREFIX + "/com/example/probe/1.0/probe-1.0.jar"),
            new Content.From(staged)
        ).join();

        final Slice mavenSlice = new MavenSlice(
            this.asto, com.auto1.pantera.security.policy.Policy.FREE,
            (username, password) -> Optional.empty(), null, "probe-repo", Optional.empty()
        );
        final Response resp = mavenSlice.response(
            new RequestLine(
                RqMethod.GET, "/.pgp-pending/com/example/probe/1.0/probe-1.0.jar"
            ),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a direct request for the staging namespace must 404, never serve the "
                + "unverified bytes sitting there",
            resp.status(), Matchers.is(RsStatus.NOT_FOUND)
        );
    }

    // ===== test fixture helpers =====

    private static Response put(final Slice slice, final String path, final byte[] data) {
        return slice.response(
            new RequestLine(RqMethod.PUT, path),
            Headers.from(new ContentLength(data.length)),
            new Content.From(data)
        ).join();
    }

    private static String metadataXml(final Storage storage, final String baseKey) {
        return new String(
            storage.value(new Key.From(baseKey + "/maven-metadata.xml")).join()
                .asBytesFuture().join(),
            StandardCharsets.UTF_8
        );
    }

    private static void await(final java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Minimal PGP key-pair + signing fixture (RSA-2048, self-contained,
     * no filesystem/network) — mirrors {@code PgpVerifierTest}'s fixture
     * helpers, kept local to this test class for isolation.
     */
    private static final class PgpFixture {

        private final PGPSecretKey secretKey;

        private PgpFixture(final PGPSecretKey secretKey) {
            this.secretKey = secretKey;
        }

        static PgpFixture generate(final String userId) throws Exception {
            final RSAKeyPairGenerator rsa = new RSAKeyPairGenerator();
            rsa.init(new RSAKeyGenerationParameters(
                BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12
            ));
            final PGPKeyPair pair = new BcPGPKeyPair(
                PublicKeyAlgorithmTags.RSA_GENERAL, rsa.generateKeyPair(), new Date()
            );
            final PGPSignatureSubpacketGenerator subs = new PGPSignatureSubpacketGenerator();
            subs.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER);
            final PGPKeyRingGenerator ring = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION, pair, userId,
                new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
                subs.generate(), null,
                new BcPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new BcPBESecretKeyEncryptorBuilder(
                    org.bouncycastle.openpgp.PGPEncryptedData.AES_256,
                    new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256)
                ).build(new char[0])
            );
            return new PgpFixture(ring.generateSecretKeyRing().getSecretKey());
        }

        byte[] armoredPublicKey() throws Exception {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ArmoredOutputStream armored = new ArmoredOutputStream(bytes)) {
                new PGPPublicKeyRing(
                    java.util.Collections.singletonList(this.secretKey.getPublicKey())
                ).encode(armored);
            }
            return bytes.toByteArray();
        }

        byte[] signDetached(final byte[] payload) throws Exception {
            final PGPPrivateKey privateKey = this.secretKey.extractPrivateKey(
                new org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder(
                    new BcPGPDigestCalculatorProvider()
                ).build(new char[0])
            );
            final PGPSignatureGenerator gen = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(
                    this.secretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256
                )
            );
            gen.init(PGPSignature.BINARY_DOCUMENT, privateKey);
            gen.update(payload);
            final PGPSignature signature = gen.generate();
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ArmoredOutputStream armored = new ArmoredOutputStream(bytes);
                 BCPGOutputStream packetOut = new BCPGOutputStream(armored)) {
                signature.encode(packetOut);
            }
            return bytes.toByteArray();
        }
    }
}
