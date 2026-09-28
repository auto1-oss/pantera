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
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MetadataVersionPruner}.
 * @since 2.2.9
 */
final class MetadataVersionPrunerTest {

    /**
     * Artifact directory.
     */
    private static final String DIR = "com/qa/lib";

    /**
     * Storage.
     */
    private InMemoryStorage storage;

    /**
     * Blocking view.
     */
    private BlockingStorage blocking;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.blocking = new BlockingStorage(this.storage);
        this.blocking.save(
            new Key.From(DIR, "maven-metadata.xml"),
            String.join(
                "",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><metadata>",
                "<groupId>com.qa</groupId><artifactId>lib</artifactId><versioning>",
                "<latest>0.0.3</latest><release>0.0.3</release><versions>",
                "<version>0.0.1</version><version>0.0.2</version><version>0.0.3</version>",
                "</versions><lastUpdated>20200101000000</lastUpdated></versioning></metadata>"
            ).getBytes(StandardCharsets.UTF_8)
        );
        for (final String ver : List.of("0.0.1", "0.0.2", "0.0.3")) {
            this.blocking.save(
                new Key.From(DIR, ver, String.format("lib-%s.jar", ver)), new byte[]{1}
            );
        }
    }

    @Test
    void dropsDeletedVersionAndRewritesChecksum() throws Exception {
        this.blocking.delete(new Key.From(DIR, "0.0.2", "lib-0.0.2.jar"));
        final List<String> changed = new MetadataVersionPruner(this.storage)
            .afterDelete(DIR + "/0.0.2").join();
        final byte[] meta = this.blocking.value(new Key.From(DIR, "maven-metadata.xml"));
        final String xml = new String(meta, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "the changed artifact is reported by its dotted name",
            changed, new IsEqual<>(List.of("com.qa.lib"))
        );
        MatcherAssert.assertThat(
            "the deleted version is no longer listed",
            xml, new IsNot<>(new StringContains("0.0.2"))
        );
        MatcherAssert.assertThat(
            "the remaining versions stay listed",
            xml, new StringContains("<version>0.0.1</version>")
        );
        MatcherAssert.assertThat(
            "the sha1 sidecar matches the rewritten metadata",
            new String(
                this.blocking.value(new Key.From(DIR, "maven-metadata.xml.sha1")),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(meta)))
        );
    }

    @Test
    void movesLatestAndReleaseWhenTheHighestVersionIsDeleted() {
        this.blocking.delete(new Key.From(DIR, "0.0.3", "lib-0.0.3.jar"));
        new MetadataVersionPruner(this.storage)
            .afterDelete(DIR + "/0.0.3/lib-0.0.3.jar").join();
        final String xml = new String(
            this.blocking.value(new Key.From(DIR, "maven-metadata.xml")), StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "latest points at the highest remaining version",
            xml, new StringContains("<latest>0.0.2</latest>")
        );
        MatcherAssert.assertThat(
            "release points at the highest remaining version",
            xml, new StringContains("<release>0.0.2</release>")
        );
    }

    @Test
    void removesMetadataWhenNoVersionIsLeft() {
        for (final String ver : List.of("0.0.1", "0.0.2", "0.0.3")) {
            this.blocking.delete(new Key.From(DIR, ver, String.format("lib-%s.jar", ver)));
        }
        new MetadataVersionPruner(this.storage).afterDelete(DIR + "/0.0.1").join();
        MatcherAssert.assertThat(
            this.blocking.exists(new Key.From(DIR, "maven-metadata.xml")),
            new IsEqual<>(false)
        );
    }

    @Test
    void removesTheChecksumSidecarsOfADeletedFile() {
        final Key jar = new Key.From(DIR, "0.0.1", "lib-0.0.1.jar");
        this.blocking.save(new Key.From(jar.string() + ".sha1"), new byte[]{1});
        this.blocking.delete(jar);
        new MetadataVersionPruner(this.storage).afterDelete(jar.string()).join();
        MatcherAssert.assertThat(
            this.blocking.exists(new Key.From(jar.string() + ".sha1")),
            new IsEqual<>(false)
        );
    }

    @Test
    void leavesMetadataAloneWhenEveryVersionStillExists() {
        final List<String> changed = new MetadataVersionPruner(this.storage)
            .afterDelete(DIR + "/0.0.2/lib-0.0.2-sources.jar").join();
        MatcherAssert.assertThat(changed, new IsEqual<>(List.of()));
    }
}
