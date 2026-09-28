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
package com.auto1.pantera.conda.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link RepodataPruner}.
 *
 * @since 2.2.9
 */
final class RepodataPrunerTest {

    /**
     * Repodata of noarch.
     */
    private static final Key REPODATA = new Key.From("noarch", "repodata.json");

    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        this.asto.save(
            RepodataPrunerTest.REPODATA,
            new Content.From(
                String.join(
                    "",
                    "{\"info\":{\"subdir\":\"noarch\"},",
                    "\"packages\":{",
                    "\"pkg-1.0-0.tar.bz2\":{\"name\":\"pkg\",\"version\":\"1.0\",\"sha256\":\"a\"},",
                    "\"pkg-2.0-0.tar.bz2\":{\"name\":\"pkg\",\"version\":\"2.0\",\"sha256\":\"b\"}",
                    "},\"packages.conda\":{",
                    "\"other-1.0-0.conda\":{\"name\":\"other\",\"version\":\"1.0\",\"sha256\":\"c\"}",
                    "},\"repodata_version\":1}"
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        for (final String file : new String[] {
            "pkg-1.0-0.tar.bz2", "pkg-2.0-0.tar.bz2", "other-1.0-0.conda",
        }) {
            this.asto.save(new Key.From("noarch", file), Content.EMPTY).join();
        }
    }

    @Test
    void dropsTheDeletedTarballFromRepodata() {
        this.asto.delete(new Key.From("noarch", "pkg-1.0-0.tar.bz2")).join();
        MatcherAssert.assertThat(
            "the removed package name is reported",
            new RepodataPruner(this.asto).afterDelete("noarch/pkg-1.0-0.tar.bz2").join(),
            new IsEqual<>(Set.of("pkg"))
        );
        final JsonObject json = this.repodata();
        MatcherAssert.assertThat(
            "only the remaining tarball is listed",
            json.getJsonObject("packages").keySet(),
            new IsEqual<>(Set.of("pkg-2.0-0.tar.bz2"))
        );
        MatcherAssert.assertThat(
            ".conda packages are untouched",
            json.getJsonObject("packages.conda").keySet(),
            new IsEqual<>(Set.of("other-1.0-0.conda"))
        );
        MatcherAssert.assertThat(
            "the other top-level fields are kept",
            json.getInt("repodata_version"), new IsEqual<>(1)
        );
    }

    @Test
    void dropsTheDeletedCondaPackageFromRepodata() {
        this.asto.delete(new Key.From("noarch", "other-1.0-0.conda")).join();
        new RepodataPruner(this.asto).afterDelete("/noarch/other-1.0-0.conda").join();
        MatcherAssert.assertThat(
            this.repodata().getJsonObject("packages.conda").keySet(),
            new IsEqual<>(Set.of())
        );
    }

    @Test
    void dropsEveryMissingPackageOnAFolderDeleteInsideTheSubdir() {
        this.asto.delete(new Key.From("noarch", "pkg-1.0-0.tar.bz2")).join();
        this.asto.delete(new Key.From("noarch", "pkg-2.0-0.tar.bz2")).join();
        new RepodataPruner(this.asto).afterDelete("noarch").join();
        MatcherAssert.assertThat(
            this.repodata().getJsonObject("packages").keySet(),
            new IsEqual<>(Set.of())
        );
    }

    @Test
    void leavesRepodataAloneWhenEveryPackageIsPresent() {
        final byte[] before = this.asto.value(RepodataPrunerTest.REPODATA).join()
            .asBytes();
        MatcherAssert.assertThat(
            "nothing is reported",
            new RepodataPruner(this.asto).afterDelete("noarch/unrelated.txt").join(),
            new IsEqual<>(Set.of())
        );
        MatcherAssert.assertThat(
            "the file is not rewritten",
            new String(
                this.asto.value(RepodataPrunerTest.REPODATA).join().asBytes(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(new String(before, StandardCharsets.UTF_8))
        );
    }

    @Test
    void ignoresASubdirWithoutRepodata() {
        MatcherAssert.assertThat(
            new RepodataPruner(this.asto).afterDelete("linux-64/x-1.0-0.tar.bz2").join(),
            new IsEqual<>(Set.of())
        );
    }

    /**
     * Stored noarch repodata.
     * @return Json
     */
    private JsonObject repodata() {
        return Json.createReader(
            new StringReader(this.asto.value(RepodataPrunerTest.REPODATA).join().asString())
        ).readObject();
    }
}
