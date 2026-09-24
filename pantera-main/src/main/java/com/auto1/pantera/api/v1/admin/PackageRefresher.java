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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metadata.ProxyMetadataRevalidators;
import com.auto1.pantera.http.cache.NegativeCache;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Clears every cache layer that can hide or stale-serve one package,
 * cluster-wide, in dependency order:
 * <ol>
 *   <li>each proxy's cached raw metadata is revalidated against its upstream
 *       through the adapter's own refresh hook (a proxy without one is
 *       reported {@code unsupported});</li>
 *   <li>the cooldown-filtered envelopes are dropped from this node's L1, from
 *       L2 by SCAN, and from peers' L1 over pub/sub — after step 1, so no
 *       envelope is recomputed from the stale listing;</li>
 *   <li>the package's negative-cache entries are dropped in every scope, both
 *       tiers, on every node.</li>
 * </ol>
 * The package is inspected before and after, so the caller sees the effect.
 *
 * @since 2.2.9
 */
public final class PackageRefresher {

    /**
     * Inspector.
     */
    private final PackageInspector inspector;

    /**
     * Negative cache.
     */
    private final NegativeCache negative;

    /**
     * Envelope cache supplier.
     */
    private final Supplier<Optional<FilteredMetadataCache>> envelopes;

    /**
     * Raw-metadata revalidation hooks.
     */
    private final ProxyMetadataRevalidators hooks;

    /**
     * Ctor.
     *
     * @param inspector Inspector
     * @param negative Negative cache
     * @param envelopes Envelope cache supplier
     * @param hooks Revalidation hooks
     */
    public PackageRefresher(
        final PackageInspector inspector, final NegativeCache negative,
        final Supplier<Optional<FilteredMetadataCache>> envelopes,
        final ProxyMetadataRevalidators hooks
    ) {
        this.inspector = inspector;
        this.negative = negative;
        this.envelopes = envelopes;
        this.hooks = hooks;
    }

    /**
     * Refresh a package.
     *
     * @param family Format family
     * @param raw Package name as typed
     * @param repo Named repository, may be null
     * @param authorization Caller's Authorization header
     * @return Future of {@code {before, after, revalidated, cleared}}
     */
    public CompletableFuture<JsonObject> refresh(
        final String family, final String raw, final String repo, final String authorization
    ) {
        final PackageName pkg = new PackageName(family, raw);
        final List<RepoTopology.RepoInfo> scope = this.inspector.scope(family, repo);
        return this.inspector.inspect(family, raw, repo, authorization)
            .thenCompose(before -> this.revalidate(scope, pkg)
                .thenCompose(revalidated -> this.dropEnvelopes(pkg)
                    .thenCompose(envelopes -> this.negative.invalidateMatching(pkg::matches)
                        .thenCompose(negatives -> this.inspector.inspect(
                            family, raw, repo, authorization
                        ).thenApply(after -> new JsonObject()
                            .put("package", pkg.raw())
                            .put("repoType", pkg.family())
                            .put("node", after.getString("node"))
                            .put("revalidated", revalidated)
                            .put("cleared", new JsonObject()
                                .put("envelopes", new JsonObject()
                                    .put("l1", envelopes[0]).put("l2", envelopes[1]))
                                .put("negativeCache", new JsonObject()
                                    .put("l1", negatives.l1()).put("l2", negatives.l2())))
                            .put("before", before)
                            .put("after", after))))));
    }

    /**
     * Revalidate raw metadata in every proxy of the scope.
     *
     * @param scope Repositories
     * @param pkg Package
     * @return Future of {@code [{repo, outcome}]}
     */
    private CompletableFuture<JsonArray> revalidate(
        final List<RepoTopology.RepoInfo> scope, final PackageName pkg
    ) {
        final List<CompletableFuture<JsonObject>> all = new ArrayList<>();
        for (final RepoTopology.RepoInfo info : scope) {
            if (!"proxy".equals(info.mode())) {
                continue;
            }
            final Optional<ProxyMetadataRevalidators.Revalidator> hook =
                this.hooks.forRepo(info.name());
            final CompletableFuture<String> outcome;
            if (hook.isPresent()) {
                outcome = hook.get().revalidate(pkg.raw())
                    .exceptionally(err -> "failed: " + PackageInspector.cause(err).getMessage());
            } else {
                outcome = CompletableFuture.completedFuture("unsupported");
            }
            all.add(outcome.thenApply(
                res -> new JsonObject().put("repo", info.name()).put("outcome", res)
            ));
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                final JsonArray arr = new JsonArray();
                all.forEach(item -> arr.add(item.join()));
                return arr;
            });
    }

    /**
     * Drop the package's envelopes under every stored spelling, awaiting
     * the L2 sweeps.
     *
     * @param pkg Package
     * @return Future of {@code [l1, l2]} totals
     */
    private CompletableFuture<int[]> dropEnvelopes(final PackageName pkg) {
        final Optional<FilteredMetadataCache> cache = this.envelopes.get();
        if (cache.isEmpty()) {
            return CompletableFuture.completedFuture(new int[]{0, 0});
        }
        final List<CompletableFuture<int[]>> all = new ArrayList<>();
        for (final String name : pkg.storedForms()) {
            all.add(cache.get().invalidatePackageAwaiting(name));
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                final int[] sum = new int[2];
                for (final CompletableFuture<int[]> one : all) {
                    sum[0] = sum[0] + one.join()[0];
                    sum[1] = sum[1] + one.join()[1];
                }
                return sum;
            });
    }
}
