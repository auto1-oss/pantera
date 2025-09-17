/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;

/**
 * Helper to build cooldown HTTP responses.
 */
public final class CooldownResponses {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private CooldownResponses() {
    }

    public static Response forbidden(final CooldownBlock block) {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("repository", block.repoName())
            .add("repositoryType", block.repoType())
            .add("artifact", block.artifact())
            .add("version", block.version())
            .add("reason", block.reason().name().toLowerCase(Locale.US))
            .add("blockedAt", ISO.format(block.blockedAt().atOffset(ZoneOffset.UTC)))
            .add("blockedUntil", ISO.format(block.blockedUntil().atOffset(ZoneOffset.UTC)));
        final JsonArrayBuilder deps = Json.createArrayBuilder();
        block.dependencies().forEach(dep -> deps.add(
            Json.createObjectBuilder()
                .add("artifact", dep.artifact())
                .add("version", dep.version())
        ));
        json.add("dependencies", deps);
        return ResponseBuilder.forbidden()
            .jsonBody(json.build().toString())
            .build();
    }
}
