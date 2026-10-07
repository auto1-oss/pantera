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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.RepoPathRemoval;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code DELETE /<repo>/<path>} of a proxy repository: evicts the cached
 * copy -- the file (with its checksum / metadata sidecars) or a directory
 * subtree from the proxy's cache storage, the search index rows of the
 * path, the tree view's cached metadata and the repository's in-memory /
 * Valkey caches of the path ({@link ProxyPathCaches}). The upstream is never
 * contacted: the next read fetches the artifact again.
 *
 * <p>Runs inside {@code TrimPathSlice} (repository-relative path), behind
 * the repository's {@code delete} permission. {@code 204} when anything was
 * evicted, {@code 404} when nothing was cached (or the proxy caches
 * nothing), {@code 400} for an unsafe path or the repository root. Every
 * outcome is audited as {@code artifact_delete}; a refused {@code 400} as a
 * failure with reason {@code forbidden}.</p>
 *
 * <p>The storage continuations run on pooled threads, so the request's
 * {@code X-Pantera-Ctx-*} headers are bound to the MDC before every
 * application log of the eviction (here and in {@link ArtifactDeletion}).</p>
 *
 * @since 2.2.10
 */
public final class ProxyEvictSlice implements Slice {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.http.slice";

    /**
     * Sidecars proxies store next to a cached file: checksums, the cached
     * response metadata, npm's tarball metadata, PEP 658 core metadata.
     */
    private static final List<String> SIDECARS = List.of(
        ".sha1", ".md5", ".sha256", ".sha512", ".pantera-meta.json", ".meta", ".metadata"
    );

    /**
     * Go module file; the index row is keyed {@code <module>/@v/<version>}.
     */
    private static final Pattern GO_ZIP = Pattern.compile("^(.+)/@v/v([^/]+)\\.zip$");

    /**
     * Composer dist; the index row is keyed {@code <vendor>/<pkg>/<version>}.
     */
    private static final Pattern PHP_DIST =
        Pattern.compile("^dist/([^/]+/[^/]+)/([^/@]+?)(?:@[^/]*)?(?:\\.zip)?$");

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Repository type, e.g. {@code maven-proxy}.
     */
    private final String type;

    /**
     * Cache storage; empty when the proxy caches nothing.
     */
    private final Optional<Storage> storage;

    /**
     * Shared artifact delete.
     */
    private final ArtifactDeletion deletion;

    /**
     * The repository's caches of a path.
     */
    private final ProxyPathCaches caches;

    /**
     * Ctor.
     * @param repo Repository name
     * @param type Repository type
     * @param storage Cache storage, empty when the proxy caches nothing
     * @param deletion Shared artifact delete
     * @param caches The repository's caches of a path
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public ProxyEvictSlice(
        final String repo, final String type, final Optional<Storage> storage,
        final ArtifactDeletion deletion, final ProxyPathCaches caches
    ) {
        this.repo = repo;
        this.type = type;
        this.storage = storage;
        this.deletion = deletion;
        this.caches = caches;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        // Captured at entry: continuations may run on storage workers.
        final AuditContext audit = new AuditContext(headers);
        final String owner = new Login(headers).getValue();
        final String path = RepoDeleteSlice.clean(line.uri().getPath());
        return body.asBytesFuture().thenCompose(
            ignored -> {
                final CompletableFuture<Response> res;
                if (path.isEmpty()) {
                    res = this.refuse(
                        audit, owner, "/", "Refusing to evict the repository root"
                    );
                } else if (RepoDeleteSlice.invalid(this.deletion, path)) {
                    res = this.refuse(audit, owner, path, "Invalid path");
                } else if (this.storage.isEmpty()) {
                    this.audit(audit, owner, path, false);
                    res = CompletableFuture.completedFuture(
                        ResponseBuilder.notFound()
                            .textBody("Repository caches nothing: " + this.repo)
                            .build()
                    );
                } else {
                    res = this.evict(this.storage.get(), path, audit, owner, headers);
                }
                return res;
            }
        );
    }

    /**
     * Evict a path.
     * @param asto Cache storage
     * @param path Path
     * @param audit Request context
     * @param owner Authenticated user
     * @param headers Request headers (request context for the logs)
     * @return Response
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Response> evict(
        final Storage asto, final String path, final AuditContext audit,
        final String owner, final Headers headers
    ) {
        return asto.exists(new Key.From(path)).thenCompose(
            file -> this.sidecars(asto, path, file).thenCompose(
                sidecars -> this.deletion.delete(
                    this.repo, this.type, asto, path,
                    file ? RepoPathRemoval.Mode.FILE : RepoPathRemoval.Mode.FOLDER,
                    headers
                ).thenCompose(
                    deleted -> this.versionRows(asto, path, file, headers).thenCompose(
                        rows -> this.caches.evict(path, !file).thenApply(
                            metadata -> deleted || sidecars > 0 || rows > 0 || metadata
                        )
                    )
                )
            )
        ).handle(
            (found, err) -> {
                final Response rsp;
                RequestContextHeaders.bindToMdc(headers);
                if (err == null) {
                    this.audit(audit, owner, path, found);
                    if (found) {
                        EcsLogger.info(ProxyEvictSlice.LOGGER)
                            .message("Evicted cached path from proxy repository")
                            .eventCategory("file")
                            .eventAction("proxy_cache_evict")
                            .eventOutcome("success")
                            .field("repository.name", this.repo)
                            .field("file.path", path)
                            .field("log.source", "application")
                            .log();
                        rsp = ResponseBuilder.noContent().build();
                    } else {
                        rsp = ResponseBuilder.notFound()
                            .textBody("Nothing is cached at path: " + path)
                            .build();
                    }
                } else {
                    AuditLogger.delete(
                        audit, this.type, this.repo, path, null, owner,
                        AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_STORAGE_UNAVAILABLE
                    );
                    EcsLogger.error(ProxyEvictSlice.LOGGER)
                        .message("Proxy cache eviction failed")
                        .eventCategory("file")
                        .eventAction("proxy_cache_evict")
                        .eventOutcome("failure")
                        .field("repository.name", this.repo)
                        .field("file.path", path)
                        .error(err)
                        .field("log.source", "application")
                        .log();
                    rsp = ResponseBuilder.internalError().build();
                }
                return rsp;
            }
        );
    }

    /**
     * Delete the sidecars stored next to a cached file.
     * @param asto Cache storage
     * @param path File path
     * @param file Whether the path is a stored file
     * @return Number of sidecars deleted
     */
    private CompletableFuture<Integer> sidecars(
        final Storage asto, final String path, final boolean file
    ) {
        CompletableFuture<Integer> res = CompletableFuture.completedFuture(0);
        if (file) {
            for (final String ext : ProxyEvictSlice.SIDECARS) {
                final Key key = new Key.From(path + ext);
                res = res.thenCompose(
                    count -> asto.exists(key).thenCompose(
                        exists -> {
                            final CompletableFuture<Integer> one;
                            if (exists) {
                                one = asto.delete(key).thenApply(nothing -> count + 1);
                            } else {
                                one = CompletableFuture.completedFuture(count);
                            }
                            return one;
                        }
                    )
                );
            }
        }
        return res;
    }

