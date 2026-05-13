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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.json.Json;
import javax.json.JsonStructure;
import javax.sql.DataSource;

/**
 * Artifact handler for /api/v1/repositories/:name/artifact* endpoints.
 * @since 1.21.0
 */
public final class ArtifactHandler {

    /**
     * Download token TTL in milliseconds.
     */
    private static final long TOKEN_TTL_MS = 60_000L;

    /**
     * HMAC algorithm for stateless token signing.
     */
    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * HMAC secret key — derived from system identity at startup.
     * Stateless tokens allow any instance behind NLB to validate.
     */
    private static final byte[] HMAC_SECRET;

    static {
        final String seed = System.getenv().getOrDefault(
            "PANTERA_DOWNLOAD_TOKEN_SECRET", // NOPMD HardCodedCryptoKey - env var name, not key material
            "pantera-download-" + ProcessHandle.current().pid()
                + "-" + System.getProperty("user.name", "default")
        );
        HMAC_SECRET = seed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Repository settings create/read/update/delete.
     */
    private final CrudRepoSettings crs;

    /**
     * Repository data management.
     */
    private final RepoData repoData;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Artifacts DataSource — used by {@link #treeHandler} to hydrate file
     * entries with upload date, size, and {@code artifact_kind} via a single
     * batch lookup instead of N per-key metadata calls against storage.
     * Nullable: when null the handler falls back to a storage-only listing
     * (no modified date / kind in the response).
     */
    private final DataSource dataSource;

    /**
     * Artifact index — used by the delete handlers to cascade the storage
     * delete into the DB, so search / locate don't return ghosts.
     * Nullable for tests; {@link ArtifactIndex#NOP} is a safe default.
     */
    private final ArtifactIndex artifactIndex;

    /**
     * Caffeine-backed cache for storage-level file metadata (size, modified).
     * Prevents repeated S3 HEADs during burst browsing when the DB has no
     * matching row for the file path (Go, npm, PyPI, Docker, Helm, Debian).
     */
    private final StorageMetaCache metaCache;

    /**
     * Ctor.
     * @param crs Repository settings CRUD
     * @param repoData Repository data management
     * @param policy Pantera security policy
     * @param dataSource Artifacts DB DataSource (nullable; enables date /
     *                   kind enrichment for tree listings when present)
     * @param artifactIndex Index used by delete handlers to cascade DB
     *                      removal alongside the storage delete
     */
    public ArtifactHandler(final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy, final DataSource dataSource,
        final ArtifactIndex artifactIndex) {
        this(crs, repoData, policy, dataSource, artifactIndex, new StorageMetaCache());
    }

    /**
     * Primary ctor — all fields.
     * @param crs Repository settings CRUD
     * @param repoData Repository data management
     * @param policy Pantera security policy
     * @param dataSource Artifacts DB DataSource (nullable)
     * @param artifactIndex Index used by delete handlers to cascade DB removal
     * @param metaCache Caffeine-backed storage metadata cache
     */
    ArtifactHandler(final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy, final DataSource dataSource,
        final ArtifactIndex artifactIndex, final StorageMetaCache metaCache) {
        this.crs = crs;
        this.repoData = repoData;
        this.policy = policy;
        this.dataSource = dataSource;
        this.artifactIndex = artifactIndex == null ? ArtifactIndex.NOP : artifactIndex;
        this.metaCache = metaCache == null ? new StorageMetaCache() : metaCache;
    }

    /**
     * Back-compat ctor without the artifact index — delete handlers will
     * skip DB cascade, matching pre-2.2.0 behaviour.
     */
    public ArtifactHandler(final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy, final DataSource dataSource) {
        this(crs, repoData, policy, dataSource, ArtifactIndex.NOP);
    }

    /**
     * Legacy ctor without DataSource — preserves older wiring that
     * predates the 2.2.0 tree-handler DB hydration.
     * @param crs Repository settings CRUD
     * @param repoData Repository data management
     * @param policy Pantera security policy
     */
    public ArtifactHandler(final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy) {
        this(crs, repoData, policy, null, ArtifactIndex.NOP);
    }

    /**
     * Register artifact routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiRepositoryPermission read =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ);
        final ApiRepositoryPermission delete =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE);
        // GET /api/v1/repositories/:name/tree — directory listing (cursor-based)
        router.get("/api/v1/repositories/:name/tree")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::treeHandler);
        // GET /api/v1/repositories/:name/artifact — artifact detail
        router.get("/api/v1/repositories/:name/artifact")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::artifactDetailHandler);
        // GET /api/v1/repositories/:name/artifact/pull — pull instructions
        router.get("/api/v1/repositories/:name/artifact/pull")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::pullInstructionsHandler);
        // GET /api/v1/repositories/:name/artifact/download — download artifact (JWT auth)
        router.get("/api/v1/repositories/:name/artifact/download")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::downloadHandler);
        // POST /api/v1/repositories/:name/artifact/download-token — issue single-use token
        router.post("/api/v1/repositories/:name/artifact/download-token")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::downloadTokenHandler);
        // GET /api/v1/repositories/:name/artifact/download-direct — download via token (no JWT)
        router.get("/api/v1/repositories/:name/artifact/download-direct")
            .handler(this::downloadDirectHandler);
        // DELETE /api/v1/repositories/:name/artifacts — delete artifact
        router.delete("/api/v1/repositories/:name/artifacts")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteArtifactHandler);
        // DELETE /api/v1/repositories/:name/packages — delete package folder
        router.delete("/api/v1/repositories/:name/packages")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deletePackageFolderHandler);
    }

    /**
     * GET /api/v1/repositories/:name/tree — browse repository storage.
     * Uses asto Storage.list(prefix, "/") for shallow directory listing,
     * which works for all repo types (maven, npm, docker, file, etc.).
     * @param ctx Routing context
     */
    private void treeHandler(final RoutingContext ctx) {
        final String repoName = ctx.pathParam("name");
        final String path = ctx.queryParam("path").stream()
            .findFirst().orElse("/");
        final String sortBy = normalizeTreeSort(
            ctx.queryParam("sort").stream().findFirst().orElse("name")
        );
        final boolean sortAsc = !"desc".equalsIgnoreCase(
            ctx.queryParam("sort_dir").stream().findFirst().orElse("asc")
        );
        final RepositoryName rname = new RepositoryName.Simple(repoName);
        // Resolve the storage key: repo root or sub-path
        final Key prefix;
        if ("/".equals(path) || path.isEmpty()) {
            prefix = new Key.From(repoName);
        } else {
            final String clean = path.startsWith("/") ? path.substring(1) : path;
            prefix = new Key.From(repoName, clean);
        }
        this.repoData.repoStorage(rname, this.crs)
            .thenCompose(asto -> asto.list(prefix, "/").thenCompose(listing -> {
                final JsonArray dirItems = new JsonArray();
                final String prefixStr = prefix.string();
                final int prefixLen = prefixStr.isEmpty() ? 0 : prefixStr.length() + 1;
                // Directories first
                for (final Key dir : listing.directories()) {
                    String dirStr = dir.string();
                    // Strip trailing slash if present
                    if (dirStr.endsWith("/")) {
                        dirStr = dirStr.substring(0, dirStr.length() - 1);
                    }
                    final String relative = dirStr.length() > prefixLen
                        ? dirStr.substring(prefixLen) : dirStr;
                    final String name = relative.contains("/")
                        ? relative.substring(relative.lastIndexOf('/') + 1) : relative;
                    // Build the path relative to repo root (strip repo name prefix)
                    final String repoPrefix = repoName + "/";
                    String itemPath = dirStr.startsWith(repoPrefix)
                        ? dirStr.substring(repoPrefix.length()) : dirStr;
                    dirItems.add(new JsonObject()
                        .put("name", name)
                        .put("path", itemPath)
                        .put("type", "directory"));
                }
                // Then files — for PyPI artifacts, check sidecar for
                // yanked status so the tree can show a "Yanked" badge.
                final List<CompletableFuture<JsonObject>> fileFutures = new ArrayList<>();
                for (final Key file : listing.files()) {
                    final String fileStr = file.string();
                    final String name = fileStr.contains("/")
                        ? fileStr.substring(fileStr.lastIndexOf('/') + 1) : fileStr;
                    final String repoPrefix = repoName + "/";
                    final String itemPath = fileStr.startsWith(repoPrefix)
                        ? fileStr.substring(repoPrefix.length()) : fileStr;
                    final JsonObject entry = new JsonObject()
                        .put("name", name)
                        .put("path", itemPath)
                        .put("type", "file");
                    // Check sidecar for pypi artifacts (cheap: one
                    // exists() + one small read per file; typical pypi
                    // version dirs have 2-5 files).
                    final boolean isPypiFile = name.endsWith(".whl")
                        || name.endsWith(".tar.gz")
                        || name.endsWith(".tar.bz2")
                        || name.endsWith(".zip");
                    if (isPypiFile) {
                        // repoData.repoStorage returns top-level storage
                        // where all keys are prefixed with the repo name.
                        // PypiSidecar.sidecarKey returns a repo-relative
                        // key, so prepend the repo name.
                        final Key sidecar = new Key.From(
                            repoName,
                            com.auto1.pantera.pypi.meta.PypiSidecar.sidecarKey(
                                new Key.From(itemPath)
                            ).string()
                        );
                        fileFutures.add(
                            asto.exists(sidecar).thenCompose(exists -> {
                                if (!exists) {
                                    return CompletableFuture.completedFuture(entry);
                                }
                                return asto.value(sidecar)
                                    .thenCompose(
                                        com.auto1.pantera.asto.Content::asBytesFuture
                                    )
                                    .thenApply(bytes -> {
                                        try (javax.json.JsonReader reader =
                                            Json.createReader(
                                                new StringReader(
                                                    new String(bytes,
                                                        StandardCharsets.UTF_8)
                                                )
                                            )) {
                                            final javax.json.JsonObject sc =
                                                reader.readObject();
                                            entry.put("yanked",
                                                sc.getBoolean("yanked", false));
                                        } catch (final Exception ignored) {
                                            // skip
                                        }
                                        return entry;
                                    });
                            })
                        );
                    } else {
                        fileFutures.add(CompletableFuture.completedFuture(entry));
                    }
                }
                return CompletableFuture.allOf(
                    fileFutures.toArray(new CompletableFuture[0])
                ).thenCompose(ignored -> {
                    final JsonArray fileItems = new JsonArray();
                    for (final var f : fileFutures) {
                        fileItems.add(f.join());
                    }
                    // Fix E (2.2.0): hydrate file entries with DB metadata
                    // (size, modified, artifact_kind). One IN query batches
                    // the whole listing; for DB misses, falls back to
                    // storage metadata + Caffeine cache so burst browsing
                    // doesn't hammer S3 with repeated HEADs.
                    return hydrateFilesWithDbMetadata(
                        this.dataSource, repoName, fileItems,
                        asto, this.metaCache
                    ).thenAccept(hydrated -> {
                        final JsonArray items = sortTreeEntries(
                            dirItems, hydrated, sortBy, sortAsc
                        );
                        ctx.response().setStatusCode(200)
                            .putHeader("Content-Type", "application/json")
                            .end(new JsonObject()
                                .put("items", items)
                                .put("sort", sortBy)
                                .put("sort_dir", sortAsc ? "asc" : "desc")
                                .put("marker", (String) null)
                                .put("hasMore", false).encode());
                    });
                });
            }))
            .exceptionally(err -> {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
                return null;
            });
    }

    /**
     * Normalise the tree sort parameter to a known value. Unknown values
     * fall back to {@code name} so a typo never breaks the listing.
     *
     * @param raw Query parameter value
     * @return "name", "date", or "size"
     */
    private static String normalizeTreeSort(final String raw) {
        if (raw == null) {
            return "name";
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "date", "modified", "created_at" -> "date";
            case "size" -> "size";
            default -> "name";
        };
    }

    /**
     * Batch-hydrate file entries with DB-sourced metadata (size, modified
     * timestamp, artifact_kind). Issues ONE query per listing regardless
     * of the number of files, then merges by {@code path}.
     *
     * <p>For files where the DB has no matching row (Go, npm, PyPI, Docker,
     * Helm, Debian — whose DB {@code name} is a module/package name, not
     * a file path), the method falls back to
     * {@link Storage#metadata(Key)} for size and modified timestamp, and
     * derives {@code artifact_kind} from the filename. Fallback results are
     * stored in {@code metaCache} so repeated calls for the same key during
     * burst browsing do not pay S3 HEAD costs.</p>
     *
     * @param ds DataSource — may be null (handler wired without DB)
     * @param repoName Repository name
     * @param files File-kind JsonObjects from the storage listing
     * @param asto Storage instance for the repository (fallback reads)
     * @param metaCache Caffeine cache for storage metadata (avoids repeat HEADs)
     * @return Future carrying the same JsonArray with metadata merged in
     */
    private static CompletableFuture<JsonArray> hydrateFilesWithDbMetadata(
        final DataSource ds, final String repoName, final JsonArray files,
        final Storage asto, final StorageMetaCache metaCache
    ) {
        if (files.isEmpty()) {
            return CompletableFuture.completedFuture(files);
        }
        // Step 1: DB batch query (fast path — one round-trip for all files).
        final Map<String, JsonObject> byPath = new HashMap<>();
        if (ds != null) {
            final List<String> paths = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                paths.add(files.getJsonObject(i).getString("path"));
            }
            try (Connection conn = ds.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT name, size, created_date, artifact_kind"
                         + " FROM artifacts"
                         + " WHERE repo_name = ? AND name = ANY(?)")) {
                stmt.setString(1, repoName);
                final Array arr = conn.createArrayOf(
                    "text", paths.toArray(new String[0])
                );
                stmt.setArray(2, arr);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        byPath.put(rs.getString("name"),
                            new JsonObject()
                                .put("size", rs.getLong("size"))
                                .put("modified",
                                    Instant.ofEpochMilli(rs.getLong("created_date"))
                                        .toString())
                                .put("artifact_kind",
                                    rs.getString("artifact_kind")));
                    }
                }
            } catch (final SQLException ex) {
                EcsLogger.warn("com.auto1.pantera.api.v1")
                    .message("Tree DB hydration failed; falling back"
                        + " to storage metadata: " + ex.getMessage())
                    .eventCategory("database")
                    .eventAction("tree_hydrate_fallback")
                    .error(ex)
                    .log();
                // byPath stays empty — all files will go through the asto fallback
            }
        }
        // Step 2: Apply DB hits; collect misses for the asto fallback.
        final List<Integer> missingIdx = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            final JsonObject file = files.getJsonObject(i);
            final JsonObject meta = byPath.get(file.getString("path"));
            if (meta != null) {
                file.put("size", meta.getLong("size"));
                file.put("modified", meta.getString("modified"));
                file.put("artifact_kind", meta.getString("artifact_kind"));
            } else {
                missingIdx.add(i);
            }
        }
        if (missingIdx.isEmpty() || asto == null) {
            return CompletableFuture.completedFuture(files);
        }
        // Step 3: Fan out storage metadata reads for DB-miss files.
        // Check the Caffeine cache first to avoid S3 HEADs on repeated browse.
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (final int idx : missingIdx) {
            final JsonObject file = files.getJsonObject(idx);
            final String filePath = file.getString("path");
            final String fileName = file.getString("name");
            // Derive kind from filename — used regardless of whether asto succeeds.
            file.put("artifact_kind", deriveArtifactKind(fileName));
            final java.util.Optional<StorageMetaCache.Entry> cached =
                metaCache.get(repoName, filePath);
            if (cached.isPresent()) {
                final StorageMetaCache.Entry entry = cached.get();
                if (entry.size() != null) {
                    file.put("size", entry.size());
                }
                if (entry.modifiedIso() != null) {
                    file.put("modified", entry.modifiedIso());
                }
                futures.add(CompletableFuture.completedFuture(null));
            } else {
                // Cache miss — read from storage and populate cache.
                final Key storageKey = new Key.From(repoName, filePath);
                futures.add(
                    asto.metadata(storageKey)
                        .thenAccept(meta -> {
                            final Long size = meta.read(Meta.OP_SIZE)
                                .map(Long::longValue).orElse(null);
                            String modifiedIso = null;
                            final java.util.Optional<? extends Instant> updated =
                                meta.read(Meta.OP_UPDATED_AT);
                            if (updated.isPresent()) {
                                modifiedIso = updated.get().toString();
                            } else {
                                final java.util.Optional<? extends Instant> created =
                                    meta.read(Meta.OP_CREATED_AT);
                                if (created.isPresent()) {
                                    modifiedIso = created.get().toString();
                                }
                            }
                            metaCache.put(repoName, filePath, size, modifiedIso);
                            if (size != null) {
                                file.put("size", size);
                            }
                            if (modifiedIso != null) {
                                file.put("modified", modifiedIso);
                            }
                        })
                        .exceptionally(err -> {
                            // Storage metadata unavailable — leave size/modified unset.
                            // artifact_kind was already derived from the filename above.
                            return null;
                        })
                );
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> files);
    }

    /**
     * Heuristic classification by filename, used when the artifacts DB has
     * no row for this path. Returns one of CHECKSUM, SIGNATURE, METADATA,
     * ARTIFACT. Only the leaf name is inspected.
     *
     * @param name File name
     * @return Kind constant
     */
    private static String deriveArtifactKind(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sha256") || lower.endsWith(".sha512")
            || lower.endsWith(".sha1") || lower.endsWith(".md5")) {
            return "CHECKSUM";
        }
        if (lower.endsWith(".asc") || lower.endsWith(".sig")
            || lower.endsWith(".sigstore")) {
            return "SIGNATURE";
        }
        if (lower.endsWith(".pom") || lower.endsWith(".xml")
            || lower.endsWith(".info") || lower.endsWith(".mod")
            || lower.endsWith(".json")
            || "list".equals(lower) || "index.yaml".equals(lower)
            || "packages.json".equals(lower)
            || "maven-metadata.xml".equals(lower)) {
            return "METADATA";
        }
        return "ARTIFACT";
    }

    /**
     * Combine directory + file entries into a single sorted list.
     * Directories always sort first within each direction: users
     * expect to see folders grouped together regardless of the
     * active sort key. Within each group, apply the requested sort.
     *
     * @param dirs Directory entries (no date — sorted by name only)
     * @param files File entries (may carry `modified` and `size`)
     * @param sortBy "name", "date", or "size"
     * @param sortAsc true for ascending
     * @return New JsonArray containing dirs then files in sorted order
     */
    private static JsonArray sortTreeEntries(
        final JsonArray dirs, final JsonArray files,
        final String sortBy, final boolean sortAsc
    ) {
        final Comparator<JsonObject> byName = Comparator.comparing(
            o -> o.getString("name", ""),
            (a, b) -> a.compareToIgnoreCase(b) != 0
                ? a.compareToIgnoreCase(b) : a.compareTo(b)
        );
        final Comparator<JsonObject> cmp;
        if ("date".equals(sortBy)) {
            // Files without `modified` sort as epoch 0; name breaks ties.
            cmp = Comparator.<JsonObject, String>comparing(
                o -> o.getString("modified", "1970-01-01T00:00:00Z")
            ).thenComparing(byName);
        } else if ("size".equals(sortBy)) {
            // Files without a hydrated `size` sort as 0; name breaks ties.
            cmp = Comparator.<JsonObject>comparingLong(
                o -> o.getLong("size", 0L)
            ).thenComparing(byName);
        } else {
            cmp = byName;
        }
        final Comparator<JsonObject> effective = sortAsc ? cmp : cmp.reversed();
        final List<JsonObject> dirList = new ArrayList<>();
        for (int i = 0; i < dirs.size(); i++) {
            dirList.add(dirs.getJsonObject(i));
        }
        dirList.sort(
            sortAsc ? byName : byName.reversed()
        );
        final List<JsonObject> fileList = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            fileList.add(files.getJsonObject(i));
        }
        fileList.sort(effective);
        final JsonArray out = new JsonArray();
        dirList.forEach(out::add);
        fileList.forEach(out::add);
        return out;
    }

    /**
     * GET /api/v1/repositories/:name/artifact — artifact detail from storage.
     * @param ctx Routing context
     */
    private void artifactDetailHandler(final RoutingContext ctx) {
        final String path = ctx.queryParam("path").stream().findFirst().orElse(null);
        if (path == null || path.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Query parameter 'path' is required");
            return;
        }
        final String repoName = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(repoName);
        final String filename = path.contains("/")
            ? path.substring(path.lastIndexOf('/') + 1)
            : path;
        final Key artifactKey = new Key.From(repoName, path);
        this.repoData.repoStorage(rname, this.crs)
            .thenCompose(asto -> asto.metadata(artifactKey).thenCompose(meta -> {
                final long size = meta.read(Meta.OP_SIZE)
                    .map(Long::longValue).orElse(0L);
                final JsonObject result = new JsonObject()
                    .put("path", path)
                    .put("name", filename)
                    .put("size", size);
                meta.read(Meta.OP_UPDATED_AT).ifPresent(
                    ts -> result.put("modified", ts.toString())
                );
                meta.read(Meta.OP_CREATED_AT).ifPresent(
                    ts -> {
                        if (!result.containsKey("modified")) {
                            result.put("modified", ts.toString());
                        }
                    }
                );
                meta.read(Meta.OP_MD5).ifPresent(
                    md5 -> result.put("checksums",
                        new JsonObject().put("md5", md5))
                );
                // For PyPI artifacts, include the yanked status from
                // the sidecar metadata so the UI can show the correct
                // Yank / Unyank button and the "Yanked" badge.
                final boolean isPypi = filename.endsWith(".whl")
                    || filename.endsWith(".tar.gz")
                    || filename.endsWith(".tar.bz2")
                    || filename.endsWith(".zip")
                    || filename.endsWith(".egg");
                if (isPypi) {
                    final Key sidecarKey = new Key.From(
                        repoName,
                        com.auto1.pantera.pypi.meta.PypiSidecar.sidecarKey(
                            new Key.From(path)
                        ).string()
                    );
                    return asto.exists(sidecarKey).thenCompose(exists -> {
                        if (!exists) {
                            return java.util.concurrent.CompletableFuture
                                .completedFuture(result);
                        }
                        return asto.value(sidecarKey)
                            .thenCompose(
                                com.auto1.pantera.asto.Content::asBytesFuture
                            )
                            .thenApply(bytes -> {
                                try (javax.json.JsonReader reader =
                                    javax.json.Json.createReader(
                                        new java.io.StringReader(
                                            new String(bytes,
                                                java.nio.charset.StandardCharsets.UTF_8)
                                        )
                                    )) {
                                    final javax.json.JsonObject sc =
                                        reader.readObject();
                                    result.put("yanked",
                                        sc.getBoolean("yanked", false));
                                    if (!sc.isNull("yanked-reason")) {
                                        result.put("yanked_reason",
                                            sc.getString("yanked-reason", ""));
                                    }
                                } catch (final Exception ignored) {
                                    // Sidecar parse error — omit yanked field
                                }
                                return result;
                            });
                    });
                }
                return java.util.concurrent.CompletableFuture
                    .completedFuture(result);
            }))
            .thenAccept(result ->
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode())
            )
            .exceptionally(err -> {
                // If metadata fails (e.g. file not found), return basic info
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(
                        new JsonObject()
                            .put("path", path)
                            .put("name", filename)
                            .put("size", 0)
                            .encode()
                    );
                return null;
            });
    }

    /**
     * GET /api/v1/repositories/:name/artifact/download — stream artifact content.
     * Streams directly from storage to the HTTP response without buffering
     * the entire file in memory, so the browser receives bytes immediately
     * and can show its native download progress bar.
     * @param ctx Routing context
     */
    private void downloadHandler(final RoutingContext ctx) {
        final String path = ctx.queryParam("path").stream().findFirst().orElse(null);
        if (path == null || path.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Query parameter 'path' is required");
            return;
        }
        final String repoName = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(repoName);
        final String filename = path.contains("/")
            ? path.substring(path.lastIndexOf('/') + 1)
            : path;
        final Key artifactKey = new Key.From(repoName, path);
        this.repoData.repoStorage(rname, this.crs)
            .thenCompose(asto ->
                asto.metadata(artifactKey).thenCompose(meta -> {
                    final long size = meta.read(Meta.OP_SIZE)
                        .map(Long::longValue).orElse(-1L);
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Disposition",
                            "attachment; filename=\"" + filename + "\"")
                        .putHeader("Content-Type", "application/octet-stream");
                    if (size >= 0) {
                        ctx.response().putHeader("Content-Length", String.valueOf(size));
                    } else {
                        ctx.response().setChunked(true);
                    }
                    return asto.value(artifactKey);
                })
            )
            .thenAccept(content -> {
                // Capture the Disposable so a client disconnect (closeHandler) or
                // response error (exceptionHandler) can cancel the upstream stream
                // and free any file channels / temp files held by downstream tees.
                final io.reactivex.disposables.Disposable disposable =
                    io.reactivex.Flowable.fromPublisher(content)
                        .map(buf -> io.vertx.core.buffer.Buffer.buffer(
                            io.netty.buffer.Unpooled.wrappedBuffer(buf)
                        ))
                        .subscribe(
                            chunk -> ctx.response().write(chunk),
                            err -> {
                                if (!ctx.response().ended()) {
                                    ctx.response().end();
                                }
                            },
                            () -> ctx.response().end()
                        );
                ctx.response().closeHandler(v -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                });
                ctx.response().exceptionHandler(err -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                });
            })
            .exceptionally(err -> {
                if (!ctx.response().headWritten()) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                        "Artifact not found: " + path);
                }
                return null;
            });
    }

    /**
     * POST /api/v1/repositories/:name/artifact/download-token — issue a single-use,
     * short-lived download token. The UI calls this first, then navigates the browser
     * directly to the download-direct URL with the token, enabling native browser
     * download progress with zero JS memory usage.
     * @param ctx Routing context
     */
    private void downloadTokenHandler(final RoutingContext ctx) {
        final String path = ctx.queryParam("path").stream().findFirst().orElse(null);
        if (path == null || path.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Query parameter 'path' is required");
            return;
        }
        final String repoName = ctx.pathParam("name");
        // Build stateless HMAC-signed token: payload.signature
        // Any instance behind NLB can validate without shared state
        final long now = System.currentTimeMillis();
        final String payload = repoName + "\n" + path + "\n" + now;
        final String payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        final String signature = hmacSign(payload);
        final String token = payloadB64 + "." + signature;
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("token", token).encode());
    }

    /**
     * GET /api/v1/repositories/:name/artifact/download-direct — download via
     * single-use token. No JWT required. The browser navigates here directly,
     * so the native download manager handles progress and disk streaming.
     * @param ctx Routing context
     */
    private void downloadDirectHandler(final RoutingContext ctx) {
        final String token = ctx.queryParam("token").stream().findFirst().orElse(null);
        if (token == null || token.isBlank()) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Download token is required");
            return;
        }
        // Validate stateless HMAC token: payloadB64.signature
        final int dot = token.indexOf('.');
        if (dot < 0) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Malformed download token");
            return;
        }
        final String payloadB64 = token.substring(0, dot);
        final String signature = token.substring(dot + 1);
        final String payload;
        try {
            payload = new String(
                Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8
            );
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid download token encoding");
            return;
        }
        if (!hmacSign(payload).equals(signature)) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid download token signature");
            return;
        }
        final String[] parts = payload.split("\n");
        if (parts.length != 3) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid download token payload");
            return;
        }
        final String tokenRepo = parts[0];
        final long tokenTime = Long.parseLong(parts[2]);
        if (System.currentTimeMillis() - tokenTime > TOKEN_TTL_MS) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Download token has expired");
            return;
        }
        final String repoName = ctx.pathParam("name");
        if (!repoName.equals(tokenRepo)) {
            ApiResponse.sendError(ctx, 403, "FORBIDDEN", "Token does not match repository");
            return;
        }
        final String path = parts[1];
        final String filename = path.contains("/")
            ? path.substring(path.lastIndexOf('/') + 1)
            : path;
        final Key artifactKey = new Key.From(repoName, path);
        final RepositoryName rname = new RepositoryName.Simple(repoName);
        this.repoData.repoStorage(rname, this.crs)
            .thenCompose(asto ->
                asto.metadata(artifactKey).thenCompose(meta -> {
                    final long size = meta.read(Meta.OP_SIZE)
                        .map(Long::longValue).orElse(-1L);
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Disposition",
                            "attachment; filename=\"" + filename + "\"")
                        .putHeader("Content-Type", "application/octet-stream");
                    if (size >= 0) {
                        ctx.response().putHeader("Content-Length", String.valueOf(size));
                    } else {
                        ctx.response().setChunked(true);
                    }
                    return asto.value(artifactKey);
                })
            )
            .thenAccept(content -> {
                // Capture the Disposable so a client disconnect (closeHandler) or
                // response error (exceptionHandler) can cancel the upstream stream
                // and free any file channels / temp files held by downstream tees.
                final io.reactivex.disposables.Disposable disposable =
                    io.reactivex.Flowable.fromPublisher(content)
                        .map(buf -> io.vertx.core.buffer.Buffer.buffer(
                            io.netty.buffer.Unpooled.wrappedBuffer(buf)
                        ))
                        .subscribe(
                            chunk -> ctx.response().write(chunk),
                            err -> {
                                if (!ctx.response().ended()) {
                                    ctx.response().end();
                                }
                            },
                            () -> ctx.response().end()
                        );
                ctx.response().closeHandler(v -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                });
                ctx.response().exceptionHandler(err -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                });
            })
            .exceptionally(err -> {
                if (!ctx.response().headWritten()) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                        "Artifact not found: " + path);
                }
                return null;
            });
    }

    /**
     * GET /api/v1/repositories/:name/artifact/pull — pull instructions by repo type.
     * @param ctx Routing context
     */
    private void pullInstructionsHandler(final RoutingContext ctx) {
        final String path = ctx.queryParam("path").stream().findFirst().orElse(null);
        if (path == null || path.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Query parameter 'path' is required");
            return;
        }
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        CompletableFuture.supplyAsync(() -> {
            if (!this.crs.exists(rname)) {
                return null;
            }
            final JsonStructure config = this.crs.value(rname);
            if (config == null) {
                return null;
            }
            if (config instanceof javax.json.JsonObject) {
                final javax.json.JsonObject jobj = (javax.json.JsonObject) config;
                final javax.json.JsonObject repo = jobj.containsKey("repo")
                    ? jobj.getJsonObject("repo") : jobj;
                return repo.getString("type", "unknown");
            }
            return "unknown";
        }, HandlerExecutor.get()).whenComplete((repoType, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            if (repoType == null) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
                return;
            }
            final JsonArray instructions = buildPullInstructions(repoType, name, path);
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(
                    new JsonObject()
                        .put("type", repoType)
                        .put("instructions", instructions)
                        .encode()
                );
        });
    }

    /**
     * DELETE /api/v1/repositories/:name/artifacts — delete artifact.
     * @param ctx Routing context
     */
    private void deleteArtifactHandler(final RoutingContext ctx) {
        final String bodyStr = ctx.body().asString();
        if (bodyStr == null || bodyStr.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final javax.json.JsonObject body;
        try {
            body = Json.createReader(new StringReader(bodyStr)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final String path = body.getString("path", "").trim();
        if (path.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Field 'path' is required");
            return;
        }
        final RepositoryName rname = new RepositoryName.Simple(ctx.pathParam("name"));
        final String repoName = rname.toString();
        // Fix (2.2.0): use DB-fallback storage lookup so DB-only repos
        // created via the management UI don't 500 with
        // `No value for key: {repo}.yml`. On success, cascade the delete
        // into the artifacts DB index so search/locate don't return
        // ghosts for files that have been removed from storage. The
        // cascade is best-effort: if it fails we still return 204 and
        // log — the ghost will resolve next backfill pass.
        this.repoData.deleteArtifact(rname, path, this.crs)
            .thenCompose(deleted -> {
                if (!deleted) {
                    return CompletableFuture.completedFuture(deleted);
                }
                // Evict from metadata cache so the next tree view doesn't
                // show stale size/modified for a file that no longer exists.
                this.metaCache.invalidate(repoName, path);
                // Cover both cases: single file at the exact path, and
                // directory delete (which also removes any children).
                return this.artifactIndex.remove(repoName, path)
                    .thenCompose(
                        nothing -> this.artifactIndex.removePrefix(
                            repoName, path.endsWith("/") ? path : path + "/"
                        )
                    )
                    .<Boolean>handle((count, err) -> {
                        if (err != null) {
                            EcsLogger.warn("com.auto1.pantera.api.v1")
                                .message("Artifact deleted from storage but"
                                    + " DB-index cascade failed; ghost row"
                                    + " will persist until next backfill: "
                                    + err.getMessage())
                                .eventCategory("database")
                                .eventAction("delete_index_cascade_failed")
                                .field("repository.name", repoName)
                                .field("file.path", path)
                                .error(err)
                                .log();
                        }
                        return deleted;
                    });
            })
            .thenAccept(
                deleted -> ctx.response().setStatusCode(204).end()
            )
            .exceptionally(
                err -> {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                    return null;
                }
            );
    }

    /**
     * DELETE /api/v1/repositories/:name/packages — delete package folder.
     * @param ctx Routing context
     */
    private void deletePackageFolderHandler(final RoutingContext ctx) {
        final String bodyStr = ctx.body().asString();
        if (bodyStr == null || bodyStr.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final javax.json.JsonObject body;
        try {
            body = Json.createReader(new StringReader(bodyStr)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final String path = body.getString("path", "").trim();
        if (path.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Field 'path' is required");
            return;
        }
        final RepositoryName rname = new RepositoryName.Simple(ctx.pathParam("name"));
        final String repoName = rname.toString();
        // Fix (2.2.0): DB-fallback storage lookup + DB-index cascade. See
        // deleteArtifactHandler for the rationale.
        this.repoData.deletePackageFolder(rname, path, this.crs)
            .thenCompose(deleted -> {
                if (!deleted) {
                    return CompletableFuture.completedFuture(deleted);
                }
                // Evict all cache entries under this folder prefix so the
                // next tree view doesn't serve stale metadata for deleted files.
                this.metaCache.invalidatePrefix(repoName, path);
                return this.artifactIndex.removePrefix(
                        repoName, path.endsWith("/") ? path : path + "/"
                    )
                    .<Boolean>handle((count, err) -> {
                        if (err != null) {
                            EcsLogger.warn("com.auto1.pantera.api.v1")
                                .message("Package folder deleted from storage"
                                    + " but DB-index cascade failed: "
                                    + err.getMessage())
                                .eventCategory("database")
                                .eventAction("delete_index_cascade_failed")
                                .field("repository.name", repoName)
                                .field("file.path", path)
                                .error(err)
                                .log();
                        }
                        return deleted;
                    });
            })
            .thenAccept(
                deleted -> ctx.response().setStatusCode(204).end()
            )
            .exceptionally(
                err -> {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                    return null;
                }
            );
    }

    /**
     * Build pull instructions array based on repository type.
     * Generates technically accurate commands per technology.
     * @param repoType Repository type string
     * @param repoName Repository name
     * @param path Artifact path within the repository
     * @return JsonArray of instruction strings
     */
    private static JsonArray buildPullInstructions(final String repoType,
        final String repoName, final String path) {
        final JsonArray instructions = new JsonArray();
        if (repoType.startsWith("maven")) {
            final String gav = mavenGav(path);
            if (gav != null) {
                instructions.add(
                    String.format("mvn dependency:get -Dartifact=%s", gav)
                );
            }
            instructions.add(
                String.format("curl -O <pantera-url>/%s/%s", repoName, path)
            );
        } else if (repoType.startsWith("npm")) {
            final String pkg = npmPackageName(path);
            instructions.add(
                String.format(
                    "npm install %s --registry <pantera-url>/%s", pkg, repoName
                )
            );
        } else if (repoType.startsWith("docker")) {
            final String image = dockerImageName(path);
            if (image != null) {
                instructions.add(
                    String.format("docker pull <pantera-host>/%s", image)
                );
            } else {
                instructions.add(
                    String.format(
                        "docker pull <pantera-host>/%s/<image>:<tag>", repoName
                    )
                );
            }
        } else if (repoType.startsWith("pypi")) {
            final String pkg = pypiPackageName(path);
            instructions.add(
                String.format(
                    "pip install --index-url <pantera-url>/%s/simple %s",
                    repoName, pkg
                )
            );
        } else if (repoType.startsWith("helm")) {
            final String chart = helmChartName(path);
            instructions.add(
                String.format(
                    "helm repo add %s <pantera-url>/%s", repoName, repoName
                )
            );
            instructions.add(
                String.format("helm install my-release %s/%s", repoName, chart)
            );
        } else if (repoType.startsWith("go")) {
            instructions.add(
                String.format(
                    "GOPROXY=<pantera-url>/%s go get %s", repoName, path
                )
            );
        } else if (repoType.startsWith("nuget")) {
            final String pkg = nugetPackageName(path);
            instructions.add(
                String.format(
                    "dotnet add package %s --source <pantera-url>/%s/index.json",
                    pkg, repoName
                )
            );
        } else {
            instructions.add(
                String.format("curl -O <pantera-url>/%s/%s", repoName, path)
            );
            instructions.add(
                String.format("wget <pantera-url>/%s/%s", repoName, path)
            );
        }
        return instructions;
    }

    /**
     * Extract Maven GAV from artifact path.
     * Path: com/example/lib/1.0/lib-1.0.jar → com.example:lib:1.0
     * @param path Artifact path
     * @return GAV string or null if path cannot be parsed
     */
    private static String mavenGav(final String path) {
        final String[] parts = path.split("/");
        if (parts.length < 4) {
            return null;
        }
        final String version = parts[parts.length - 2];
        final String artifactId = parts[parts.length - 3];
        final StringBuilder groupId = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++) {
            if (i > 0) {
                groupId.append('.');
            }
            groupId.append(parts[i]);
        }
        return String.format("%s:%s:%s", groupId, artifactId, version);
    }

    /**
     * Extract npm package name from artifact path.
     * Path: @scope/pkg/-/@scope/pkg-1.0.0.tgz → @scope/pkg
     * Path: pkg/-/pkg-1.0.0.tgz → pkg
     * @param path Artifact path
     * @return Package name
     */
    private static String npmPackageName(final String path) {
        final String[] parts = path.split("/");
        if (parts.length >= 2 && parts[0].startsWith("@")) {
            return parts[0] + "/" + parts[1];
        }
        return parts[0];
    }

    /**
     * Extract Docker image name from storage path.
     * Storage path: docker/registry/v2/repositories/image/... → image
     * @param path Artifact path
     * @return Image name or null if it's a blob/internal path
     */
    private static String dockerImageName(final String path) {
        final String[] parts = path.split("/");
        final int repoIdx = indexOf(parts, "repositories");
        if (repoIdx >= 0 && repoIdx + 1 < parts.length) {
            final StringBuilder image = new StringBuilder();
            for (int i = repoIdx + 1; i < parts.length; i++) {
                if ("_manifests".equals(parts[i]) || "_layers".equals(parts[i])
                    || "_uploads".equals(parts[i])) {
                    break;
                }
                if (image.length() > 0) {
                    image.append('/');
                }
                image.append(parts[i]);
            }
            if (image.length() > 0) {
                return image.toString();
            }
        }
        return null;
    }

    /**
     * Extract PyPI package name from path.
     * Path: packages/example-pkg/1.0/example_pkg-1.0.tar.gz → example-pkg
     * @param path Artifact path
     * @return Package name
     */
    private static String pypiPackageName(final String path) {
        final String[] parts = path.split("/");
        if (parts.length >= 2 && "packages".equals(parts[0])) {
            return parts[1];
        }
        final String filename = parts[parts.length - 1];
        final int dash = filename.indexOf('-');
        if (dash > 0) {
            return filename.substring(0, dash);
        }
        return filename;
    }

    /**
     * Extract Helm chart name from path.
     * @param path Artifact path
     * @return Chart name
     */
    private static String helmChartName(final String path) {
        final String[] parts = path.split("/");
        final String filename = parts[parts.length - 1];
        final int dash = filename.indexOf('-');
        if (dash > 0) {
            return filename.substring(0, dash);
        }
        return filename;
    }

    /**
     * Extract NuGet package name from path.
     * @param path Artifact path
     * @return Package name
     */
    private static String nugetPackageName(final String path) {
        final String[] parts = path.split("/");
        return parts[0];
    }

    /**
     * Find index of element in array.
     * @param arr Array
     * @param target Target element
     * @return Index or -1
     */
    private static int indexOf(final String[] arr, final String target) {
        for (int i = 0; i < arr.length; i++) {
            if (target.equals(arr[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compute HMAC-SHA256 signature for the given payload.
     * @param payload Data to sign
     * @return URL-safe Base64 encoded signature
     */
    private static String hmacSign(final String payload) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(HMAC_SECRET, HMAC_ALGO));
            final byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (final Exception ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }
}
