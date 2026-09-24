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

import com.auto1.pantera.cooldown.CooldownPackageRow;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.context.HandlerExecutor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Explains, per version of one package, what every cache layer and the
 * cooldown state say — and flags where they disagree.
 *
 * <p>For each proxy/group repository of the package's format (or one named
 * repository and, for a group, everything it reaches) the served version
 * listing is fetched in-process with the caller's credentials — what a
 * client of that repository is shown right now — next to the
 * filtered-metadata envelope (L1/L2), the negative-cache entries, and the
 * package's live and archived cooldown records. A version is a
 * {@code mismatch} when its cooldown state and its visibility disagree:
 * released/expired yet hidden where it was blocked, hidden in a group while
 * a member lists it, or blocked yet still listed where it is blocked.</p>
 *
 * @since 2.2.9
 */
public final class PackageInspector {

    /**
     * Largest listing body read (npm packuments run to tens of MB).
     */
    private static final int MAX_LISTING = 64 * 1024 * 1024;

    /**
     * L2 keys scanned for the negative-cache view.
     */
    private static final int NEG_SCAN_LIMIT = 100_000;

    /**
     * Serving-side access.
     */
    private final AdminDiagnostics diag;

    /**
     * Negative cache.
     */
    private final NegativeCache negative;

    /**
     * Envelope cache, when installed.
     */
    private final Supplier<Optional<FilteredMetadataCache>> envelopes;

    /**
     * Cooldown records.
     */
    private final CooldownLookup cooldown;

    /**
     * Ctor.
     *
     * @param diag Serving-side access
     * @param negative Negative cache
     * @param envelopes Envelope cache supplier
     * @param cooldown Cooldown records
     */
    public PackageInspector(
        final AdminDiagnostics diag, final NegativeCache negative,
        final Supplier<Optional<FilteredMetadataCache>> envelopes,
        final CooldownLookup cooldown
    ) {
        this.diag = diag;
        this.negative = negative;
        this.envelopes = envelopes;
        this.cooldown = cooldown;
    }

    /**
     * Repositories an inspection covers.
     *
     * @param family Format family
     * @param repo Named repository, may be null
     * @return Repositories, selected ones first, then what groups reach
     * @throws IllegalArgumentException When {@code repo} is not configured
     */
    public List<RepoTopology.RepoInfo> scope(final String family, final String repo) {
        final List<RepoTopology.RepoInfo> selected = new ArrayList<>();
        if (repo != null && !repo.isBlank()) {
            selected.add(this.diag.topology().repo(repo).orElseThrow(
                () -> new IllegalArgumentException("Repository '" + repo + "' not found")
            ));
        } else {
            final String fam = family.toLowerCase(Locale.ROOT);
            this.diag.topology().all().stream()
                .filter(info -> info.family().equals(fam) && !"local".equals(info.mode()))
                .sorted(Comparator.comparing(RepoTopology.RepoInfo::name))
                .forEach(selected::add);
        }
        final Map<String, RepoTopology.RepoInfo> all = new LinkedHashMap<>();
        for (final RepoTopology.RepoInfo info : selected) {
            all.put(info.name(), info);
        }
        for (final RepoTopology.RepoInfo info : selected) {
            if (info.group()) {
                for (final RepoTopology.RepoInfo member : this.diag.topology().reachable(info.name())) {
                    all.putIfAbsent(member.name(), member);
                }
            }
        }
        return new ArrayList<>(all.values());
    }

