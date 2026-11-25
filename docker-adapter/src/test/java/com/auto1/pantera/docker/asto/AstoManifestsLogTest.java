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
package com.auto1.pantera.docker.asto;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.docker.ManifestReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * What {@link AstoManifests} logs when a manifest is not there.
 *
 * <p>A reference nobody stored is an ordinary miss, on a proxy cache it is
 * the first pull of every image: it was a {@code WARN} without repository,
 * image or reference, 186 records in four days that nobody could act on.
 * It is a {@code DEBUG} record now and says what was asked for. A link
 * whose blob is gone is a real inconsistency and stays a {@code WARN},
 * with the same identity.</p>
 *
 * @since 2.2.14
 */
final class AstoManifestsLogTest {

    private static final String DIGEST =
        "sha256:cb8a924afdf0229ef7515d9e5b3024e23b3eb03ddbba287f4a19c6ac90b8d221";

    private static final String LOGGER = "com.auto1.pantera.docker";

    private Storage storage;

    /**
     * Records of the docker logger. This module's tests run log4j through
     * SLF4J into logback, so a record arrives as its level and the rendered
     * {@code field="value"} pairs.
     */
    private ListAppender<ILoggingEvent> capture;

    /**
     * Level of the logger before the test.
     */
    private ch.qos.logback.classic.Level before;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);
        this.before = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        this.capture = new ListAppender<>();
        this.capture.start();
        logger.addAppender(this.capture);
    }

    @AfterEach
    void tearDown() {
        final Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);
        logger.detachAppender(this.capture);
        logger.setLevel(this.before);
    }

    @Test
    void unknownTagIsAnIdentifiedDebugRecord() {
        this.manifests().get(ManifestReference.from("3.19")).join();
        final ILoggingEvent miss = this.only("manifest_unknown");
        MatcherAssert.assertThat(
            "an ordinary miss is not a warning",
            miss.getLevel(), new IsEqual<>(ch.qos.logback.classic.Level.DEBUG)
        );
        AstoManifestsLogTest.assertIdentified(miss.getFormattedMessage());
    }

    @Test
    void unknownDigestIsReportedAsDigest() {
        this.manifests().get(ManifestReference.from(DIGEST)).join();
        MatcherAssert.assertThat(
            this.only("manifest_unknown").getFormattedMessage(),
            new StringContains(String.format("container.image.hash.all=\"%s\"", DIGEST))
        );
    }

    @Test
    void linkWithoutBlobIsAnIdentifiedWarning() {
        final ManifestReference ref = ManifestReference.from("3.19");
        this.storage.save(
            Layout.manifest("library/alpine", ref),
            new Content.From(DIGEST.getBytes(StandardCharsets.US_ASCII))
        ).join();
        this.manifests().get(ref).join();
        final ILoggingEvent broken = this.only("manifest_blob_missing");
        MatcherAssert.assertThat(
            "a link pointing at nothing is an inconsistency of the stored image",
            broken.getLevel(), new IsEqual<>(ch.qos.logback.classic.Level.WARN)
        );
        AstoManifestsLogTest.assertIdentified(broken.getFormattedMessage());
    }

    private AstoManifests manifests() {
        return (AstoManifests) new AstoDocker("docker-proxy", this.storage)
            .repo("library/alpine").manifests();
    }

    private static void assertIdentified(final String record) {
        MatcherAssert.assertThat(
            "the record names the Pantera repository",
            record, new StringContains("repository.name=\"docker-proxy\"")
        );
        MatcherAssert.assertThat(
            "the record names the image",
            record, new StringContains("container.image.name=\"library/alpine\"")
        );
        MatcherAssert.assertThat(
            "the record names the tag",
            record, new StringContains("container.image.tag=\"3.19\"")
        );
    }

    private ILoggingEvent only(final String reason) {
        final List<ILoggingEvent> found = this.capture.list.stream()
            .filter(
                event -> event.getFormattedMessage()
                    .contains(String.format("event.reason=\"%s\"", reason))
            )
            .toList();
        MatcherAssert.assertThat(
            "exactly one manifest_get record with reason " + reason,
            found.size(), new IsEqual<>(1)
        );
        return found.get(0);
    }
}
