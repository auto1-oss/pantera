/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http;

import com.artipie.asto.Content;
import com.artipie.asto.Meta;
import com.artipie.composer.Repository;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import com.artipie.scheduling.ArtifactEvent;
import com.jcabi.log.Logger;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice for adding a package to the repository in ZIP format.
 * See <a href="https://getcomposer.org/doc/05-repositories.md#artifact">Artifact repository</a>.
 */
@SuppressWarnings({"PMD.SingularField", "PMD.UnusedPrivateField"})
final class AddArchiveSlice implements Slice {
    /**
     * Composer HTTP for entry point.
     * See <a href="https://getcomposer.org/doc/04-schema.md#version">docs</a>.
     */
    public static final Pattern PATH = Pattern.compile(
        "^/(?:(?<dir>(?!\\.\\.)(?:[A-Za-z0-9_.\\-]+/)+))?(?<full>(?<name>[A-Za-z0-9_.\\-]*)-(?<version>v?\\d+\\.\\d+\\.\\d+(?:[-\\w]*))\\.zip)$"
    );

    /**
     * Repository type.
     */
    public static final String REPO_TYPE = "php";

    /**
     * Repository.
     */
    private final Repository repository;

    /**
     * Artifact events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     * @param repository Repository.
     * @param rname Repository name
     */
    AddArchiveSlice(final Repository repository, final String rname) {
        this(repository, Optional.empty(), rname);
    }

    /**
     * Ctor.
     * @param repository Repository
     * @param events Artifact events
     * @param rname Repository name
     */
    AddArchiveSlice(
        final Repository repository, final Optional<Queue<ArtifactEvent>> events,
        final String rname
    ) {
        this.repository = repository;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String uri = line.uri().getPath();
        final Matcher matcher = AddArchiveSlice.PATH.matcher(uri);
        if (matcher.matches()) {
            final String packageName = matcher.group("name");
            final String version = matcher.group("version");
            final String dir = matcher.group("dir");
            if (dir != null && dir.contains("..")) {
                Logger.warn(this, "Rejected archive path with '..': %s", uri);
                return CompletableFuture.completedFuture(ResponseBuilder.badRequest().build());
            }
            final String full = (dir == null ? "" : dir) + matcher.group("full");
            final Archive.Zip archive =
                new Archive.Zip(new Archive.Name(full, version));
            
            CompletableFuture<Void> res =
                this.repository.addArchive(archive, new Content.From(body));
            
            if (this.events.isPresent()) {
                res = res.thenAccept(
                    nothing -> {
                        final long size;
                        try {
                            size = this.repository.storage().metadata(archive.name().artifact())
                                .thenApply(meta -> meta.read(Meta.OP_SIZE))
                                .join()
                                .map(Long::longValue)
                                .orElse(0L);
                        } catch (final Exception e) {
                            Logger.warn(this, "Failed to get file size: %s", e.getMessage());
                            return;
                        }
                        final long created = System.currentTimeMillis();
                        this.events.get().add(
                            new ArtifactEvent(
                                AddArchiveSlice.REPO_TYPE,
                                this.rname,
                                new Login(headers).getValue(),
                                packageName,
                                version,
                                size,
                                created,
                                (Long) null  // No release date for local uploads
                            )
                        );
                        Logger.info(
                            this,
                            "Recorded Composer upload: %s:%s (repo=%s, size=%d)",
                            packageName,
                            version,
                            this.rname,
                            size
                        );
                    }
                );
            }
            return res.thenApply(nothing -> ResponseBuilder.created().build());
        }
        return ResponseBuilder.badRequest().completedFuture();
    }
}
