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
package com.auto1.pantera.composer.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Filters cooldown-blocked versions out of a Composer root
 * aggregation response — {@code /packages.json} or {@code /repo.json}.
 *
 * <p>Two root shapes exist in the wild:</p>
 *
 * <dl>
 *   <dt><b>Lazy-providers scheme</b></dt>
 *   <dd>The root JSON declares a {@code providers-url} or
 *       {@code metadata-url} template (e.g.
 *       {@code "/p2/%package%.json"}) and <em>does not</em> enumerate
 *       package version maps inline. Clients follow the template to
 *       fetch per-package metadata. In this scheme there is nothing
 *       to filter at the root — the filtering happens per-package via
 *       {@link ComposerPackageMetadataHandler}. This filter detects
 *       that shape and returns the metadata unchanged.</dd>
 *
 *   <dt><b>Inline-packages scheme</b></dt>
 *   <dd>The root JSON contains a {@code "packages"} object whose
 *       first-level keys are {@code "vendor/package"} entries and
 *       whose values are version maps (Packagist satis snapshots and
 *       small private repos use this shape). This filter walks those
 *       entries, removes blocked version keys, and drops any package
 *       entry that ends up empty — yielding the same "package is
 *       absent" semantics Composer already applies when a package
 *       genuinely has no versions.</dd>
 * </dl>
 *
 * <p>Top-level metadata fields — {@code providers-url},
 * {@code metadata-url}, {@code notify-batch}, {@code search},
 * {@code available-packages}, {@code mirrors}, etc. — are preserved
 * unchanged. Only the {@code packages} object is mutated.</p>
 *
 * <p>This is a pure value transform; it does not perform any I/O and
 * has no dependencies on the cooldown service. The orchestrating
 * handler ({@code ComposerRootPackagesHandler}) gathers the
 * per-package blocked set(s) by consulting the cooldown service and
 * then invokes {@link #filter(JsonNode, BiPredicate)}.</p>
 *
 * @since 2.2.0
 */
public final class ComposerRootPackagesFilter {

    /**
     * Filter the root aggregation JSON, removing versions for which
     * the supplied predicate returns true.
     *
     * <p>The predicate receives {@code (packageName, version)} pairs
     * and must return {@code true} iff the entry should be removed.
     * A package whose version map becomes empty after removal is
     * itself dropped from {@code packages}.</p>
     *
     * <p>If the root does not contain an inline {@code packages}
     * object (lazy-providers scheme) the metadata is returned
     * unchanged — there is nothing to filter at the root level.</p>
     *
     * @param metadata Parsed root JSON (mutated in place, also returned)
     * @param blockedPredicate Returns true for (pkg,version) pairs to drop
     * @return The (possibly mutated) root metadata node
     */
    public JsonNode filter(
        final JsonNode metadata,
        final BiPredicate<String, String> blockedPredicate
    ) {
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        final ObjectNode root = (ObjectNode) metadata;
        final JsonNode packages = root.get("packages");
        if (!inlinePackagesShape(packages)) {
            // Lazy-providers / metadata-url scheme or packages-is-array
            // (Satis) — nothing to filter at the root level.
            return metadata;
        }
        final ObjectNode packagesObj = (ObjectNode) packages;
        final List<String> pkgsToDrop = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> pkgIter = packagesObj.fields();
        while (pkgIter.hasNext()) {
            final Map.Entry<String, JsonNode> pkgEntry = pkgIter.next();
            final String pkgName = pkgEntry.getKey();
            final JsonNode pkgNode = pkgEntry.getValue();
            if (pkgNode instanceof ObjectNode versionsObj) {
                removeBlockedVersionsObject(
                    pkgName, versionsObj, blockedPredicate
                );
                if (versionsObj.size() == 0) {
                    pkgsToDrop.add(pkgName);
                }
            } else if (pkgNode != null && pkgNode.isArray()) {
                // Packagist v1 array-of-version-objects shape — each
                // element has a "version" field. Collect indices to
                // drop, then replace the array.
                final com.fasterxml.jackson.databind.node.ArrayNode arr =
                    (com.fasterxml.jackson.databind.node.ArrayNode) pkgNode;
                final com.fasterxml.jackson.databind.node.ArrayNode kept =
                    arr.arrayNode();
                for (final JsonNode entry : arr) {
                    if (entry == null || !entry.has("version")) {
                        kept.add(entry);
                        continue;
                    }
                    final String version = entry.get("version").asText(null);
                    if (version == null) {
                        kept.add(entry);
                        continue;
                    }
                    if (!blockedPredicate.test(pkgName, version)) {
                        kept.add(entry);
                    }
                }
                if (kept.size() == 0) {
                    pkgsToDrop.add(pkgName);
                } else {
                    packagesObj.set(pkgName, kept);
                }
            }
        }
        for (final String pkg : pkgsToDrop) {
            packagesObj.remove(pkg);
        }
        return metadata;
    }

    /**
     * Collect every {@code (packageName, version)} pair that appears
     * in the inline {@code packages} object, so the orchestrator can
     * batch them to the cooldown service.
     *
     * @param metadata Parsed root JSON
     * @return List of (pkg, version) entries; empty for non-inline shapes
     */
    public List<PackageVersion> extractPackageVersions(final JsonNode metadata) {
        final List<PackageVersion> result = new ArrayList<>();
        if (!(metadata instanceof ObjectNode)) {
            return result;
        }
        final JsonNode packages = metadata.get("packages");
        if (!inlinePackagesShape(packages)) {
            return result;
        }
        final ObjectNode packagesObj = (ObjectNode) packages;
        final Iterator<Map.Entry<String, JsonNode>> pkgIter = packagesObj.fields();
        while (pkgIter.hasNext()) {
            final Map.Entry<String, JsonNode> pkgEntry = pkgIter.next();
            final String pkgName = pkgEntry.getKey();
            final JsonNode pkgNode = pkgEntry.getValue();
            if (pkgNode instanceof ObjectNode versionsObj) {
                final Iterator<String> verNames = versionsObj.fieldNames();
                while (verNames.hasNext()) {
                    result.add(new PackageVersion(pkgName, verNames.next()));
                }
            } else if (pkgNode != null && pkgNode.isArray()) {
                for (final JsonNode entry : pkgNode) {
                    if (entry != null && entry.has("version")) {
                        final String version = entry.get("version").asText(null);
                        if (version != null) {
                            result.add(new PackageVersion(pkgName, version));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * True iff the packages node exists as an object with at least
     * one entry whose value is itself an object or array (meaning
     * inline version data is present for that package).
     */
    private static boolean inlinePackagesShape(final JsonNode packages) {
        if (packages == null || !packages.isObject() || packages.size() == 0) {
            return false;
        }
        final Iterator<JsonNode> it = packages.elements();
        while (it.hasNext()) {
            final JsonNode node = it.next();
            if (node != null && (node.isObject() || node.isArray())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove blocked version keys from a per-package object-shaped
     * version map in place.
     */
    private static void removeBlockedVersionsObject(
        final String pkgName,
        final ObjectNode versionsObj,
        final BiPredicate<String, String> blockedPredicate
    ) {
        final Set<String> toRemove = new java.util.HashSet<>();
        final Iterator<String> verNames = versionsObj.fieldNames();
        while (verNames.hasNext()) {
            final String ver = verNames.next();
            if (blockedPredicate.test(pkgName, ver)) {
                toRemove.add(ver);
            }
        }
        for (final String ver : toRemove) {
            versionsObj.remove(ver);
        }
    }

    /**
     * Simple (package, version) pair used by the handler when batching
     * blocked-check queries against the cooldown service.
     */
    public static final class PackageVersion {
        private final String pkg;
        private final String version;

        public PackageVersion(final String pkg, final String version) {
            this.pkg = pkg;
            this.version = version;
        }

        public String pkg() {
            return this.pkg;
        }

        public String version() {
            return this.version;
        }
    }
}
