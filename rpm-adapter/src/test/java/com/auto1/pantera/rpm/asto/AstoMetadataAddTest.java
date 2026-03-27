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
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.StandardNamingPolicy;
import com.auto1.pantera.rpm.TestRpm;
import com.auto1.pantera.rpm.hm.IsXmlEqual;
import com.auto1.pantera.rpm.meta.XmlPackage;
import com.auto1.pantera.rpm.pkg.FilePackage;
import com.auto1.pantera.rpm.pkg.FilePackageHeader;
import com.auto1.pantera.rpm.pkg.Package;
import com.jcabi.matchers.XhtmlMatchers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.cactoos.list.ListOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link AstoMetadataAdd}.
 * @since 1.10
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AstoMetadataAddTest {

    /**
     * Digest fot test.
     */
    private static final Digest DGST = Digest.SHA256;

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Reader of metadata bytes.
     */
    private MetadataBytes mbytes;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.mbytes = new MetadataBytes(this.storage);
    }

    @Test
    void addsEmptyFiles() throws IOException {
        final Key temp = new AstoMetadataAdd(
            this.storage,
            new RepoConfig.Simple(AstoMetadataAddTest.DGST, StandardNamingPolicy.SHA256, false)
        ).perform(Collections.emptyList()).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Failed to generate 4 items: metadatas and checksums",
            this.storage.list(Key.ROOT).join(),
            Matchers.iterableWithSize(4)
        );
        MatcherAssert.assertThat(
            "Failed to generate empty primary xml",
            new String(
                this.mbytes.value(temp, XmlPackage.PRIMARY),
                StandardCharsets.UTF_8
            ),
            XhtmlMatchers.hasXPaths("/*[local-name()='metadata' and @packages='0']")
        );
        MatcherAssert.assertThat(
            "Failed to generate empty other xml",
            new String(
                this.mbytes.value(temp, XmlPackage.OTHER),
                StandardCharsets.UTF_8
            ),
            XhtmlMatchers.hasXPaths("/*[local-name()='otherdata' and @packages='0']")
        );
    }

    @Test
    void addsPackagesMetadata() throws IOException {
        new TestResource("AstoMetadataAddTest/primary.xml.gz")
            .saveTo(this.storage, new Key.From("repodata", "primary.xml.gz"));
        new TestResource("AstoMetadataAddTest/other.xml.gz")
            .saveTo(this.storage, new Key.From("repodata", "other.xml.gz"));
        new TestResource("AstoMetadataAddTest/filelists.xml.gz")
            .saveTo(this.storage, new Key.From("repodata", "filelists.xml.gz"));
        final TestRpm.Libdeflt libdeflt = new TestRpm.Libdeflt();
        final TestRpm.Abc abc = new TestRpm.Abc();
        final Key temp = new AstoMetadataAdd(
            this.storage,
            new RepoConfig.Simple(AstoMetadataAddTest.DGST, StandardNamingPolicy.SHA256, true)
        ).perform(
            new ListOf<Package.Meta>(
                new FilePackage.Headers(
                    new FilePackageHeader(libdeflt.path()).header(),
                    libdeflt.path(), Digest.SHA256, libdeflt.path().getFileName().toString()
                ),
                new FilePackage.Headers(
                    new FilePackageHeader(abc.path()).header(),
                    abc.path(), Digest.SHA256, abc.path().getFileName().toString()
                )
            )
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Failed to generate 6 items: metadatas and checksums",
            this.storage.list(temp).join(),
            Matchers.iterableWithSize(6)
        );
        MatcherAssert.assertThat(
            "Failed to generate correct primary xml",
            new TestResource("AstoMetadataAddTest/primary-res.xml").asPath(),
            new IsXmlEqual(
                this.mbytes.value(temp, XmlPackage.PRIMARY)
            )
        );
        MatcherAssert.assertThat(
            "Failed to generate correct other xml",
            new TestResource("AstoMetadataAddTest/other-res.xml").asPath(),
            new IsXmlEqual(
                this.mbytes.value(temp, XmlPackage.OTHER)
            )
        );
        MatcherAssert.assertThat(
            "Failed to generate correct filelists xml",
            new TestResource("AstoMetadataAddTest/filelists-res.xml").asPath(),
            new IsXmlEqual(
                this.mbytes.value(temp, XmlPackage.FILELISTS)
            )
        );
        this.checksumCheck(temp, XmlPackage.PRIMARY);
        this.checksumCheck(temp, XmlPackage.OTHER);
        this.checksumCheck(temp, XmlPackage.FILELISTS);
    }

    @Test
    void addItemsToLargeFiles() throws IOException {
        new TestResource("AstoMetadataAddTest/large-primary.xml.gz")
            .saveTo(this.storage, new Key.From("repodata", "primary.xml.gz"));
        new TestResource("AstoMetadataAddTest/large-other.xml.gz")
            .saveTo(this.storage, new Key.From("repodata", "other.xml.gz"));
        new TestResource("AstoMetadataAddTest/large-filelists.xml.gz")
            .saveTo(this.storage, new Key.From("repodata", "filelists.xml.gz"));
        final TestRpm.Libdeflt libdeflt = new TestRpm.Libdeflt();
        final TestRpm.Abc abc = new TestRpm.Abc();
        final Key temp = new AstoMetadataAdd(
            this.storage,
            new RepoConfig.Simple(AstoMetadataAddTest.DGST, StandardNamingPolicy.SHA256, true)
        ).perform(
            new ListOf<Package.Meta>(
                new FilePackage.Headers(
                    new FilePackageHeader(libdeflt.path()).header(),
                    libdeflt.path(), Digest.SHA256, libdeflt.path().getFileName().toString()
                ),
                new FilePackage.Headers(
                    new FilePackageHeader(abc.path()).header(),
                    abc.path(), Digest.SHA256, abc.path().getFileName().toString()
                )
            )
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Failed to generate 6 items: metadatas and checksums",
            this.storage.list(temp).join(),
            Matchers.iterableWithSize(6)
        );
    }

    private void checksumCheck(final Key res, final XmlPackage other) {
        MatcherAssert.assertThat(
            String.format("Checksum and size are expected to be stored for %s", other.name()),
            new String(
                new BlockingStorage(this.storage)
                    .value(
                        new Key.From(
                            res,
                            String.format("%s.%s", other.name(), AstoMetadataAddTest.DGST.name())
                        )
                    ),
                StandardCharsets.UTF_8
            ),
            Matchers.matchesPattern("[0-9a-z]* \\d+")
        );
    }

}
