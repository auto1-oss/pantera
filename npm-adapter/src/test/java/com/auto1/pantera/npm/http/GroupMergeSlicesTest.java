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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GroupKeysSlice} and {@link GroupSearchSlice}: npm group
 * endpoints whose answer is the union of the members' answers.
 */
final class GroupMergeSlicesTest {

    @Test
    void keysAreTheUnionOfEveryMembersKeys() {
        final List<String> paths = new ArrayList<>();
        final JsonArray keys = GroupMergeSlicesTest.json(
            new GroupKeysSlice(
                List.of("npm-local", "npm-proxy", "npm-down"),
                List.of(
                    GroupMergeSlicesTest.member(paths, 200, "{\"keys\":[{\"keyid\":\"SHA256:local\"}]}"),
                    GroupMergeSlicesTest.member(
                        paths, 200,
                        "{\"keys\":[{\"keyid\":\"SHA256:npmjs\"},{\"keyid\":\"SHA256:local\"}]}"
                    ),
                    GroupMergeSlicesTest.member(paths, 503, "")
                )
            ),
            "/-/npm/v1/keys"
        ).getJsonArray("keys");
        MatcherAssert.assertThat(
            "hosted and upstream keys are both served, once each",
            keys.size(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the hosted member's key is included",
            keys.getJsonObject(0).getString("keyid"), new IsEqual<>("SHA256:local")
        );
        MatcherAssert.assertThat(
            "each member is asked under its own name",
            paths.contains("/npm-local/-/npm/v1/keys"), new IsEqual<>(true)
        );
    }

    @Test
    void searchMergesHostedAndUpstreamResults() {
        final JsonObject body = GroupMergeSlicesTest.json(
            new GroupSearchSlice(
                List.of("npm-local", "npm-proxy"),
                List.of(
                    GroupMergeSlicesTest.member(
                        new ArrayList<>(), 200,
                        "{\"objects\":[{\"package\":{\"name\":\"qa-local\"}}],\"total\":1}"
                    ),
                    GroupMergeSlicesTest.member(
                        new ArrayList<>(), 200,
                        "{\"objects\":[{\"package\":{\"name\":\"qa-local\"}},"
                            + "{\"package\":{\"name\":\"qa-upstream\"}},"
                            + "{\"package\":{\"name\":\"qa-third\"}}],\"total\":1000}"
                    )
                )
            ),
            "/-/v1/search?text=qa&size=2"
        );
        final JsonArray objects = body.getJsonArray("objects");
        MatcherAssert.assertThat(
            "size caps the merged page",
            objects.size(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the hosted package comes first",
            objects.getJsonObject(0).getJsonObject("package").getString("name"),
            new IsEqual<>("qa-local")
        );
        MatcherAssert.assertThat(
            "the upstream package follows, duplicates dropped",
            objects.getJsonObject(1).getJsonObject("package").getString("name"),
            new IsEqual<>("qa-upstream")
        );
    }

    /**
     * Member answering a fixed status and body, recording the paths asked.
     * @param paths Recorded paths
     * @param status Status
     * @param body Body
     * @return Slice
     */
    private static Slice member(final List<String> paths, final int status, final String body) {
        return (line, headers, content) -> {
            synchronized (paths) {
                paths.add(line.uri().getPath());
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(com.auto1.pantera.http.RsStatus.byCode(status))
                    .textBody(body)
                    .build()
            );
        };
    }

    /**
     * GET through a slice and parse the JSON answer.
     * @param slice Slice
     * @param path Path
     * @return JSON object
     */
    private static JsonObject json(final Slice slice, final String path) {
        return Json.createReader(
            new StringReader(
                slice.response(
                    new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
    }
}
