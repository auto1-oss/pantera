/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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

}
