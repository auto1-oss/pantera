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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.pypi.http.IndexGenerator;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.security.policy.Policy;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the PyPI yank/unyank lifecycle behind {@link PypiHandler}:
 * the change must reach the served index (B11), and a missing release or
 * repository is a 404, never a silent 204 or a 500 (B88).
 *
 * @since 2.2.9
 */
final class PypiHandlerYankTest {

    /**
     * Repository name.
     */
    private static final String REPO = "pypi";

    /**
     * Repo-scoped storage.
     */
    private Storage storage;

    /**
     * Handler under test.
     */
    private PypiHandler handler;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.handler = new PypiHandler(
            Policy.FREE,
            name -> REPO.equals(name) ? Optional.of(this.storage) : Optional.empty()
        );
        this.release("0.0.1");
        this.release("0.0.2");
        new IndexGenerator(this.storage, new Key.From("qa-pkg"), "/").generate().join();
    }

    @Test
    void yankIsVisibleInTheServedIndexImmediately() {
        MatcherAssert.assertThat(
            "yank of an existing release is applied",
            this.handler.lifecycle(REPO, "QA_Pkg", "0.0.2", true, "bad build"),
            new IsEqual<>(PypiHandler.Outcome.APPLIED)
        );
        MatcherAssert.assertThat(
            "the persisted PEP 691 index carries the yank",
            this.yanked("qa_pkg-0.0.2.tar.gz"),
            new IsEqual<>(Json.createValue("bad build"))
        );
        MatcherAssert.assertThat(
            "the persisted PEP 503 index carries the yank",
            this.html().contains("data-yanked=\"bad build\""),
            new IsEqual<>(true)
        );
    }

    @Test
    void unyankIsVisibleInTheServedIndexImmediately() {
        this.handler.lifecycle(REPO, "qa-pkg", "0.0.2", true, "bad build");
        MatcherAssert.assertThat(
            "unyank of an existing release is applied",
            this.handler.lifecycle(REPO, "qa-pkg", "0.0.2", false, null),
            new IsEqual<>(PypiHandler.Outcome.APPLIED)
        );
        MatcherAssert.assertThat(
            "the persisted index no longer marks the release yanked",
            this.yanked("qa_pkg-0.0.2.tar.gz"),
            new IsEqual<>(JsonValue.FALSE)
        );
    }

    @Test
    void yankOfMissingVersionIsNotFound() {
        MatcherAssert.assertThat(
            this.handler.lifecycle(REPO, "qa-pkg", "9.9.9", true, null),
            new IsEqual<>(PypiHandler.Outcome.NO_FILES)
        );
    }

    @Test
    void yankInUnknownRepositoryIsNotFound() {
        MatcherAssert.assertThat(
            this.handler.lifecycle("no_such_repo", "qa-pkg", "0.0.1", true, null),
            new IsEqual<>(PypiHandler.Outcome.NO_REPOSITORY)
        );
    }

    private void release(final String version) {
        final Key key = new Key.From("qa-pkg", version, "qa_pkg-" + version + ".tar.gz");
        this.storage.save(key, new Content.From(version.getBytes(StandardCharsets.UTF_8)))
            .join();
        PypiSidecar.write(this.storage, key, null, Instant.parse("2026-01-01T00:00:00Z")).join();
    }

    private JsonValue yanked(final String filename) {
        final JsonObject root = Json.createReader(
            new StringReader(
                new String(
                    this.storage.value(new Key.From(".pypi", "qa-pkg", "qa-pkg.json"))
                        .join().asBytes(),
                    StandardCharsets.UTF_8
                )
            )
        ).readObject();
        return root.getJsonArray("files").getValuesAs(JsonObject.class).stream()
            .filter(file -> filename.equals(file.getString("filename")))
            .findFirst().orElseThrow().get("yanked");
    }

    private String html() {
        return new String(
            this.storage.value(new Key.From(".pypi", "qa-pkg", "qa-pkg.html")).join().asBytes(),
            StandardCharsets.UTF_8
        );
    }
}
