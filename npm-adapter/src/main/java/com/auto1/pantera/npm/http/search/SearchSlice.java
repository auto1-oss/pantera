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
package com.auto1.pantera.npm.http.search;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.misc.DescSortedVersions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

/**
 * Search slice - handles npm search.
 * Endpoint: GET /-/v1/search?text={query}&size={n}&from={offset}
 *
 * <p>Backed by the shared, already-populated {@link ArtifactIndex} (the same
 * index every publish writes to via {@code SyncArtifactIndexer}) instead of a
 * dedicated in-memory index that nothing ever populated.</p>
 *
 * <p>The index holds one row per published version; the npm search schema
 * wants one object per package, and the npm CLI reads
 * {@code package.maintainers} without a guard. Hits are therefore collapsed
 * to one object per package (represented by its highest stable version, or
 * its highest version when all are prereleases) and every object carries
 * {@code maintainers}, {@code date} and {@code links}. When the repository
 * storage is available the representative version's {@code description},
 * {@code keywords} and {@code maintainers} are read from its stored
 * manifest.</p>
 *
 * @since 1.1
 */
public final class SearchSlice implements Slice {

    /**
     * Query parameter pattern.
     */
    private static final Pattern QUERY_PATTERN = Pattern.compile(
        "text=([^&]+)(?:&size=(\\d+))?(?:&from=(\\d+))?"
    );

    /**
     * Default result size.
     */
    private static final int DEFAULT_SIZE = 20;

    /**
     * How many index rows (versions) are scanned to build the per-package
     * result set; paging ({@code from}/{@code size}) applies to packages.
     */
    private static final int MAX_SCANNED_VERSIONS = 1000;

    /**
     * Repo type base filter passed to the index (matches npm, npm-proxy, npm-group).
     */
    private static final String REPO_TYPE = "npm";

    /**
     * JSON key for keywords.
     */
    private static final String KEYWORDS = "keywords";

    /**
     * JSON key for maintainers.
     */
    private static final String MAINTAINERS = "maintainers";

    /**
     * JSON key for description.
     */
    private static final String DESCRIPTION = "description";

    /**
     * JSON key for a maintainer's user name.
     */
    private static final String USERNAME = "username";

    /**
     * Artifact index shared with every other repository/format.
     */
    private final ArtifactIndex index;

    /**
     * This repository's name — scopes search results to packages published
     * into this repository, not every npm repository on the instance.
     */
    private final String repoName;

    /**
     * Repository storage for per-version manifests, if available.
     */
    private final Optional<Storage> storage;

    /**
     * Constructor without manifest enrichment.
     * @param index Shared artifact index
     * @param repoName Repository name to scope results to
     */
    public SearchSlice(final ArtifactIndex index, final String repoName) {
        this(index, repoName, Optional.empty());
    }

    /**
     * Constructor.
     * @param index Shared artifact index
     * @param repoName Repository name to scope results to
     * @param storage Repository storage used to read each package's
     *  description, keywords and maintainers
     */
    public SearchSlice(
        final ArtifactIndex index, final String repoName, final Optional<Storage> storage
    ) {
        this.index = Objects.requireNonNull(index, "index");
        this.repoName = Objects.requireNonNull(repoName, "repoName");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            final String query = line.uri().getQuery();
            if (query == null || query.isEmpty()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest()
                        .textBody("Search query required")
                        .build()
                );
            }

