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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.PerVersionLayout;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Test for {@link GetDistTagsSlice}.
 */
class GetDistTagsSliceTest {

    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.storage.save(
            new Key.From("@hello/simple-npm-project", "meta.json"),
            new Content.From(
                String.join(
                    "\n",
                    "{",
                    "\"dist-tags\": {",
                    "    \"latest\": \"1.0.3\",",
                    "    \"second\": \"1.0.2\",",
                    "    \"first\": \"1.0.1\"",
                    "  }",
                    "}"
                ).getBytes(StandardCharsets.UTF_8)
            )
        ).join();
    }

    @Test
    void readsDistTagsFromMeta() {
        Assertions.assertEquals(
            "{\"latest\":\"1.0.3\",\"second\":\"1.0.2\",\"first\":\"1.0.1\"}",
            new GetDistTagsSlice(this.storage).response(
                new RequestLine(RqMethod.GET, "/-/package/@hello%2fsimple-npm-project/dist-tags"),
                Headers.EMPTY, Content.EMPTY
            ).join().body().asString()
        );
    }

    @Test
    void returnsNotFoundIfMetaIsNotFound() {
        Assertions.assertEquals(
            RsStatus.NOT_FOUND,
            new GetDistTagsSlice(this.storage).response(
                new RequestLine(RqMethod.GET, "/-/package/@hello%2fanother-npm-project/dist-tags"),
                Headers.EMPTY, Content.EMPTY
            ).join().status()
        );
    }

    /**
     * The split-brain regression guard (WS4-npm.3): dist-tag reads must work
     * for a package published purely through the per-version layout — no
     * hand-planted {@code meta.json} crutch — proving custom tags set at
     * publish time surface correctly.
     */
    @Test
    void readsDistTagsFromPerVersionLayoutWithoutMetaJsonCrutch() {
        final Key pkg = new Key.From("@hello/published-project");
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        layout.addVersion(
            pkg, "1.0.0",
            Json.createObjectBuilder().add("name", pkg.string()).add("version", "1.0.0").build()
        ).toCompletableFuture().join();
        layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("latest", "1.0.0").build()
        ).toCompletableFuture().join();
        layout.addVersion(
            pkg, "1.1.0-beta.1",
            Json.createObjectBuilder()
                .add("name", pkg.string()).add("version", "1.1.0-beta.1").build()
        ).toCompletableFuture().join();
        layout.mergeDistTags(
            pkg, Json.createObjectBuilder().add("beta", "1.1.0-beta.1").build()
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "meta.json is never written by this flow",
            this.storage.exists(new Key.From(pkg, "meta.json")).join(),
            new IsEqual<>(false)
        );
        final javax.json.JsonObject tags = Json.createReader(
            new java.io.StringReader(
                new GetDistTagsSlice(this.storage).response(
                    new RequestLine(
                        RqMethod.GET, "/-/package/@hello%2fpublished-project/dist-tags"
                    ),
                    Headers.EMPTY, Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(
            "latest survives from publish",
            tags.getString("latest"),
            new IsEqual<>("1.0.0")
        );
        MatcherAssert.assertThat(
            "custom beta tag surfaces alongside latest",
            tags.getString("beta"),
            new IsEqual<>("1.1.0-beta.1")
        );
    }

}
