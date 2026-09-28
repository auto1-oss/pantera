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

/**
 * A package name found by the inspector's suggestion search: one
 * artifacts-index row (grouped per repository) or one cooldown record.
 *
 * @param source {@link #INDEX} or {@link #COOLDOWN}
 * @param repoType Repository type as stored ({@code npm-proxy}, ...)
 * @param repoName Repository name
 * @param name Package name as stored
 * @param pathPrefix Index path prefix, may be null
 * @since 2.2.9
 */
public record SuggestRow(
    String source, String repoType, String repoName, String name, String pathPrefix
) {

    /**
     * Source: the artifacts index.
     */
    public static final String INDEX = "index";

    /**
     * Source: live or archived cooldown records.
     */
    public static final String COOLDOWN = "cooldown";
}
