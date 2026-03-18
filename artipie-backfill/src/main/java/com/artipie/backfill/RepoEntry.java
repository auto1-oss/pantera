/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.backfill;

/**
 * Parsed result of one Artipie repo YAML config file.
 *
 * @param repoName Repo name derived from the YAML filename stem (e.g. {@code go.yaml} → {@code go})
 * @param rawType Raw {@code repo.type} string from the YAML (e.g. {@code docker-proxy})
 * @since 1.20.13
 */
record RepoEntry(String repoName, String rawType) {
}
