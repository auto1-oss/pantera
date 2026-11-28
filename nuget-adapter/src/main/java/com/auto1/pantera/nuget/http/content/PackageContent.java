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
package com.auto1.pantera.nuget.http.content;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.PresignResolver;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.nuget.PackageIdentity;
import com.auto1.pantera.nuget.Repository;
import com.auto1.pantera.nuget.http.Resource;
import com.auto1.pantera.nuget.http.Route;
import com.auto1.pantera.nuget.http.metadata.ContentLocation;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Package content route.
 * See <a href="https://docs.microsoft.com/en-us/nuget/api/package-base-address-resource">Package Content</a>
 */
@SuppressWarnings("deprecation")
public final class PackageContent implements Route, ContentLocation {

    /**
     * Base URL of repository.
     */
    private final URL base;

    /**
     * Repository to read content from.
     */
    private final Repository repository;

    /**
     * WS1.7 per-repo presigned-direct-download policy. {@link
     * DownloadPolicy#streamOnly()} (the two-arg-ctor default) is byte-identical
     * to pre-WS1.7 behaviour: no {@code 302} is ever issued.
     */
    private final DownloadPolicy policy;

    /**
     * Repo name for the {@code pantera.storage.download.decision} metric tag,
     * derived from the outermost {@link SubStorage} prefix.
     */
    private final String repoName;

    /**
     * Ctor -- stream-only (pre-WS1.7 behaviour).
     *
     * @param base Base URL of repository.
     * @param repository Repository to read content from.
     */
    public PackageContent(final URL base, final Repository repository) {
        this(base, repository, DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with an explicit WS1.7 (spec {@code WS1-storage-for-scale.md}
     * &sect;3.B2) download policy. Only {@code .nupkg}/{@code .snupkg} package
     * bytes are redirect-eligible; the versions index, {@code .nuspec} manifest
     * and every other metadata resource always stream.
     *
     * @param base Base URL of repository.
     * @param repository Repository to read content from.
     * @param policy WS1.7 download policy.
     */
    public PackageContent(final URL base, final Repository repository, final DownloadPolicy policy) {
        this.base = base;
        this.repository = repository;
        this.policy = policy;
        final Storage storage = repository.storage();
        this.repoName = storage instanceof SubStorage sub ? sub.prefix().string() : "unknown";
    }

    /**
     * WS1.7: resolve a presigned URL for a package-content key, or empty when a
     * redirect is not applicable (stream-only policy, a non-package metadata
     * key, no presigner, or not-yet-durable).
     *
     * @param key Package content key.
     * @return Presigned URL to redirect to, or empty to stream.
     */
    private Optional<URI> presignedFor(final Key key) {
        if (this.policy.mode() == DownloadMode.STREAM || !PackageContent.redirectEligible(key)) {
            return Optional.empty();
        }
        return PresignResolver.resolve(this.repository.storage(), key)
            .flatMap(target -> target.presignIfDurable(this.policy.presignTtlSeconds()));
    }

    /**
     * @param key Package content key
     * @return {@code true} iff the key addresses a NuGet package archive
     *  ({@code .nupkg}/{@code .snupkg}) -- never the versions index or a
     *  {@code .nuspec} manifest
     */
    private static boolean redirectEligible(final Key key) {
        final String path = key.string().toLowerCase(Locale.ROOT);
        return path.endsWith(".nupkg") || path.endsWith(".snupkg");
    }

    /**
     * WS1.7: record the redirect-vs-stream serving decision.
     *
     * @param decision {@code "redirect"} or {@code "stream"}
     */
    private void recordDecision(final String decision) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordDownloadDecision(this.repoName, decision);
        }
    }

    @Override
    public String path() {
        return "/content";
    }

    @Override
    public Resource resource(final String path) {
        return new PackageResource(path, this.repository);
    }

    @Override
    public URL url(final PackageIdentity identity) {
        final String relative = String.format(
            "%s%s/%s",
            this.base.getPath(),
            this.path(),
            identity.nupkgKey().string()
        );
        try {
            return new URL(this.base, relative);
        } catch (final MalformedURLException ex) {
            throw new IllegalStateException(
                String.format("Failed to build URL from base: '%s'", this.base),
                ex
            );
        }
    }

    /**
     * Package content resource.
     *
     * @since 0.1
     */
    private class PackageResource implements Resource {

        /**
         * Resource path.
         */
        private final String path;

        /**
         * Repository to read content from.
         */
        private final Repository repository;

        /**
         * Ctor.
         *
         * @param path Resource path.
         * @param repository Storage to read content from.
         */
        PackageResource(final String path, final Repository repository) {
            this.path = path;
            this.repository = repository;
        }

        @Override
        public CompletableFuture<Response> get(final Headers headers) {
            return this.key().<CompletableFuture<Response>>map(
                this::serve
            ).orElse(ResponseBuilder.notFound().completedFuture());
        }

        /**
         * WS1.7: answer {@code 302} to a presigned URL for a redirect-eligible
         * package archive when one is currently possible; otherwise stream the
         * bytes exactly as before. This route is GET-only ({@code NuGet}
         * answers 405 for anything else), so no method guard is needed here.
         *
         * @param key Package content key
         * @return Response
         */
        private CompletableFuture<Response> serve(final Key key) {
            final Optional<URI> presigned = PackageContent.this.presignedFor(key);
            if (presigned.isPresent()) {
                PackageContent.this.recordDecision("redirect");
                return CompletableFuture.completedFuture(
                    ResponseBuilder.found()
                        .header(new Location(presigned.get().toString()))
                        .build()
                );
            }
            if (PackageContent.this.policy.mode() != DownloadMode.STREAM
                && PackageContent.redirectEligible(key)) {
                PackageContent.this.recordDecision("stream");
            }
            return this.repository.content(key)
                .thenApply(
                    existing -> existing.map(
                        data -> ResponseBuilder.ok().body(data).build()
                    ).orElse(ResponseBuilder.notFound().build())
                ).toCompletableFuture();
        }

        @Override
        public CompletableFuture<Response> put(Headers headers, Content body) {
            return ResponseBuilder.methodNotAllowed().completedFuture();
        }

        /**
         * Tries to build key to storage value from path.
         *
         * @return Key to storage value, if there is one.
         */
        private Optional<Key> key() {
            final String prefix = String.format("%s/", path());
            if (this.path.startsWith(prefix)) {
                return Optional.of(new Key.From(this.path.substring(prefix.length())));
            }
            return Optional.empty();
        }
    }
}
