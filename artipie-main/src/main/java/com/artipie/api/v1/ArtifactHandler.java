/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import com.artipie.api.AuthzHandler;
import com.artipie.api.RepositoryName;
import com.artipie.api.perms.ApiRepositoryPermission;
import com.artipie.asto.Key;
import com.artipie.asto.ListResult;
import com.artipie.asto.Meta;
import com.artipie.security.policy.Policy;
import com.artipie.settings.RepoData;
import com.artipie.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.time.Instant;
import javax.json.Json;
import javax.json.JsonStructure;

/**
 * Artifact handler for /api/v1/repositories/:name/artifact* endpoints.
 * @since 1.21.0
 */
public final class ArtifactHandler {

    /**
     * Repository settings create/read/update/delete.
     */
    private final CrudRepoSettings crs;

    /**
     * Repository data management.
     */
    private final RepoData repoData;

    /**
     * Artipie security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param crs Repository settings CRUD
     * @param repoData Repository data management
     * @param policy Artipie security policy
     */
    public ArtifactHandler(final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy) {
        this.crs = crs;
        this.repoData = repoData;
        this.policy = policy;
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
        // GET /api/v1/repositories/:name/artifact/download — download artifact
        router.get("/api/v1/repositories/:name/artifact/download")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::downloadHandler);
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
            .thenCompose(asto -> asto.list(prefix, "/"))
            .thenAccept(listing -> {
                final JsonArray items = new JsonArray();
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
                    items.add(new JsonObject()
                        .put("name", name)
                        .put("path", itemPath)
                        .put("type", "directory"));
                }
                // Then files
                for (final Key file : listing.files()) {
                    final String fileStr = file.string();
                    final String name = fileStr.contains("/")
                        ? fileStr.substring(fileStr.lastIndexOf('/') + 1) : fileStr;
                    final String repoPrefix = repoName + "/";
                    String itemPath = fileStr.startsWith(repoPrefix)
                        ? fileStr.substring(repoPrefix.length()) : fileStr;
                    items.add(new JsonObject()
                        .put("name", name)
                        .put("path", itemPath)
                        .put("type", "file"));
                }
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("items", items)
                        .put("marker", (String) null)
                        .put("hasMore", false).encode());
            })
            .exceptionally(err -> {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    err.getCause() != null ? err.getCause().getMessage() : err.getMessage());
                return null;
            });
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
            .thenCompose(asto -> asto.metadata(artifactKey))
            .thenAccept(meta -> {
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
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            })
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
                        .putHeader("Content-Disposition",
                            "attachment; filename=\"" + filename + "\"")
                        .putHeader("Content-Type", "application/octet-stream");
                    if (size >= 0) {
                        ctx.response().putHeader("Content-Length", String.valueOf(size));
                    }
                    return asto.value(artifactKey);
                })
            )
            .thenCompose(content -> content.asBytesFuture())
            .thenAccept(bytes ->
                ctx.response().setStatusCode(200).end(
                    io.vertx.core.buffer.Buffer.buffer(bytes)
                )
            )
            .exceptionally(err -> {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                    "Artifact not found: " + path);
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
        ctx.vertx().<String>executeBlocking(
            () -> {
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
            },
            false
        ).onSuccess(
            repoType -> {
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
            }
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
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
        this.repoData.deleteArtifact(rname, path)
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
        this.repoData.deletePackageFolder(rname, path)
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
    @SuppressWarnings("PMD.CyclomaticComplexity")
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
                String.format("curl -O <artipie-url>/%s/%s", repoName, path)
            );
        } else if (repoType.startsWith("npm")) {
            final String pkg = npmPackageName(path);
            instructions.add(
                String.format(
                    "npm install %s --registry <artipie-url>/%s", pkg, repoName
                )
            );
        } else if (repoType.startsWith("docker")) {
            final String image = dockerImageName(path);
            if (image != null) {
                instructions.add(
                    String.format("docker pull <artipie-host>/%s", image)
                );
            } else {
                instructions.add(
                    String.format(
                        "docker pull <artipie-host>/%s/<image>:<tag>", repoName
                    )
                );
            }
        } else if (repoType.startsWith("pypi")) {
            final String pkg = pypiPackageName(path);
            instructions.add(
                String.format(
                    "pip install --index-url <artipie-url>/%s/simple %s",
                    repoName, pkg
                )
            );
        } else if (repoType.startsWith("helm")) {
            final String chart = helmChartName(path);
            instructions.add(
                String.format(
                    "helm repo add %s <artipie-url>/%s", repoName, repoName
                )
            );
            instructions.add(
                String.format("helm install my-release %s/%s", repoName, chart)
            );
        } else if (repoType.startsWith("go")) {
            instructions.add(
                String.format(
                    "GOPROXY=<artipie-url>/%s go get %s", repoName, path
                )
            );
        } else if (repoType.startsWith("nuget")) {
            final String pkg = nugetPackageName(path);
            instructions.add(
                String.format(
                    "dotnet add package %s --source <artipie-url>/%s/index.json",
                    pkg, repoName
                )
            );
        } else {
            instructions.add(
                String.format("curl -O <artipie-url>/%s/%s", repoName, path)
            );
            instructions.add(
                String.format("wget <artipie-url>/%s/%s", repoName, path)
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
}
