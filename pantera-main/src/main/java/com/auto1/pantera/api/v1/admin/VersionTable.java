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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-version join of cooldown state and served visibility, with the
 * mismatch rules of the package inspector.
 *
 * <p>Rules — a version is a {@code mismatch} when:</p>
 * <ul>
 *   <li>it is {@code blocked} yet listed by the repository holding the
 *       block, or by a group reaching that repository while no member of
 *       the group lists it (the group serves a stale listing);</li>
 *   <li>it is {@code released}/{@code expired} yet missing from the listing
 *       of the repository it was blocked in (the historical "unblocked but
 *       still invisible" bug);</li>
 *   <li>whatever its state, a group hides it while one of the group's
 *       members lists it.</li>
 * </ul>
 *
 * @since 2.2.9
 */
final class VersionTable {

    /**
     * Topology.
     */
    private final RepoTopology topology;

    /**
     * Versions each repository with a readable listing shows.
     */
    private final Map<String, Set<String>> visible;

    /**
     * Cooldown records by version.
     */
    private final Map<String, List<CooldownPackageRow>> rows;

    /**
     * Ctor.
     *
     * @param topology Topology
     * @param visible Versions per repository (only repositories whose
     *  listing was read)
     * @param rows Cooldown records
     */
    VersionTable(
        final RepoTopology topology, final Map<String, Set<String>> visible,
        final List<CooldownPackageRow> rows
    ) {
        this.topology = topology;
        this.visible = visible;
        this.rows = new LinkedHashMap<>();
        for (final CooldownPackageRow row : rows) {
            this.rows.computeIfAbsent(row.version(), ver -> new ArrayList<>()).add(row);
        }
    }

    /**
     * The table.
     *
     * @return One entry per version, newest first
     */
    JsonArray json() {
        final Set<String> versions = new TreeSet<>(new VersionOrder().reversed());
        this.visible.values().forEach(versions::addAll);
        versions.addAll(this.rows.keySet());
        final JsonArray out = new JsonArray();
        for (final String version : versions) {
            out.add(this.entry(version));
        }
        return out;
    }

    /**
     * One version.
     *
     * @param version Version
     * @return Entry
     */
    private JsonObject entry(final String version) {
        final Optional<CooldownPackageRow> row = this.relevant(version);
        final List<String> shown = new ArrayList<>();
        final List<String> hidden = new ArrayList<>();
        for (final Map.Entry<String, Set<String>> repo : this.visible.entrySet()) {
            if (repo.getValue().contains(version)) {
                shown.add(repo.getKey());
            } else {
                hidden.add(repo.getKey());
            }
        }
        final JsonObject cooldown = new JsonObject()
            .put("state", row.map(CooldownPackageRow::state).orElse("none"));
        row.ifPresent(rec -> cooldown
            .put("blockedUntil", rec.blockedUntil() == null ? null : rec.blockedUntil().toString())
            .put("reason", rec.reason())
            .put("repo", rec.repoName())
            .put("source", rec.live() ? "live" : "history"));
        final Optional<String> why = this.mismatch(row, shown, hidden);
        final JsonObject entry = new JsonObject()
            .put("version", version)
            .put("cooldown", cooldown)
            .put("visibleIn", new JsonArray(shown))
            .put("hiddenIn", new JsonArray(hidden))
            .put("mismatch", why.isPresent());
        why.ifPresent(reason -> entry.put("mismatchReason", reason));
        return entry;
    }

    /**
     * The record that decides a version's state: a live block first, then
     * a live release, then a live expiry, then the newest archived record.
     *
     * @param version Version
     * @return Record, empty when the version never had a cooldown
     */
    private Optional<CooldownPackageRow> relevant(final String version) {
        final List<CooldownPackageRow> recs = this.rows.getOrDefault(version, List.of());
        for (final String state : List.of("blocked", "released", "expired")) {
            for (final CooldownPackageRow rec : recs) {
                if (rec.live() && state.equals(rec.state())) {
                    return Optional.of(rec);
                }
            }
        }
        return recs.stream().filter(rec -> !rec.live()).findFirst();
    }

    /**
     * Mismatch rule evaluation.
     *
     * @param row Deciding record
     * @param shown Repositories listing the version
     * @param hidden Repositories not listing it
     * @return Reason, empty when consistent
     */
    private Optional<String> mismatch(
        final Optional<CooldownPackageRow> row, final List<String> shown, final List<String> hidden
    ) {
        final Optional<String> res;
        if (row.isPresent() && "blocked".equals(row.get().state())) {
            res = this.blockedButShown(row.get().repoName(), shown);
        } else {
            final Optional<String> released = row
                .filter(rec -> hidden.contains(rec.repoName()))
                .map(rec -> rec.state() + " but hidden in " + rec.repoName());
            res = released.isPresent() ? released : this.groupHides(shown, hidden);
        }
        return res;
    }

    /**
     * Blocked-yet-listed check.
     *
     * @param blockRepo Repository holding the block
     * @param shown Repositories listing the version
     * @return Reason, empty when consistent
     */
    private Optional<String> blockedButShown(final String blockRepo, final List<String> shown) {
        if (shown.contains(blockRepo)) {
            return Optional.of("blocked but listed by " + blockRepo);
        }
        for (final String group : this.topology.groupsContaining(blockRepo)) {
            if (shown.contains(group) && this.topology.reachable(group).stream()
                .noneMatch(member -> shown.contains(member.name()))
                && this.topology.reachable(group).stream()
                    .allMatch(member -> this.visible.containsKey(member.name())
                        || member.group())) {
                return Optional.of("blocked but listed by group " + group
                    + " while none of its members lists it");
            }
        }
        return Optional.empty();
    }

    /**
     * Group-hides-a-member's-version check.
     *
     * @param shown Repositories listing the version
     * @param hidden Repositories not listing it
     * @return Reason, empty when consistent
     */
    private Optional<String> groupHides(final List<String> shown, final List<String> hidden) {
        for (final String repo : hidden) {
            final boolean group = this.topology.repo(repo).map(RepoTopology.RepoInfo::group)
                .orElse(false);
            if (!group) {
                continue;
            }
            for (final RepoTopology.RepoInfo member : this.topology.reachable(repo)) {
                if (shown.contains(member.name())) {
                    return Optional.of("hidden in group " + repo + " though member "
                        + member.name() + " lists it");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Version order: numeric runs compared numerically, the rest
     * lexicographically; a release sorts after its pre-releases.
     *
     * @since 2.2.9
     */
    static final class VersionOrder implements Comparator<String> {
        @Override
        public int compare(final String left, final String right) {
            final String[] lparts = left.split("[.\\-+_]");
            final String[] rparts = right.split("[.\\-+_]");
            final int len = Math.max(lparts.length, rparts.length);
            for (int idx = 0; idx < len; idx = idx + 1) {
                final String lpart = idx < lparts.length ? lparts[idx] : null;
                final String rpart = idx < rparts.length ? rparts[idx] : null;
                final int cmp = VersionOrder.part(lpart, rpart);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return left.compareTo(right);
        }

        /**
         * Compare one segment.
         *
         * @param left Left segment, null when absent
         * @param right Right segment, null when absent
         * @return Comparison
         */
        private static int part(final String left, final String right) {
            final int res;
            if (left == null || right == null) {
                final String present = left == null ? right : left;
                final int sign = left == null ? -1 : 1;
                res = present.chars().allMatch(Character::isDigit) ? sign : -sign;
            } else if (left.chars().allMatch(Character::isDigit) && !left.isEmpty()
                && right.chars().allMatch(Character::isDigit) && !right.isEmpty()) {
                res = new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
            } else {
                res = left.compareTo(right);
            }
            return res;
        }
    }
}
