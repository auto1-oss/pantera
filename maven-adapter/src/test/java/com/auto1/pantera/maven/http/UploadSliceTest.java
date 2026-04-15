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
import com.auto1.pantera.asto.test.ContentIs;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.jcabi.xml.XMLDocument;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

}