    /**
     * Inspect a package.
     *
     * @param family Format family ({@code npm}, {@code pypi}, ...)
     * @param raw Package name as typed
     * @param repo Named repository, may be null
     * @param authorization Caller's Authorization header, may be null
     * @return Future of the inspection document
     */
    public CompletableFuture<JsonObject> inspect(
        final String family, final String raw, final String repo, final String authorization
    ) {
        final List<RepoTopology.RepoInfo> repos = this.scope(family, repo);
        final PackageName pkg = this.packageName(family, raw, repos);
        final Map<String, CompletableFuture<JsonObject>> metadata = new LinkedHashMap<>();
        for (final RepoTopology.RepoInfo info : repos) {
            metadata.put(info.name(), this.metadata(info, pkg, authorization));
        }
        final CompletableFuture<Map<String, JsonArray>> negatives = this.negatives(pkg);
        final CompletableFuture<List<CooldownPackageRow>> rows = CompletableFuture.supplyAsync(
            () -> this.cooldown.find(
                repos.stream().map(RepoTopology.RepoInfo::name).collect(Collectors.toList()),
                pkg.storedForms()
            ),
            HandlerExecutor.get()
        );
        final Map<String, CompletableFuture<JsonObject>> envelope = new LinkedHashMap<>();
        for (final RepoTopology.RepoInfo info : repos) {
            envelope.put(info.name(), this.envelope(info.name(), pkg));
        }
        final List<CompletableFuture<?>> all = new ArrayList<>(metadata.values());
        all.addAll(envelope.values());
        all.add(negatives);
        all.add(rows);
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> this.document(
                pkg, repos, metadata, envelope, negatives.join(), rows.join()
            ));
    }

    /**
     * Package as the cooldown tables and caches store it. A docker
     * reference is reduced to its image name the way the docker client
     * would ({@code localhost:8081/docker_group/ubuntu:24.04} to
     * {@code library/ubuntu}); other formats keep the typed name.
     *
     * @param family Format family
     * @param raw Name as typed
     * @param repos Repositories in scope
     * @return Package name
     */
    PackageName packageName(
        final String family, final String raw, final List<RepoTopology.RepoInfo> repos
    ) {
        final String name;
        if ("docker".equals(new PackageName(family, "").family())) {
            name = new DockerImageName(
                raw, repos.stream().map(RepoTopology.RepoInfo::name).collect(Collectors.toList())
            ).name();
        } else {
            name = raw;
        }
        return new PackageName(family, name);
    }

    /**
     * Assemble the inspection document.
     *
     * @param pkg Package
     * @param repos Repositories
     * @param metadata Metadata per repository
     * @param envelope Envelope state per repository
     * @param negatives Negative-cache entries per scope
     * @param rows Cooldown records
     * @return Document
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private JsonObject document(
        final PackageName pkg, final List<RepoTopology.RepoInfo> repos,
        final Map<String, CompletableFuture<JsonObject>> metadata,
        final Map<String, CompletableFuture<JsonObject>> envelope,
        final Map<String, JsonArray> negatives, final List<CooldownPackageRow> rows
    ) {
        final JsonArray out = new JsonArray();
        final Map<String, Set<String>> visible = new LinkedHashMap<>();
        for (final RepoTopology.RepoInfo info : repos) {
            final JsonObject meta = metadata.get(info.name()).join();
            if (meta.getInteger("status", 0) == 200 && meta.containsKey("visibleVersions")) {
                final Set<String> versions = new LinkedHashSet<>();
                meta.getJsonArray("visibleVersions").forEach(ver -> versions.add(String.valueOf(ver)));
                visible.put(info.name(), versions);
            }
            final JsonObject item = new JsonObject()
                .put("name", info.name())
                .put("type", info.type())
                .put("mode", info.mode())
                .put("metadata", meta)
                .put("envelope", envelope.get(info.name()).join())
                .put("negativeCache", negatives.getOrDefault(info.name(), new JsonArray()));
            if (info.group()) {
                item.put("members", new JsonArray(info.members()));
            }
            out.add(item);
        }
        return new JsonObject()
            .put("package", pkg.raw())
            .put("repoType", pkg.family())
            .put("node", this.diag.node())
            .put("repos", out)
            .put("versions", new VersionTable(this.diag.topology(), visible, rows).json());
    }

    /**
     * Served version listing of one repository.
     *
     * @param info Repository
     * @param pkg Package
     * @param authorization Caller's Authorization header
     * @return Future of the metadata section (never fails)
     */
    private CompletableFuture<JsonObject> metadata(
        final RepoTopology.RepoInfo info, final PackageName pkg, final String authorization
    ) {
        final MetadataFormat format = new MetadataFormat(info.family());
        final Optional<MetadataFormat.Listing> listing = format.listing(pkg);
        if (listing.isEmpty()) {
            return CompletableFuture.completedFuture(
                new JsonObject().put("unsupported", true).put("fetchedVia", "in-process")
            );
        }
        return this.diag.fetch().get(
            info.name(), listing.get().path(), authorization, listing.get().accept(), MAX_LISTING
        ).thenApply(resp -> {
            final JsonObject meta = new JsonObject()
                .put("status", resp.status())
                .put("fetchedVia", "in-process")
                .put("path", listing.get().path());
            if (resp.status() == 200) {
                if (resp.truncated()) {
                    meta.put("error", "listing larger than " + MAX_LISTING + " bytes");
                } else {
                    try {
                        meta.put("visibleVersions", new JsonArray(format.versions(pkg, resp.body())));
                    } catch (final RuntimeException ex) {
                        meta.put("error", "unreadable listing: " + ex.getMessage());
                    }
                }
            }
            return meta;
        }).exceptionally(err -> new JsonObject()
            .put("status", 0)
            .put("fetchedVia", "in-process")
            .put("path", listing.get().path())
            .put("error", String.valueOf(PackageInspector.cause(err).getMessage())));
    }

    /**
     * Envelope state of a package in a repository (any stored spelling).
     *
     * @param repo Repository name
     * @param pkg Package
     * @return Future of {@code {l1:{present, ageMs}, l2:{present, ttlRemainingMs}}}
     */
    private CompletableFuture<JsonObject> envelope(final String repo, final PackageName pkg) {
        final Optional<FilteredMetadataCache> cache = this.envelopes.get();
        if (cache.isEmpty()) {
            return CompletableFuture.completedFuture(PackageInspector.envelopeJson(
                new FilteredMetadataCache.EnvelopeState(false, -1L, false, -2L)
            ));
        }
        final List<CompletableFuture<FilteredMetadataCache.EnvelopeState>> probes =
            pkg.storedForms().stream().map(name -> cache.get().probe(repo, name))
                .collect(Collectors.toList());
        return CompletableFuture.allOf(probes.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                boolean l1 = false;
                long age = -1L;
                boolean l2 = false;
                long ttl = -2L;
                for (final CompletableFuture<FilteredMetadataCache.EnvelopeState> probe : probes) {
                    final FilteredMetadataCache.EnvelopeState state = probe.join();
                    if (state.l1Present()) {
                        l1 = true;
                        age = age < 0 ? state.l1AgeMs() : Math.min(age, state.l1AgeMs());
                    }
                    if (state.l2Present()) {
                        l2 = true;
                        ttl = Math.max(ttl, state.l2TtlMs());
                    }
                }
                return PackageInspector.envelopeJson(
                    new FilteredMetadataCache.EnvelopeState(l1, age, l2, ttl)
                );
            })
            .exceptionally(err -> new JsonObject()
                .put("error", String.valueOf(PackageInspector.cause(err).getMessage())));
    }

    /**
     * Negative-cache entries of a package by scope (this node's L1 merged
     * with L2).
     *
     * @param pkg Package
     * @return Future of scope to entries
     */
    private CompletableFuture<Map<String, JsonArray>> negatives(final PackageName pkg) {
        return CompletableFuture.supplyAsync(this.negative::l1Keys, HandlerExecutor.get())
            .thenCompose(l1 -> this.negative.l2Keys(NEG_SCAN_LIMIT).thenApply(l2 -> {
                final Set<String> inL2 = new HashSet<>(l2.flats());
                final Set<String> flats = new LinkedHashSet<>(l1);
                flats.addAll(l2.flats());
                final Map<String, JsonArray> out = new HashMap<>();
                for (final String flat : flats) {
                    final NegativeCacheKey key = NegativeCacheKey.parse(flat);
                    if (key != null && pkg.matches(key)) {
                        out.computeIfAbsent(key.scope(), scope -> new JsonArray()).add(
                            new JsonObject()
                                .put("key", new JsonObject()
                                    .put("scope", key.scope())
                                    .put("repoType", key.repoType())
                                    .put("artifactName", key.artifactName())
                                    .put("artifactVersion", key.artifactVersion()))
                                .put("l1", l1.contains(flat))
                                .put("l2", inL2.contains(flat))
                        );
                    }
                }
                return out;
            }));
    }

    /**
     * Envelope JSON.
     *
     * @param state State
     * @return JSON
     */
    private static JsonObject envelopeJson(final FilteredMetadataCache.EnvelopeState state) {
        return new JsonObject()
            .put("l1", new JsonObject()
                .put("present", state.l1Present())
                .put("ageMs", state.l1Present() ? state.l1AgeMs() : null))
            .put("l2", new JsonObject()
                .put("present", state.l2Present())
                .put("ttlRemainingMs", state.l2Present() && state.l2TtlMs() >= 0
                    ? state.l2TtlMs() : null));
    }

    /**
     * Unwrap completion wrappers.
     *
     * @param err Error
     * @return Root-most meaningful cause
     */
    static Throwable cause(final Throwable err) {
        Throwable cur = err;
        while ((cur instanceof java.util.concurrent.CompletionException
            || cur instanceof java.util.concurrent.ExecutionException)
            && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }
}