            final Matcher matcher = QUERY_PATTERN.matcher(query);
            if (!matcher.find()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.badRequest()
                        .textBody("Invalid search query")
                        .build()
                );
            }

            final String text = matcher.group(1);
            final int size = matcher.group(2) != null
                ? Integer.parseInt(matcher.group(2))
                : DEFAULT_SIZE;
            final int from = matcher.group(3) != null
                ? Integer.parseInt(matcher.group(3))
                : 0;
            return this.search(text, size, from);
        });
    }

    /**
     * Run the index search and page over packages.
     * @param text Query text
     * @param size Page size (packages)
     * @param from Page offset (packages)
     * @return Completion with the response
     */
    private CompletableFuture<Response> search(final String text, final int size, final int from) {
        return this.index.search(
            text, SearchSlice.MAX_SCANNED_VERSIONS, 0, SearchSlice.REPO_TYPE,
            this.repoName, "relevance", true
        ).thenCompose(result -> {
            final List<List<ArtifactDocument>> packages =
                SearchSlice.byPackage(result.documents());
            final List<List<ArtifactDocument>> page = packages.subList(
                Math.min(from, packages.size()),
                Math.min(from + size, packages.size())
            );
            return this.objects(page).thenApply(
                objects -> ResponseBuilder.ok()
                    .jsonBody(Json.createObjectBuilder()
                        .add("objects", objects)
                        .add("total", packages.size())
                        .add("time", System.currentTimeMillis())
                        .build())
                    .build()
            );
        });
    }

    /**
     * Group index rows by package name, keeping relevance order of each
     * package's first hit.
     * @param docs Index rows (one per version)
     * @return Rows grouped per package
     */
    private static List<List<ArtifactDocument>> byPackage(final List<ArtifactDocument> docs) {
        final Map<String, List<ArtifactDocument>> grouped = new LinkedHashMap<>();
        for (final ArtifactDocument doc : docs) {
            grouped.computeIfAbsent(SearchSlice.name(doc), key -> new ArrayList<>()).add(doc);
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * Build the search objects for one page of packages.
     * @param page Packages (each a list of its version rows)
     * @return Completion with the objects array
     */
    private CompletableFuture<JsonArray> objects(final List<List<ArtifactDocument>> page) {
        final List<CompletableFuture<JsonObject>> futures = new ArrayList<>(page.size());
        for (final List<ArtifactDocument> versions : page) {
            final ArtifactDocument doc = SearchSlice.representative(versions);
            futures.add(this.manifest(doc).thenApply(man -> SearchSlice.packageToJson(doc, man)));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
            .thenApply(
                nothing -> {
                    final JsonArrayBuilder arr = Json.createArrayBuilder();
                    futures.forEach(fut -> arr.add(fut.join()));
                    return arr.build();
                }
            );
    }

    /**
     * Read the stored manifest of a package version; empty on any miss.
     * @param doc Index row
     * @return Completion with the manifest (possibly empty)
     */
    private CompletableFuture<JsonObject> manifest(final ArtifactDocument doc) {
        if (this.storage.isEmpty() || doc.version() == null) {
            return CompletableFuture.completedFuture(JsonValue.EMPTY_JSON_OBJECT);
        }
        return new PerVersionLayout(this.storage.get())
            .readVersion(new Key.From(SearchSlice.name(doc)), doc.version())
            .toCompletableFuture()
            .exceptionally(err -> JsonValue.EMPTY_JSON_OBJECT);
    }

    /**
     * The row representing a package: its highest stable version, or its
     * highest version when every version is a prerelease.
     * @param versions Version rows of one package
     * @return Representative row
     */
    private static ArtifactDocument representative(final List<ArtifactDocument> versions) {
        final JsonObjectBuilder known = Json.createObjectBuilder();
        final Map<String, ArtifactDocument> byVersion = new LinkedHashMap<>();
        for (final ArtifactDocument doc : versions) {
            if (doc.version() != null && !doc.version().isEmpty()) {
                known.add(doc.version(), JsonValue.EMPTY_JSON_OBJECT);
                byVersion.put(doc.version(), doc);
            }
        }
        final JsonObject all = known.build();
        List<String> sorted = new DescSortedVersions(all, true).value();
        if (sorted.isEmpty()) {
            sorted = new DescSortedVersions(all, false).value();
        }
        ArtifactDocument chosen = versions.get(0);
        if (!sorted.isEmpty()) {
            chosen = byVersion.get(sorted.get(0));
        }
        return chosen;
    }

    /**
     * Package name of an index row.
     * @param doc Index row
     * @return Package name
     */
    private static String name(final ArtifactDocument doc) {
        return doc.artifactName() != null ? doc.artifactName() : doc.artifactPath();
    }

    /**
     * Convert an indexed artifact document to the npm search result schema.
     * @param doc Indexed artifact document
     * @param manifest Stored manifest of that version (may be empty)
     * @return JSON object
     */
    private static JsonObject packageToJson(final ArtifactDocument doc, final JsonObject manifest) {
        final String version = doc.version() != null ? doc.version() : "";
        final JsonObjectBuilder pkg = Json.createObjectBuilder()
            .add("name", SearchSlice.name(doc))
            .add("version", version)
            .add(SearchSlice.DESCRIPTION, SearchSlice.description(manifest))
            .add(SearchSlice.KEYWORDS, SearchSlice.arrayOrEmpty(manifest, SearchSlice.KEYWORDS))
            .add(SearchSlice.MAINTAINERS, SearchSlice.maintainers(doc, manifest))
            .add("links", Json.createObjectBuilder());
        if (doc.createdAt() != null) {
            pkg.add("date", doc.createdAt().toString());
        }
        if (doc.owner() != null && !doc.owner().isEmpty()) {
            pkg.add("publisher", Json.createObjectBuilder().add(SearchSlice.USERNAME, doc.owner()));
        }
        return Json.createObjectBuilder()
            .add("package", pkg)
            .add("score", Json.createObjectBuilder()
                .add("final", 1.0)
                .add("detail", Json.createObjectBuilder()
                    .add("quality", 1.0)
                    .add("popularity", 1.0)
                    .add("maintenance", 1.0)
                )
            )
            .add("searchScore", 1.0)
            .build();
    }

    /**
     * Description from the manifest, or empty.
     * @param manifest Manifest
     * @return Description
     */
    private static String description(final JsonObject manifest) {
        final JsonValue value = manifest.get(SearchSlice.DESCRIPTION);
        String desc = "";
        if (value != null && value.getValueType() == JsonValue.ValueType.STRING) {
            desc = manifest.getString(SearchSlice.DESCRIPTION);
        }
        return desc;
    }

    /**
     * An array field of the manifest, or an empty array.
     * @param manifest Manifest
     * @param key Field name
     * @return Array
     */
    private static JsonArray arrayOrEmpty(final JsonObject manifest, final String key) {
        final JsonValue value = manifest.get(key);
        JsonArray arr = JsonValue.EMPTY_JSON_ARRAY;
        if (value != null && value.getValueType() == JsonValue.ValueType.ARRAY) {
            arr = value.asJsonArray();
        }
        return arr;
    }

    /**
     * Maintainers as {@code [{"username": ...}]}: the manifest's maintainers
     * when stored, else the publishing owner, else an empty array.
     * @param doc Index row
     * @param manifest Manifest
     * @return Maintainers array
     */
    private static JsonArray maintainers(final ArtifactDocument doc, final JsonObject manifest) {
        final JsonArrayBuilder out = Json.createArrayBuilder();
        for (final JsonValue item : SearchSlice.arrayOrEmpty(manifest, SearchSlice.MAINTAINERS)) {
            if (item.getValueType() == JsonValue.ValueType.OBJECT
                && item.asJsonObject().containsKey("name")) {
                final JsonObject man = item.asJsonObject();
                final JsonObjectBuilder entry = Json.createObjectBuilder()
                    .add(SearchSlice.USERNAME, man.getString("name", ""));
                if (man.containsKey("email")) {
                    entry.add("email", man.getString("email", ""));
                }
                out.add(entry);
            }
        }
        JsonArray result = out.build();
        if (result.isEmpty() && doc.owner() != null && !doc.owner().isEmpty()) {
            result = Json.createArrayBuilder()
                .add(Json.createObjectBuilder().add(SearchSlice.USERNAME, doc.owner()))
                .build();
        }
        return result;
    }
}
