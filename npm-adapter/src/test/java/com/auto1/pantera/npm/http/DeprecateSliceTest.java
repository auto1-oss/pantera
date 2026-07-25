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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.npm.JsonFromMeta;
import com.auto1.pantera.npm.PerVersionLayout;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wtf.g4s8.hamcrest.json.JsonHas;
import wtf.g4s8.hamcrest.json.JsonValueIs;

/**
 * Test for {@link DeprecateSlice}.
 * @since 0.8
 */
class DeprecateSliceTest {

    /**
     * Deprecated field name.
     */
    private static final String FIELD = "deprecated";

    /**
     * Test project name.
     */
    private static final String PROJECT = "@hello/simple-npm-project";

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Meta file key.
     */
    private Key meta;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.meta = new Key.From(DeprecateSliceTest.PROJECT, "meta.json");
    }

    @Test
    void addsDeprecateFieldForVersion() {
        this.storage.save(this.meta, this.createMetaJson(false)).join();
        final String value = "This version is deprecated!";
        MatcherAssert.assertThat(
            "Response status is OK",
            new DeprecateSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.PUT, "/@hello%2fsimple-npm-project"
                ),
                Headers.EMPTY,
                new Content.From(
                    Json.createObjectBuilder()
                        .add("name", DeprecateSliceTest.PROJECT)
                        .add(
                            "versions",
                            Json.createObjectBuilder().add(
                                "1.0.1",
                                Json.createObjectBuilder()
                                    .add("name", DeprecateSliceTest.PROJECT)
                                    .add("version", "1.0.1")
                            ).add(
                                "1.0.2",
                                Json.createObjectBuilder()
                                    .add("name", DeprecateSliceTest.PROJECT)
                                    .add("version", "1.0.2")
                                    .add(DeprecateSliceTest.FIELD, value)
                            )
                        ).build().toString().getBytes(StandardCharsets.UTF_8)
                )
            )
        );
        MatcherAssert.assertThat(
            "Meta.json is updated",
            new JsonFromMeta(this.storage, new Key.From(DeprecateSliceTest.PROJECT)).json(),
            Matchers.allOf(
                new JsonHas(
                    "versions",
                    new JsonHas(
                        "1.0.2",
                        new JsonHas(DeprecateSliceTest.FIELD, new JsonValueIs(value))
                    )
                ),
                new JsonHas(
                    "versions",
                    new JsonHas(
                        "1.0.1",
                        new IsNot<>(new JsonHas(DeprecateSliceTest.FIELD, new JsonValueIs(value)))
                    )
                )
            )
        );
    }

    @Test
    void addsDeprecateFieldForVersions() {
        this.storage.save(this.meta, this.createMetaJson(false)).join();
        final String value = "Do not use!";
        MatcherAssert.assertThat(
            "Response status is OK",
            new DeprecateSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.PUT, "/@hello%2fsimple-npm-project"
                ),
                Headers.EMPTY,
                new Content.From(
                    Json.createObjectBuilder()
                        .add("name", DeprecateSliceTest.PROJECT)
                        .add(
                            "versions",
                            Json.createObjectBuilder().add(
                                "1.0.1",
                                Json.createObjectBuilder()
                                    .add("name", DeprecateSliceTest.PROJECT)
                                    .add("version", "1.0.1")
                                    .add(DeprecateSliceTest.FIELD, value)
                            ).add(
                                "1.0.2",
                                Json.createObjectBuilder()
                                    .add("name", DeprecateSliceTest.PROJECT)
                                    .add("version", "1.0.2")
                                    .add(DeprecateSliceTest.FIELD, value)
                            )
                        ).build().toString().getBytes(StandardCharsets.UTF_8)
                )
            )
        );
        MatcherAssert.assertThat(
            "Meta.json is updated",
            new JsonFromMeta(this.storage, new Key.From(DeprecateSliceTest.PROJECT)).json(),
            Matchers.allOf(
                new JsonHas(
                    "versions",
                    new JsonHas(
                        "1.0.2", new JsonHas(DeprecateSliceTest.FIELD, new JsonValueIs(value))
                    )
                ),
                new JsonHas(
                    "versions",
                    new JsonHas(
                        "1.0.1", new JsonHas(DeprecateSliceTest.FIELD, new JsonValueIs(value))
                    )
                )
            )
        );
    }

    @Test
    void deprecatedFieldShouldBeRemovedByEmptyMessage() {
        final String msg = "";
        this.storage.save(this.meta, this.createMetaJson(true)).join();
        MatcherAssert.assertThat(
            "Response status is OK",
            new DeprecateSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.PUT, "/@hello%2fsimple-npm-project"
                ),
                Headers.EMPTY,
                new Content.From(
                    Json.createObjectBuilder()
                        .add("name", DeprecateSliceTest.PROJECT)
                        .add(
                            "versions", Json.createObjectBuilder()
                        .add(
                            "1.0.3",
                            Json.createObjectBuilder()
                                .add("name", DeprecateSliceTest.PROJECT)
                                .add("version", "1.0.3")
                                .add("deprecated", msg)
                            )
                        )
                    .build().toString().getBytes(StandardCharsets.UTF_8)
                )
            )
        );
        MatcherAssert.assertThat(
            "Meta.json is updated",
            new JsonFromMeta(this.storage, new Key.From(DeprecateSliceTest.PROJECT)).json()
                .getJsonObject("versions")
                .getJsonObject("1.0.3")
                .getJsonString(DeprecateSliceTest.FIELD),
            new IsNull<>()
        );
    }

    @Test
    void returnsNotFoundIfMetaIsNotFound() {
        MatcherAssert.assertThat(
            new DeprecateSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.PUT, "/some/project")
            )
        );
    }

    /**
     * Split-brain regression guard (WS4-npm.3): {@code npm deprecate} must
     * take effect on a package published purely through the per-version
     * layout — patching the target {@code .versions/<v>.json} file directly,
     * not a hand-planted {@code meta.json}.
     */
    @Test
    void deprecatesVersionOnPerVersionLayoutPackage() {
        final Key pkg = new Key.From(DeprecateSliceTest.PROJECT);
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        layout.addVersion(
            pkg, "1.0.1",
            Json.createObjectBuilder()
                .add("name", DeprecateSliceTest.PROJECT).add("version", "1.0.1").build()
        ).toCompletableFuture().join();
        layout.addVersion(
            pkg, "1.0.2",
            Json.createObjectBuilder()
                .add("name", DeprecateSliceTest.PROJECT).add("version", "1.0.2").build()
        ).toCompletableFuture().join();
        final String msg = "Danger! Do not use!";
        MatcherAssert.assertThat(
            "Response status is OK",
            new DeprecateSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.PUT, "/@hello%2fsimple-npm-project"),
                Headers.EMPTY,
                new Content.From(
                    Json.createObjectBuilder()
                        .add("name", DeprecateSliceTest.PROJECT)
                        .add(
                            "versions",
                            Json.createObjectBuilder().add(
                                "1.0.2",
                                Json.createObjectBuilder()
                                    .add("name", DeprecateSliceTest.PROJECT)
                                    .add("version", "1.0.2")
                                    .add(DeprecateSliceTest.FIELD, msg)
                            )
                        ).build().toString().getBytes(StandardCharsets.UTF_8)
                )
            )
        );
        MatcherAssert.assertThat(
            "No meta.json crutch is written",
            this.storage.exists(new Key.From(pkg, "meta.json")).join(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "1.0.2 per-version file carries the deprecation message",
            layout.readVersion(pkg, "1.0.2").toCompletableFuture().join()
                .getString(DeprecateSliceTest.FIELD),
            new IsEqual<>(msg)
        );
        MatcherAssert.assertThat(
            "1.0.1 per-version file is untouched",
            layout.readVersion(pkg, "1.0.1").toCompletableFuture().join()
                .containsKey(DeprecateSliceTest.FIELD),
            new IsEqual<>(false)
        );
    }

    private Content createMetaJson(final boolean third) {
        final JsonObjectBuilder versions =
            Json.createObjectBuilder().add(
                "1.0.1", Json.createObjectBuilder()
                    .add("name", DeprecateSliceTest.PROJECT)
                    .add("version", "1.0.1")
            ).add(
                "1.0.2",
                Json.createObjectBuilder()
                    .add("name", DeprecateSliceTest.PROJECT)
                    .add("version", "1.0.2")
            );
        if (third) {
            versions.add(
                "1.0.3",
                Json.createObjectBuilder()
                    .add("name", DeprecateSliceTest.PROJECT)
                    .add("version", "1.0.3")
                    .add("deprecated", "Some deprecated message")
            );
        }
        return new Content.From(
            Json.createObjectBuilder()
                .add("name", DeprecateSliceTest.PROJECT)
                .add("versions", versions)
                .build().toString().getBytes(StandardCharsets.UTF_8)
        );
    }
}
