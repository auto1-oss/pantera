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
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A test for gem submit operation.
 */
final class ApiGetSliceTest {
    @Test
    void queryResultsInOkResponse(@TempDir final Path tmp) throws IOException {
        new TestResource("gviz-0.3.5.gem").saveTo(tmp.resolve("./gviz-0.3.5.gem"));
        Response resp = new ApiGetSlice(new FileStorage(tmp))
            .response(
                new RequestLine(RqMethod.GET, "/api/v1/gems/gviz.json"),
                Headers.EMPTY, Content.EMPTY
            ).join();
        Assertions.assertTrue(
            resp.body().asString().contains("\"name\":\"gviz\""),
            resp.body().asString()
        );
    }

    @Test
    void returnsValidResponseForYamlRequest(@TempDir final Path tmp) throws IOException {
        new TestResource("gviz-0.3.5.gem").saveTo(tmp.resolve("./gviz-0.3.5.gem"));
        Response resp = new ApiGetSlice(new FileStorage(tmp)).response(
            new RequestLine(RqMethod.GET, "/api/v1/gems/gviz.yaml"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        Assertions.assertEquals(
            "text/x-yaml; charset=utf-8",
            resp.headers().single("Content-Type").getValue()
        );
        Assertions.assertTrue(
            resp.body().asString().contains("name: gviz")
        );
    }
}

