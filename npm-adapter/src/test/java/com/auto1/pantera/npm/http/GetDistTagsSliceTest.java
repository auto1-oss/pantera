/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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

}
