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
package com.auto1.pantera.pypi.http;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SimpleJsonRenderer}.
 */
class SimpleJsonRendererTest {

    @Test
    void rendersBasicPackageJson() {
        final Instant uploadTime = Instant.parse("2024-03-15T10:30:00Z");
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "mylib-1.0.0-py3-none-any.whl",
            "https://example.com/packages/mylib-1.0.0-py3-none-any.whl",
            "abc123def456",
            ">=3.8",
            uploadTime,
            false,
            Optional.empty(),
            Optional.empty()
        );
        final String json = SimpleJsonRenderer.render("mylib", List.of(entry));
        final JsonObject root = Json.createReader(new StringReader(json)).readObject();
        MatcherAssert.assertThat(
            root.getJsonObject("meta").getString("api-version"),
            new IsEqual<>("1.1")
        );
        MatcherAssert.assertThat(
            root.getString("name"),
            new IsEqual<>("mylib")
        );
        final JsonObject file = root.getJsonArray("files").getJsonObject(0);
        MatcherAssert.assertThat(
            file.getString("filename"),
            new IsEqual<>("mylib-1.0.0-py3-none-any.whl")
        );
        MatcherAssert.assertThat(
            file.getJsonObject("hashes").getString("sha256"),
            new IsEqual<>("abc123def456")
        );
        MatcherAssert.assertThat(
            file.getString("requires-python"),
            new IsEqual<>(">=3.8")
        );
        MatcherAssert.assertThat(
            file.getString("upload-time"),
            new IsEqual<>(uploadTime.toString())
        );
        MatcherAssert.assertThat(
            file.getBoolean("yanked"),
            new IsEqual<>(false)
        );
    }

    @Test
    void omitsOptionalFieldsWhenEmpty() {
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "mylib-1.0.0.tar.gz",
            "https://example.com/packages/mylib-1.0.0.tar.gz",
            "deadbeef",
            "",
            null,
            false,
            Optional.empty(),
            Optional.empty()
        );
        final String json = SimpleJsonRenderer.render("mylib", List.of(entry));
        final JsonObject file = Json.createReader(new StringReader(json))
            .readObject()
            .getJsonArray("files")
            .getJsonObject(0);
        MatcherAssert.assertThat(
            file.containsKey("requires-python"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            file.containsKey("upload-time"),
            new IsEqual<>(false)
        );
    }

    @Test
    void truncatesNanosecondUploadTimeToPep700Format() {
        // PEP 700 mandates "yyyy-mm-ddThh:mm:ss.ffffffZ" — max 6 fractional
        // digits, Z suffix. Python's datetime.fromisoformat rejects any
        // fraction wider than 6 digits on every CPython version through
        // 3.13, which breaks pip's parse_links. Regression for the
        // 9-digit filesystem-creationTime leak.
        final Instant nanos = Instant.parse("2026-03-24T19:38:57Z").plusNanos(874_672_722L);
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "pkg-1.0.0-py3-none-any.whl",
            "https://example.com/pkg-1.0.0-py3-none-any.whl",
            "cafebabe",
            null,
            nanos,
            false,
            Optional.empty(),
            Optional.empty()
        );
        final JsonObject file = Json.createReader(new StringReader(
            SimpleJsonRenderer.render("pkg", List.of(entry))
        )).readObject().getJsonArray("files").getJsonObject(0);
        final String uploadTime = file.getString("upload-time");
        MatcherAssert.assertThat(
            "upload-time must match PEP 700 format: yyyy-mm-ddThh:mm:ss[.ffffff]Z "
                + "(max 6 fractional digits, Z suffix). Got: " + uploadTime,
            Pattern.matches(
                "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z$",
                uploadTime
            ),
            new IsEqual<>(true)
        );
        // Sanity: must be the microsecond-truncated value, not 9 digits.
        MatcherAssert.assertThat(
            "upload-time truncated to microseconds should preserve the top 6 digits",
            uploadTime,
            new IsEqual<>("2026-03-24T19:38:57.874672Z")
        );
    }

    @Test
    void rendersYankedPackage() {
        final SimpleJsonRenderer.FileEntry entry = new SimpleJsonRenderer.FileEntry(
            "mylib-0.9.0-py3-none-any.whl",
            "https://example.com/packages/mylib-0.9.0-py3-none-any.whl",
            "cafebabe",
            null,
            null,
            true,
            Optional.of("Security vulnerability"),
            Optional.empty()
        );
        final String json = SimpleJsonRenderer.render("mylib", List.of(entry));
        final JsonObject file = Json.createReader(new StringReader(json))
            .readObject()
            .getJsonArray("files")
            .getJsonObject(0);
        // PEP 691: yanked is a string (reason) when true, boolean
        // false when not yanked. Previously this was a boolean true +
        // a separate "yanked-reason" key, which was non-compliant.
        MatcherAssert.assertThat(
            file.getString("yanked"),
            new IsEqual<>("Security vulnerability")
        );
        MatcherAssert.assertThat(
            "yanked-reason key must not exist — PEP 691 encodes the "
                + "reason in the yanked field itself",
            file.containsKey("yanked-reason"),
            new IsEqual<>(false)
        );
    }
}
