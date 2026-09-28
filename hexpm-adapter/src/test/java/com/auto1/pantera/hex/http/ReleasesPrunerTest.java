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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.hex.proto.generated.PackageOuterClass;
import com.auto1.pantera.hex.proto.generated.SignedOuterClass;
import com.auto1.pantera.hex.utils.Gzip;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ReleasesPruner}.
 *
 * @since 2.2.9
 */
final class ReleasesPrunerTest {

    /**
     * Registry of the dashed package.
     */
    private static final Key REGISTRY = new Key.From("packages", "qa_app");

    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        final PackageOuterClass.Package.Builder pkg = PackageOuterClass.Package.newBuilder()
            .setName("qa_app").setRepository("hex-local");
        for (final String ver : new String[] {"1.0.0", "2.0.0-rc.1"}) {
            pkg.addReleases(
                PackageOuterClass.Release.newBuilder().setVersion(ver)
                    .setInnerChecksum(ByteString.copyFrom(new byte[] {1}))
                    .setOuterChecksum(ByteString.copyFrom(new byte[] {2}))
            );
            this.asto.save(
                new Key.From("tarballs", "qa_app-" + ver + ".tar"), Content.EMPTY
            ).join();
        }
        this.asto.save(
            ReleasesPrunerTest.REGISTRY,
            new Content.From(
                new Gzip(
                    SignedOuterClass.Signed.newBuilder()
                        .setPayload(ByteString.copyFrom(pkg.build().toByteArray()))
                        .setSignature(ByteString.EMPTY).build().toByteArray()
                ).compress()
            )
        ).join();
    }

    @Test
    void dropsTheReleaseWhoseTarballIsDeleted() throws Exception {
        this.asto.delete(new Key.From("tarballs", "qa_app-2.0.0-rc.1.tar")).join();
        MatcherAssert.assertThat(
            "the package is reported",
            new ReleasesPruner(this.asto).afterDelete("tarballs/qa_app-2.0.0-rc.1.tar").join(),
            new IsEqual<>(Set.of("qa_app"))
        );
        MatcherAssert.assertThat(
            "only the remaining release is listed",
            this.releases(), new IsEqual<>(List.of("1.0.0"))
        );
    }

    @Test
    void removesTheRegistryWhenTheTarballFolderIsDeleted() {
        this.asto.delete(new Key.From("tarballs", "qa_app-1.0.0.tar")).join();
        this.asto.delete(new Key.From("tarballs", "qa_app-2.0.0-rc.1.tar")).join();
        new ReleasesPruner(this.asto).afterDelete("tarballs/").join();
        MatcherAssert.assertThat(
            this.asto.exists(ReleasesPrunerTest.REGISTRY).join(), new IsEqual<>(false)
        );
    }

    @Test
    void leavesTheRegistryAloneWhenTheTarballIsStored() throws Exception {
        new ReleasesPruner(this.asto).afterDelete("tarballs/qa_app-1.0.0.tar").join();
        MatcherAssert.assertThat(this.releases(), new IsEqual<>(List.of("1.0.0", "2.0.0-rc.1")));
    }

    /**
     * Listed releases.
     * @return Versions
     * @throws Exception On error
     */
    private List<String> releases() throws Exception {
        return PackageOuterClass.Package.parseFrom(
            SignedOuterClass.Signed.parseFrom(
                new Gzip(this.asto.value(ReleasesPrunerTest.REGISTRY).join().asBytes())
                    .decompress()
            ).getPayload()
        ).getReleasesList().stream()
            .map(PackageOuterClass.Release::getVersion)
            .collect(Collectors.toList());
    }
}
