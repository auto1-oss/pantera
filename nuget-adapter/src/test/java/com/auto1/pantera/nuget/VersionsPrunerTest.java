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
package com.auto1.pantera.nuget;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import javax.json.Json;
import javax.json.JsonString;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link VersionsPruner}.
 *
 * @since 2.2.9
 */
final class VersionsPrunerTest {

    /**
     * Versions registry.
     */
    private static final Key INDEX = new Key.From("qa.pkg", "index.json");

    /**
     * Storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
        for (final String ver : new String[] {"1.0.0", "2.0.0"}) {
            this.asto.save(
                new Key.From("qa.pkg", ver, "qa.pkg." + ver + ".nupkg"), Content.EMPTY
            ).join();
            this.asto.save(new Key.From("qa.pkg", ver, "qa.pkg.nuspec"), Content.EMPTY).join();
        }
        this.asto.save(
            VersionsPrunerTest.INDEX,
            new Content.From(
                "{\"versions\":[\"1.0.0\",\"2.0.0\"]}".getBytes(StandardCharsets.UTF_8)
            )
        ).join();
    }

    @Test
    void dropsAVersionWhoseFolderIsDeleted() {
        this.asto.delete(new Key.From("qa.pkg", "2.0.0", "qa.pkg.2.0.0.nupkg")).join();
        this.asto.delete(new Key.From("qa.pkg", "2.0.0", "qa.pkg.nuspec")).join();
        new VersionsPruner(this.asto).afterDelete("qa.pkg/2.0.0").join();
        MatcherAssert.assertThat(this.versions(), new IsEqual<>(List.of("1.0.0")));
    }

    @Test
    void dropsAVersionWhosePackageFileIsDeleted() {
        this.asto.delete(new Key.From("qa.pkg", "1.0.0", "qa.pkg.1.0.0.nupkg")).join();
        new VersionsPruner(this.asto).afterDelete("/qa.pkg/1.0.0/qa.pkg.1.0.0.nupkg").join();
        MatcherAssert.assertThat(this.versions(), new IsEqual<>(List.of("2.0.0")));
    }

    @Test
    void removesTheRegistryWhenNoVersionIsLeft() {
        for (final String ver : new String[] {"1.0.0", "2.0.0"}) {
            this.asto.delete(new Key.From("qa.pkg", ver, "qa.pkg." + ver + ".nupkg")).join();
        }
        new VersionsPruner(this.asto).afterDelete("qa.pkg/1.0.0").join();
        MatcherAssert.assertThat(
            this.asto.exists(VersionsPrunerTest.INDEX).join(), new IsEqual<>(false)
        );
    }

    /**
     * Listed versions.
     * @return Versions
     */
    private List<String> versions() {
        return Json.createReader(
            new java.io.StringReader(this.asto.value(VersionsPrunerTest.INDEX).join().asString())
        ).readObject().getJsonArray("versions").getValuesAs(JsonString.class).stream()
            .map(JsonString::getString)
            .collect(Collectors.toList());
    }
}
