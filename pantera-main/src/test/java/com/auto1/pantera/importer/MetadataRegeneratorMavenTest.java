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
package com.auto1.pantera.importer;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.importer.api.ChecksumPolicy;
import com.auto1.pantera.importer.api.ImportHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the Maven branch of {@link MetadataRegenerator}, which delegates
 * {@code maven-metadata.xml} regeneration to the shared
 * {@link com.auto1.pantera.maven.metadata.MavenMetadataRegenerator} in
 * maven-adapter (single implementation shared with the hosted upload path).
 */
final class MetadataRegeneratorMavenTest {

    /**
     * Regenerating for one freshly-imported jar re-derives the {@code
     * <versions>} listing from every version directory present in storage —
     * not just the one being imported — proving the importer now runs the
     * shared storage-authoritative algorithm rather than trusting a single
     * version. A stale pre-existing metadata listing only one version is
     * overwritten with the complete set.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rederivesVersionSetFromStorage() throws Exception {
        final Storage storage = new InMemoryStorage();
        this.save(storage, "com/acme/example/1.0.0/example-1.0.0.jar", "jar-one");
        this.save(storage, "com/acme/example/1.1.0/example-1.1.0.jar", "jar-two");
        this.save(
            storage,
            "com/acme/example/maven-metadata.xml",
            "<metadata><versioning><versions><version>1.0.0</version>"
                + "</versions></versioning></metadata>"
        );
        final MetadataRegenerator regenerator = new MetadataRegenerator(
            storage, "maven", "my-repo", Optional.empty()
        );
        regenerator.regenerate(
            new Key.From("com/acme/example/1.1.0/example-1.1.0.jar"),
            mavenRequest()
        ).toCompletableFuture().get(30, TimeUnit.SECONDS);
        final String metadata = this.read(storage, "com/acme/example/maven-metadata.xml");
        MatcherAssert.assertThat(
            "earlier version 1.0.0 is retained from storage",
            metadata,
            new StringContains("<version>1.0.0</version>")
        );
        MatcherAssert.assertThat(
            "newly imported version 1.1.0 is present",
            metadata,
            new StringContains("<version>1.1.0</version>")
        );
        MatcherAssert.assertThat(
            "latest is the highest version found in storage",
            metadata,
            new StringContains("<latest>1.1.0</latest>")
        );
        MatcherAssert.assertThat(
            "success counter advanced once",
            regenerator.getSuccessCount(),
            new IsEqual<>(1L)
        );
    }

    /**
     * The shared regenerator also writes checksum sidecars over the freshly
     * generated {@code maven-metadata.xml}, and the importer-specific checksum
     * pass still covers the imported artifact itself. Both must be present
     * after a single regeneration.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void writesMetadataAndArtifactChecksums() throws Exception {
        final Storage storage = new InMemoryStorage();
        this.save(storage, "com/acme/lib/2.0.0/lib-2.0.0.jar", "payload");
        new MetadataRegenerator(storage, "maven", "my-repo", Optional.empty())
            .regenerate(
                new Key.From("com/acme/lib/2.0.0/lib-2.0.0.jar"),
                mavenRequest()
            ).toCompletableFuture().get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "maven-metadata.xml checksum sidecar written by shared regenerator",
            storage.exists(new Key.From("com/acme/lib/maven-metadata.xml.sha1"))
                .get(30, TimeUnit.SECONDS),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "imported artifact checksum sidecar written by importer",
            storage.exists(new Key.From("com/acme/lib/2.0.0/lib-2.0.0.jar.sha1"))
                .get(30, TimeUnit.SECONDS),
            new IsEqual<>(true)
        );
    }

    private static ImportRequest mavenRequest() {
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "maven")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "id-1")
            .add(ImportHeaders.ARTIFACT_NAME, "example")
            .add(ImportHeaders.ARTIFACT_VERSION, "1.1.0")
            .add(ImportHeaders.ARTIFACT_OWNER, "qa")
            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.COMPUTE.name());
        return ImportRequest.parse(
            new RequestLine(RqMethod.PUT, "/.import/my-repo/com/acme/example-1.1.0.jar"),
            headers
        );
    }

    private void save(final Storage storage, final String key, final String body) throws Exception {
        storage.save(
            new Key.From(key),
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).get(30, TimeUnit.SECONDS);
    }

    private String read(final Storage storage, final String key) throws Exception {
        return new String(
            storage.value(new Key.From(key))
                .thenCompose(Content::asBytesFuture)
                .get(30, TimeUnit.SECONDS),
            StandardCharsets.UTF_8
        );
    }
}
