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
package com.auto1.pantera.nuget.http.publish;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.nuget.PackageIdentity;
import com.auto1.pantera.nuget.Repository;
import com.auto1.pantera.nuget.http.Resource;
import com.auto1.pantera.nuget.http.Route;
import com.auto1.pantera.nuget.metadata.PackageId;
import com.auto1.pantera.nuget.metadata.Version;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package delete: {@code DELETE {PackagePublish}/{id}/{version}}, what
 * {@code dotnet nuget delete <id> <version>} sends to the publish resource
 * the service index advertises.
 * See <a href="https://learn.microsoft.com/en-us/nuget/api/package-publish-resource#delete-a-package">Delete a package</a>.
 *
 * <p>Deleting removes the version's {@code .nupkg}, {@code .nuspec} and
 * hash and drops it from the package's version list (a hard delete, not the
 * nuget.org "unlist"). Answers 204 No Content, or 404 when the version (or
 * the path) does not name a stored package version. Every outcome is
 * audited as {@code artifact_delete}; a successful delete also publishes a
 * delete-version artifact event so the search index row goes.</p>
 */
public final class PackageDelete implements Route {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "nuget";

    /**
     * {@code /package/<id>/<version>} with a trailing slash tolerated.
     */
    private static final Pattern PATH = Pattern.compile(
        "^/package/(?<id>[A-Za-z0-9_][A-Za-z0-9._-]*)/(?<version>[A-Za-z0-9][A-Za-z0-9.+-]*)/?$"
    );

    /**
     * Repository.
     */
    private final Repository repository;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * Artifact events.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Ctor.
     *
     * @param repository Repository
     * @param events Artifact events
     * @param name Repository name
     */
    public PackageDelete(
        final Repository repository, final Optional<Queue<ArtifactEvent>> events,
        final String name
    ) {
        this.repository = repository;
        this.events = events;
        this.name = name;
    }

    @Override
    public String path() {
        return "/package";
    }

    @Override
    public Resource resource(final String path) {
        return new Target(this.repository, this.events, this.name, path);
    }

    /**
     * One package version addressed by a delete request.
     */
    private static final class Target implements Resource {

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
        private final String name;

        /**
         * Request path.
         */
        private final String path;

        /**
         * Ctor.
         *
         * @param repository Repository
         * @param events Artifact events
         * @param name Repository name
         * @param path Request path
         */
        Target(
            final Repository repository, final Optional<Queue<ArtifactEvent>> events,
            final String name, final String path
        ) {
            this.repository = repository;
            this.events = events;
            this.name = name;
            this.path = path;
        }

        @Override
        public CompletableFuture<Response> get(final Headers headers) {
            return ResponseBuilder.methodNotAllowed().completedFuture();
        }

        @Override
        public CompletableFuture<Response> put(final Headers headers, final Content body) {
            return body.discard().thenApply(
                ignored -> ResponseBuilder.methodNotAllowed().build()
            );
        }

        @Override
        public CompletableFuture<Response> delete(final Headers headers) {
            RequestContextHeaders.bindToMdc(headers);
            final AuditContext ctx = new AuditContext(headers);
            final String owner = new Login(headers).getValue();
            final Optional<PackageIdentity> identity = Target.identity(this.path);
            final CompletableFuture<Response> res;
            if (identity.isEmpty()) {
                AuditLogger.delete(
                    ctx, PackageDelete.REPO_TYPE, this.name, this.path, null, owner,
                    AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                );
                res = ResponseBuilder.notFound().completedFuture();
            } else {
                final PackageIdentity pkg = identity.get();
                res = this.repository.delete(pkg).thenApply(
                    deleted -> {
                        RequestContextHeaders.bindToMdc(headers);
                        final Response rsp;
                        if (deleted) {
                            this.events.ifPresent(
                                queue -> queue.add(
                                    new ArtifactEvent(
                                        PackageDelete.REPO_TYPE, this.name,
                                        pkg.packageId(), pkg.packageVersion()
                                    )
                                )
                            );
                            AuditLogger.delete(
                                ctx, PackageDelete.REPO_TYPE, this.name, pkg.packageId(),
                                pkg.packageVersion(), owner, AuditLogger.OUTCOME_SUCCESS, null
                            );
                            rsp = ResponseBuilder.noContent().build();
                        } else {
                            AuditLogger.delete(
                                ctx, PackageDelete.REPO_TYPE, this.name, pkg.packageId(),
                                pkg.packageVersion(), owner,
                                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_NOT_FOUND
                            );
                            rsp = ResponseBuilder.notFound().build();
                        }
                        return rsp;
                    }
                ).toCompletableFuture();
            }
            return res;
        }

        /**
         * Package identity named by a delete path.
         *
         * @param path Request path
         * @return Identity, empty when the path names no valid id/version
         */
        private static Optional<PackageIdentity> identity(final String path) {
            final Matcher matcher = PackageDelete.PATH.matcher(path);
            Optional<PackageIdentity> res = Optional.empty();
            if (matcher.matches() && !matcher.group("id").contains("..")) {
                final Version version = new Version(matcher.group("version"));
                try {
                    version.normalized();
                    res = Optional.of(
                        new PackageIdentity(new PackageId(matcher.group("id")), version)
                    );
                } catch (final IllegalStateException ex) {
                    res = Optional.empty();
                }
            }
            return res;
        }
    }
}
