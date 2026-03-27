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
package com.auto1.pantera.npm.misc;

import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AbbreviatedMetadata}.
 *
 * <p>Validates that the abbreviated packument format preserves all fields
 * required for correct dependency resolution, including peerDependenciesMeta.</p>
 *
 * @since 1.21
 */
final class AbbreviatedMetadataTest {

    @Test
    void preservesPeerDependenciesMeta() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "vitest")
                .add("version", "4.1.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/vitest/-/vitest-4.1.0.tgz")
                    .add("shasum", "abc123")
                    .add("integrity", "sha512-abc")
                    .build())
                .add("peerDependencies", Json.createObjectBuilder()
                    .add("vite", ">=5.0.0")
                    .add("@vitest/ui", "4.1.0")
                    .build())
                .add("peerDependenciesMeta", Json.createObjectBuilder()
                    .add("@vitest/ui", Json.createObjectBuilder()
                        .add("optional", true)
                        .build())
                    .build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("4.1.0");

        assertTrue(
            version.containsKey("peerDependenciesMeta"),
            "peerDependenciesMeta must be present in abbreviated version metadata"
        );
        assertTrue(
            version.getJsonObject("peerDependenciesMeta")
                .getJsonObject("@vitest/ui").getBoolean("optional"),
            "peerDependenciesMeta optional flag must be preserved"
        );
    }

    @Test
    void absentPeerDependenciesMetaStaysAbsent() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "lodash")
                .add("version", "4.17.21")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/lodash/-/lodash-4.17.21.tgz")
                    .add("shasum", "def456")
                    .build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("4.17.21");

        assertFalse(
            version.containsKey("peerDependenciesMeta"),
            "absent peerDependenciesMeta must remain absent (not null, not empty object)"
        );
    }

    @Test
    void emptyPeerDependenciesMetaIsPreserved() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "react")
                .add("version", "18.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/react/-/react-18.0.0.tgz")
                    .add("shasum", "ghi789")
                    .build())
                .add("peerDependenciesMeta", Json.createObjectBuilder().build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("18.0.0");

        assertTrue(
            version.containsKey("peerDependenciesMeta"),
            "empty peerDependenciesMeta object must be preserved (absent vs empty are semantically different)"
        );
        assertEquals(
            0,
            version.getJsonObject("peerDependenciesMeta").size(),
            "empty peerDependenciesMeta must be an empty object, not null"
        );
    }

    @Test
    void preservesBundledDependenciesCanonicalSpelling() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "foo")
                .add("version", "1.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/foo/-/foo-1.0.0.tgz")
                    .add("shasum", "jkl012")
                    .build())
                .add("bundledDependencies", Json.createArrayBuilder()
                    .add("bar")
                    .build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("1.0.0");

        assertTrue(
            version.containsKey("bundledDependencies"),
            "bundledDependencies (canonical npm spelling) must be preserved"
        );
    }

    @Test
    void dropsNonEssentialFields() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "pkg")
                .add("version", "1.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/pkg/-/pkg-1.0.0.tgz")
                    .add("shasum", "mno345")
                    .build())
                .add("description", "This field is non-essential for install")
                .add("keywords", Json.createArrayBuilder().add("test").build())
                .add("repository", Json.createObjectBuilder()
                    .add("type", "git")
                    .add("url", "https://github.com/example/pkg")
                    .build())
                .add("author", Json.createObjectBuilder()
                    .add("name", "Someone")
                    .build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("1.0.0");

        assertFalse(version.containsKey("description"), "description must be dropped");
        assertFalse(version.containsKey("keywords"), "keywords must be dropped");
        assertFalse(version.containsKey("repository"), "repository must be dropped");
        assertFalse(version.containsKey("author"), "author must be dropped");
    }

    @Test
    void preservesAllEssentialFields() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "complex-pkg")
                .add("version", "2.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/complex-pkg/-/complex-pkg-2.0.0.tgz")
                    .add("shasum", "pqr678")
                    .add("integrity", "sha512-pqr")
                    .build())
                .add("dependencies", Json.createObjectBuilder()
                    .add("lodash", "^4.0.0")
                    .build())
                .add("devDependencies", Json.createObjectBuilder()
                    .add("jest", "^29.0.0")
                    .build())
                .add("peerDependencies", Json.createObjectBuilder()
                    .add("react", ">=17.0.0")
                    .build())
                .add("peerDependenciesMeta", Json.createObjectBuilder()
                    .add("react", Json.createObjectBuilder()
                        .add("optional", false)
                        .build())
                    .build())
                .add("optionalDependencies", Json.createObjectBuilder()
                    .add("fsevents", "^2.0.0")
                    .build())
                .add("bin", Json.createObjectBuilder()
                    .add("mypkg", "./bin/mypkg.js")
                    .build())
                .add("engines", Json.createObjectBuilder()
                    .add("node", ">=16.0.0")
                    .build())
                .add("os", Json.createArrayBuilder().add("linux").add("darwin").build())
                .add("cpu", Json.createArrayBuilder().add("x64").add("arm64").build())
                .add("deprecated", "Use new-pkg instead")
                .add("hasInstallScript", true)
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("2.0.0");

        assertTrue(version.containsKey("name"), "name");
        assertTrue(version.containsKey("version"), "version");
        assertTrue(version.containsKey("dist"), "dist");
        assertTrue(version.containsKey("dependencies"), "dependencies");
        assertTrue(version.containsKey("devDependencies"), "devDependencies");
        assertTrue(version.containsKey("peerDependencies"), "peerDependencies");
        assertTrue(version.containsKey("peerDependenciesMeta"), "peerDependenciesMeta");
        assertTrue(version.containsKey("optionalDependencies"), "optionalDependencies");
        assertTrue(version.containsKey("bin"), "bin");
        assertTrue(version.containsKey("engines"), "engines");
        assertTrue(version.containsKey("os"), "os");
        assertTrue(version.containsKey("cpu"), "cpu");
        assertTrue(version.containsKey("deprecated"), "deprecated");
        assertTrue(version.containsKey("hasInstallScript"), "hasInstallScript");
    }

    @Test
    void topLevelFieldsPreserved() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "pkg")
                .add("version", "1.0.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/pkg/-/pkg-1.0.0.tgz")
                    .add("shasum", "abc")
                    .build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();

        assertTrue(abbreviated.containsKey("name"), "top-level name");
        assertTrue(abbreviated.containsKey("dist-tags"), "top-level dist-tags");
        assertTrue(abbreviated.containsKey("versions"), "top-level versions");
        assertTrue(abbreviated.containsKey("modified"), "top-level modified timestamp");
    }

    @Test
    void multiplePeerDependenciesMetaEntriesPreserved() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "vitest")
                .add("version", "4.1.0")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/vitest/-/vitest-4.1.0.tgz")
                    .add("shasum", "abc123")
                    .build())
                .add("peerDependencies", Json.createObjectBuilder()
                    .add("vite", ">=5.0.0")
                    .add("@vitest/ui", "4.1.0")
                    .add("@vitest/browser", "4.1.0")
                    .add("@types/node", ">=20.0.0")
                    .build())
                .add("peerDependenciesMeta", Json.createObjectBuilder()
                    .add("@vitest/ui", Json.createObjectBuilder()
                        .add("optional", true).build())
                    .add("@vitest/browser", Json.createObjectBuilder()
                        .add("optional", true).build())
                    .add("@types/node", Json.createObjectBuilder()
                        .add("optional", true).build())
                    .build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject peerMeta = abbreviated.getJsonObject("versions")
            .getJsonObject("4.1.0")
            .getJsonObject("peerDependenciesMeta");

        assertEquals(3, peerMeta.size(), "all three optional peer entries must be preserved");
        assertTrue(peerMeta.getJsonObject("@vitest/ui").getBoolean("optional"));
        assertTrue(peerMeta.getJsonObject("@vitest/browser").getBoolean("optional"));
        assertTrue(peerMeta.getJsonObject("@types/node").getBoolean("optional"));
    }

    @Test
    void preservesLibcForNativeBinaryPackages() {
        final JsonObject full = buildPackument(
            Json.createObjectBuilder()
                .add("name", "@parcel/watcher")
                .add("version", "2.4.1")
                .add("dist", Json.createObjectBuilder()
                    .add("tarball", "/@parcel/watcher/-/@parcel/watcher-2.4.1.tgz")
                    .add("shasum", "stu901")
                    .build())
                .add("cpu", Json.createArrayBuilder().add("x64").add("arm64").build())
                .add("os", Json.createArrayBuilder().add("linux").build())
                .add("libc", Json.createArrayBuilder().add("glibc").build())
                .build()
        );

        final JsonObject abbreviated = new AbbreviatedMetadata(full).generate();
        final JsonObject version = abbreviated.getJsonObject("versions")
            .getJsonObject("2.4.1");

        assertTrue(
            version.containsKey("libc"),
            "libc must be preserved for native binary packages (Alpine Linux compatibility)"
        );
        assertEquals(
            "glibc",
            version.getJsonArray("libc").getString(0),
            "libc value must be preserved exactly"
        );
    }

    /**
     * Build a minimal packument JSON with one version entry.
     *
     * @param versionMeta Version metadata object
     * @return Full packument JSON
     */
    private static JsonObject buildPackument(final JsonObject versionMeta) {
        final String version = versionMeta.getString("version");
        final String name = versionMeta.getString("name");
        return Json.createObjectBuilder()
            .add("name", name)
            .add("dist-tags", Json.createObjectBuilder()
                .add("latest", version)
                .build())
            .add("versions", Json.createObjectBuilder()
                .add(version, versionMeta)
                .build())
            .add("time", Json.createObjectBuilder()
                .add("modified", "2024-01-15T10:30:00.000Z")
                .add("created", "2020-01-01T00:00:00.000Z")
                .add(version, "2024-01-15T10:30:00.000Z")
                .build())
            .build();
    }
}
