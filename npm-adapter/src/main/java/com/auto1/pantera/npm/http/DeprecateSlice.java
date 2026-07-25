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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PackageNameFromUrl;
import com.auto1.pantera.npm.PerVersionLayout;
import org.apache.commons.lang3.StringUtils;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonPatchBuilder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Slice to handle `npm deprecate` command requests.
 *
 * <p>Patches the {@code deprecated} field directly on the target
 * {@code .versions/<v>.json} file(s) for packages published through the
 * per-version layout. Falls back to a legacy {@code meta.json} patch for
 * packages that predate the per-version layout.</p>
 */
public final class DeprecateSlice implements Slice {
    /**
     * Patter for `referer` header value.
     */
    static final Pattern HEADER = Pattern.compile("deprecate.*");

    /**
     * Deprecated json field name.
     */
    private static final String FIELD = "deprecated";

    /**
     * Abstract storage.
     */
    private final Storage storage;

    /**
     * @param storage Abstract storage
     */
    public DeprecateSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers iterable, Content publisher) {
        final String pkg = new PackageNameFromUrl(line).value();
        final Key packageKey = new Key.From(pkg);
        final PerVersionLayout layout = new PerVersionLayout(this.storage);
        return layout.hasVersions(packageKey).thenCompose(
            hasVersions -> {
                if (hasVersions) {
                    return new Content.From(publisher).asJsonObjectFuture()
                        .thenCompose(
                            body -> this.applyDeprecation(
                                layout, packageKey, body.getJsonObject("versions")
                            )
                        ).thenApply(ignored -> ResponseBuilder.ok().build());
                }
                return this.legacyDeprecate(pkg, publisher);
            }
        ).toCompletableFuture();
    }

    /**
     * Apply the {@code deprecated} field (add or, when the message is empty,
     * remove) to every version's per-version file that the client's body
     * carries a {@code deprecated} entry for.
     *
     * @param layout Per-version layout
     * @param packageKey Package key
     * @param versions Versions object from the client's request body
     * @return Completion stage
     */
    private CompletionStage<Void> applyDeprecation(
        final PerVersionLayout layout, final Key packageKey, final JsonObject versions
    ) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final String version : versions.keySet()) {
            final JsonObject patch = versions.getJsonObject(version);
            if (patch.containsKey(DeprecateSlice.FIELD)) {
                chain = chain.thenCompose(
                    ignored -> this.patchVersion(
                        layout, packageKey, version, patch.getString(DeprecateSlice.FIELD)
                    ).toCompletableFuture()
                );
            }
        }
        return chain;
    }

    /**
     * Read-modify-write a single version's per-version file, adding or
     * removing the {@code deprecated} field.
     *
     * @param layout Per-version layout
     * @param packageKey Package key
     * @param version Version to patch
     * @param message Deprecation message; an empty message removes the field
     * @return Completion stage
     */
    private CompletionStage<Void> patchVersion(
        final PerVersionLayout layout, final Key packageKey,
        final String version, final String message
    ) {
        return layout.readVersion(packageKey, version).thenCompose(
            existing -> {
                if (existing.isEmpty()) {
                    // Version referenced by the client no longer exists — ignore.
                    return CompletableFuture.<Void>completedFuture(null);
                }
                final JsonObjectBuilder builder = Json.createObjectBuilder(existing);
                if (StringUtils.isEmpty(message)) {
                    builder.remove(DeprecateSlice.FIELD);
                } else {
                    builder.add(DeprecateSlice.FIELD, message);
                }
                return layout.writeVersion(packageKey, version, builder.build());
            }
        );
    }

    /**
     * Legacy fallback for packages published before the per-version layout
     * existed: patch {@code deprecated} straight in {@code meta.json}.
     *
     * @param pkg Package name
     * @param publisher Request body
     * @return Completion stage with the response
     */
    private CompletableFuture<Response> legacyDeprecate(final String pkg, final Content publisher) {
        final Key key = new Key.From(pkg, "meta.json");
        return this.storage.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return new Content.From(publisher).asJsonObjectFuture()
                        .thenApply(json -> json.getJsonObject("versions"))
                        .thenCombine(
                            this.storage.value(key)
                                .thenCompose(Content::asJsonObjectFuture),
                            (body, meta) -> DeprecateSlice.deprecate(body, meta).toString()
                        ).thenCompose(
                            str -> this.storage.save(
                                key, new Content.From(str.getBytes(StandardCharsets.UTF_8))
                            ).thenApply(ignored -> ResponseBuilder.ok().build())
                        );
                }
                // Consume request body to prevent Vert.x request leak
                return new Content.From(publisher).asBytesFuture().thenApply(ignored ->
                    ResponseBuilder.notFound().build()
                );
            }
        );
    }

    /**
     * Adds tag deprecated from request body to meta.json.
     * @param versions Versions json
     * @param meta Meta json from storage
     * @return Meta json with added deprecate tags
     */
    private static JsonObject deprecate(final JsonObject versions, final JsonObject meta) {
        final JsonPatchBuilder res = Json.createPatchBuilder();
        final String field = "deprecated";
        final  String path = "/versions/%s/deprecated";
        for (final String version : versions.keySet()) {
            if (versions.getJsonObject(version).containsKey(field)) {
                if (StringUtils.isEmpty(versions.getJsonObject(version).getString(field))) {
                    res.remove(String.format(path, version));
                } else {
                    res.add(
                        String.format(path, version),
                        versions.getJsonObject(version).getString(field)
                    );
                }
            }
        }
        return res.build().apply(meta);
    }
}
