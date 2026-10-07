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
package com.auto1.pantera.settings.repo;

import java.time.Instant;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import javax.json.JsonValue;

/**
 * Builds {@link RepoSummary} rows from stored repository configs, with or
 * without the {@code repo} wrapper.
 * @since 2.2.10
 */
public final class RepoSummaries {

    /**
     * Build one summary.
     * @param name Repository name
     * @param column Type column value, may be null, blank or {@code unknown}
     * @param config Stored config, with or without the {@code repo} wrapper, may be null
     * @param updated Updated-at, may be null
     * @param updater Updated-by, may be null
     * @param creator Created-by, may be null
     * @return Summary
     * @checkstyle ParameterNumberCheck (3 lines)
     */
    public RepoSummary from(final String name, final String column,
        final JsonStructure config, final Instant updated,
        final String updater, final String creator) {
        final JsonObject repo = this.repoSection(config);
        // RepositoryDao.save stores "unknown" (never blank) when the config
        // carried no repo.type at save time, so both mean "ask the config".
        final String type;
        if (column != null && !column.isBlank() && !"unknown".equals(column)) {
            type = column;
        } else {
            type = repo.getString("type", "unknown");
        }
        return new RepoSummary(name, type, repo, updated, updater, creator);
    }

    private JsonObject repoSection(final JsonStructure config) {
        JsonObject res = Json.createObjectBuilder().build();
        if (config instanceof JsonObject obj) {
            final JsonValue inner = obj.get("repo");
            if (inner != null && inner.getValueType() == JsonValue.ValueType.OBJECT) {
                res = inner.asJsonObject();
            } else {
                res = obj;
            }
        }
        return res;
    }
}
