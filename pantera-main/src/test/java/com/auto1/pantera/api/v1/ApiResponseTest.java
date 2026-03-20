/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ApiResponseTest {

    @Test
    void createsErrorResponse() {
        final JsonObject err = ApiResponse.error(404, "NOT_FOUND", "Repo not found");
        assertThat(err.getInteger("status"), is(404));
        assertThat(err.getString("error"), is("NOT_FOUND"));
        assertThat(err.getString("message"), is("Repo not found"));
    }

    @Test
    void createsPaginatedResponse() {
        final JsonArray items = new JsonArray().add("a").add("b");
        final JsonObject page = ApiResponse.paginated(items, 0, 20, 42);
        assertThat(page.getJsonArray("items").size(), is(2));
        assertThat(page.getInteger("page"), is(0));
        assertThat(page.getInteger("size"), is(20));
        assertThat(page.getInteger("total"), is(42));
        assertThat(page.getBoolean("hasMore"), is(true));
    }

    @Test
    void paginatedHasMoreFalseOnLastPage() {
        final JsonArray items = new JsonArray().add("x");
        final JsonObject page = ApiResponse.paginated(items, 2, 20, 41);
        assertThat(page.getBoolean("hasMore"), is(false));
    }

    @Test
    void slicesList() {
        final List<String> all = List.of("a", "b", "c", "d", "e");
        final JsonArray items = ApiResponse.sliceToArray(all, 1, 2);
        assertThat(items.size(), is(2));
        assertThat(items.getString(0), is("c"));
        assertThat(items.getString(1), is("d"));
    }

    @Test
    void slicesListBeyondEnd() {
        final List<String> all = List.of("a", "b");
        final JsonArray items = ApiResponse.sliceToArray(all, 1, 20);
        assertThat(items.size(), is(0));
    }

    @Test
    void clampsPageSize() {
        assertThat(ApiResponse.clampSize(200), is(100));
        assertThat(ApiResponse.clampSize(-5), is(20));
        assertThat(ApiResponse.clampSize(50), is(50));
    }

    @Test
    void parsesIntParam() {
        assertThat(ApiResponse.intParam("10", 20), is(10));
        assertThat(ApiResponse.intParam(null, 20), is(20));
        assertThat(ApiResponse.intParam("abc", 20), is(20));
    }
}