    /**
     * Remove the version-level index rows a file eviction leaves stale
     * where the format indexes a version, not a file: a maven version
     * directory left without files, a go module {@code .zip}, a composer
     * dist.
     * @param asto Cache storage
     * @param path Evicted path
     * @param file Whether a file was evicted
     * @param headers Request headers (request context for the logs)
     * @return Number of rows removed
     */
    private CompletableFuture<Integer> versionRows(
        final Storage asto, final String path, final boolean file, final Headers headers
    ) {
        final String family = this.type.toLowerCase(Locale.ROOT);
        final CompletableFuture<Integer> res;
        if (!file) {
            res = CompletableFuture.completedFuture(0);
        } else if (family.startsWith("maven") || family.startsWith("gradle")) {
            final int slash = path.lastIndexOf('/');
            if (slash > 0) {
                final String dir = path.substring(0, slash);
                res = asto.list(new Key.From(dir)).thenCompose(
                    left -> {
                        final String inside = dir + "/";
                        final boolean empty = left.stream()
                            .noneMatch(key -> key.string().startsWith(inside));
                        final CompletableFuture<Integer> rows;
                        if (empty) {
                            rows = this.deletion.unindex(this.repo, dir, headers);
                        } else {
                            rows = CompletableFuture.completedFuture(0);
                        }
                        return rows;
                    }
                );
            } else {
                res = CompletableFuture.completedFuture(0);
            }
        } else {
            res = this.versionRow(family, path)
                .map(row -> this.deletion.unindex(this.repo, row, headers))
                .orElseGet(() -> CompletableFuture.completedFuture(0));
        }
        return res;
    }

    /**
     * Version-level index row of a go / composer artifact file.
     * @param family Lower-case repository type
     * @param path File path
     * @return Index path, if the format indexes the file's version
     */
    private Optional<String> versionRow(final String family, final String path) {
        final List<String> rows = new ArrayList<>(1);
        if (family.startsWith("go")) {
            final Matcher go = ProxyEvictSlice.GO_ZIP.matcher(path);
            if (go.matches()) {
                rows.add(go.group(1) + "/@v/" + go.group(2));
            }
        } else if (family.startsWith("php") || family.startsWith("composer")) {
            final Matcher dist = ProxyEvictSlice.PHP_DIST.matcher(path);
            if (dist.matches()) {
                rows.add(dist.group(1) + "/" + dist.group(2));
            }
        }
        return rows.stream().findFirst();
    }

    /**
     * Refuse an eviction with {@code 400} and audit the refusal.
     * @param audit Request context captured at entry
     * @param owner Authenticated user
     * @param path Audited path ({@code /} for the repository root)
     * @param reason Response body
     * @return Response
     */
    private CompletableFuture<Response> refuse(
        final AuditContext audit, final String owner, final String path, final String reason
    ) {
        AuditLogger.delete(
            audit, this.type, this.repo, path, null, owner,
            AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_FORBIDDEN
        );
        return CompletableFuture.completedFuture(
            ResponseBuilder.badRequest().textBody(reason).build()
        );
    }

    /**
     * Audit an eviction.
     * @param audit Request context
     * @param owner Authenticated user
     * @param path Path
     * @param found Whether anything was evicted
     */
    private void audit(
        final AuditContext audit, final String owner, final String path, final boolean found
    ) {
        AuditLogger.delete(
            audit, this.type, this.repo, path, null, owner,
            found ? AuditLogger.OUTCOME_SUCCESS : AuditLogger.OUTCOME_FAILURE,
            found ? null : AuditLogger.REASON_NOT_FOUND
        );
    }
}
