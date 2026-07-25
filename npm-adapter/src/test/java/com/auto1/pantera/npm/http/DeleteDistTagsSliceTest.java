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
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.npm.PerVersionLayout;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Test for {@link DeleteDistTagsSlice}.
 */
class DeleteDistTagsSliceTest {

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
        this.meta = new Key.From("@hello/simple-npm-project", "meta.json");
        this.storage.save(
            this.meta,
            new Content.From(
                String.join(
                    "\n",
                    "{",
                    "\"name\": \"@hello/simple-npm-project\",",
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
    void returnsOkAndUpdatesTags() {
        MatcherAssert.assertThat(
            "Response status is OK",
            new DeleteDistTagsSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.GET, "/-/package/@hello%2fsimple-npm-project/dist-tags/second"
                )
            )
        );
        MatcherAssert.assertThat(
            "Meta.json is updated",
            this.storage.value(this.meta).join().asString(),
            new IsEqual<>(
                "{\"name\":\"@hello/simple-npm-project\",\"dist-tags\":{\"latest\":\"1.0.3\",\"first\":\"1.0.1\"}}"
            )
        );
    }

    @Test
    void returnsNotFoundIfMetaIsNotFound() {
        MatcherAssert.assertThat(
            new DeleteDistTagsSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/-/package/@hello%2ftest-project/dist-tags/second")
            )
        );
    }

    @Test
    void returnsBadRequest() {
        MatcherAssert.assertThat(
            new DeleteDistTagsSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.BAD_REQUEST),
                new RequestLine(RqMethod.GET, "/abc/123")
            )
        );
    }

    /**
     * Split-brain regression guard (WS4-npm.3): {@code npm dist-tag rm} must
     * work for a package published purely through the per-version layout,
     * removing the tag from the durable {@code .dist-tags.json} sidecar.
     */
    @Test
    void removesCustomTagOnPerVersionLayoutPackage() {
        final Key pkg = new Key.From("@hello/published-project");
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        layout.addVersion(
            pkg, "1.0.0",
            Json.createObjectBuilder().add("name", pkg.string()).add("version", "1.0.0").build()
        ).toCompletableFuture().join();
        layout.writeTag(pkg, "beta", "1.0.0").toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Response status is OK",
            new DeleteDistTagsSlice(this.storage),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(
                    RqMethod.GET, "/-/package/@hello%2fpublished-project/dist-tags/beta"
                )
            )
        );
        MatcherAssert.assertThat(
            "Tag no longer present in the sidecar",
            layout.readDistTags(pkg).toCompletableFuture().join().containsKey("beta"),
            new IsEqual<>(false)
        );
    }

}
