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

import com.auto1.pantera.settings.repo.RepoSummary;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.json.JsonValue;

/**
 * Filters, sorts and projects repository summaries for
 * {@code GET /api/v1/repositories}. Pure: no I/O, so it is unit-tested
 * without Vert.x.
 * @since 2.2.10
 */
final class RepoListing {

    /**
     * Accepted {@code mode} values.
     */
    private static final Set<String> MODES = Set.of("hosted", "proxy", "group");

    /**
     * Accepted {@code sort} values.
     */
    private static final Set<String> SORTS = Set.of("name", "type", "updated_at");

    /**
     * Accepted {@code order} values.
     */
    private static final Set<String> ORDERS = Set.of("asc", "desc");

    /**
     * Summary rows.
     */
    private final Collection<RepoSummary> rows;

    /**
     * Whether the caller may read the named repository.
     */
    private final Predicate<String> readable;

    /**
     * Ctor.
     * @param rows Summary rows
     * @param readable Per-repository read grant of the caller
     */
    RepoListing(final Collection<RepoSummary> rows, final Predicate<String> readable) {
        this.rows = rows;
        this.readable = readable;
    }

    /**
     * Filtered, sorted and projected items.
     * @param params Validated query parameters
     * @return Items
     */
    List<JsonObject> items(final Params params) {
        final List<RepoSummary> kept = new ArrayList<>();
        for (final RepoSummary row : this.rows) {
            if (params.matches(row) && this.readable.test(row.name())) {
                kept.add(row);
            }
        }
        kept.sort(params.comparator());
        final List<JsonObject> res = new ArrayList<>(kept.size());
        for (final RepoSummary row : kept) {
            res.add(RepoListing.project(row));
        }
        return res;
    }

    private static JsonObject project(final RepoSummary row) {
        final String type = RepoListing.type(row);
        final JsonObject res = new JsonObject()
            .put("name", row.name())
            .put("type", type)
            .put("mode", RepoListing.mode(type))
            .put("storage", RepoListing.storage(row))
            .put("anonymous_read", row.repo().getBoolean("anonymous_read", false))
            .put("anonymous_write", row.repo().getBoolean("anonymous_write", false));
        final JsonValue immutable = row.repo().get("immutable");
        if (immutable != null && immutable.getValueType() == JsonValue.ValueType.TRUE) {
            res.put("immutable", true);
        } else if (immutable != null && immutable.getValueType() == JsonValue.ValueType.FALSE) {
            res.put("immutable", false);
        } else {
            res.putNull("immutable");
        }
        if (row.updatedAt() == null) {
            res.putNull("updated_at");
        } else {
            res.put("updated_at", row.updatedAt().toString());
        }
        final String editor = row.updatedBy() == null ? row.createdBy() : row.updatedBy();
        if (editor == null) {
            res.putNull("updated_by");
        } else {
            res.put("updated_by", editor);
        }
        return res;
    }

    private static String type(final RepoSummary row) {
        final String res;
        if (row.type() == null || row.type().isBlank()) {
            res = row.repo().getString("type", "unknown");
        } else {
            res = row.type();
        }
        return res;
    }

    private static String mode(final String type) {
        final String lower = type.toLowerCase(Locale.ROOT);
        final String res;
        if (lower.endsWith("-proxy")) {
            res = "proxy";
        } else if (lower.endsWith("-group")) {
            res = "group";
        } else {
            res = "hosted";
        }
        return res;
    }

    private static String storage(final RepoSummary row) {
        final JsonValue val = row.repo().get("storage");
        String res = null;
        if (val != null && val.getValueType() == JsonValue.ValueType.STRING) {
            res = row.repo().getString("storage");
        } else if (val != null && val.getValueType() == JsonValue.ValueType.OBJECT) {
            res = val.asJsonObject().getString("type", null);
        }
        return res;
    }

    /**
     * Query parameters of the list endpoint.
     * @param query Name substring, nullable
     * @param type Type substring, nullable
     * @param mode hosted|proxy|group, nullable
     * @param sort name|type|updated_at, nullable (name)
     * @param order asc|desc, nullable (asc)
     */
    record Params(String query, String type, String mode, String sort, String order) {

        /**
         * Validate the enumerated parameters.
         * @return Error message when a value is not accepted
         */
        Optional<String> validate() {
            Optional<String> res = Optional.empty();
            if (this.mode != null && !MODES.contains(this.mode)) {
                res = Optional.of("mode must be one of hosted, proxy, group");
            } else if (this.sort != null && !SORTS.contains(this.sort)) {
                res = Optional.of("sort must be one of name, type, updated_at");
            } else if (this.order != null && !ORDERS.contains(this.order)) {
                res = Optional.of("order must be asc or desc");
            }
            return res;
        }

        /**
         * Whether a row passes the query, type and mode filters.
         * @param row Row
         * @return True when kept
         */
        boolean matches(final RepoSummary row) {
            final String rtype = RepoListing.type(row);
            final boolean byname = this.query == null
                || row.name().toLowerCase(Locale.ROOT)
                    .contains(this.query.toLowerCase(Locale.ROOT));
            final boolean bytype = this.type == null
                || rtype.toLowerCase(Locale.ROOT).contains(this.type.toLowerCase(Locale.ROOT));
            final boolean bymode = this.mode == null || this.mode.equals(RepoListing.mode(rtype));
            return byname && bytype && bymode;
        }

        /**
         * Sort order for the kept rows.
         * @return Comparator
         */
        Comparator<RepoSummary> comparator() {
            final boolean desc = "desc".equals(this.order);
            // Case-insensitive like the database's ORDER BY name, with the
            // exact name as a deterministic tie-breaker.
            final Comparator<RepoSummary> byname = Comparator
                .comparing(RepoSummary::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RepoSummary::name);
            final Comparator<RepoSummary> res;
            if ("type".equals(this.sort)) {
                final Comparator<RepoSummary> bytype = Comparator.comparing(RepoListing::type);
                res = (desc ? bytype.reversed() : bytype).thenComparing(byname);
            } else if ("updated_at".equals(this.sort)) {
                final Comparator<java.time.Instant> natural = desc
                    ? Comparator.reverseOrder() : Comparator.naturalOrder();
                res = Comparator.comparing(
                    RepoSummary::updatedAt, Comparator.nullsLast(natural)
                ).thenComparing(byname);
            } else {
                res = desc ? byname.reversed() : byname;
            }
            return res;
        }
    }
}
