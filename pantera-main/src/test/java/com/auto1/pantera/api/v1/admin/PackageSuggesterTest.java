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

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link PackageSuggester} over a fixture lookup that applies the SQL
 * {@code ILIKE} patterns the way PostgreSQL would.
 *
 * @since 2.2.9
 */
final class PackageSuggesterTest {

    /**
     * Fixture rows across formats and both sources.
     */
    private static final List<SuggestRow> ROWS = List.of(
        PackageSuggesterTest.index("npm-proxy", "npm_proxy", "okhttp5-shim", null),
        PackageSuggesterTest.index("npm-proxy", "npm_proxy", "@types/node", null),
        PackageSuggesterTest.index("npm-proxy", "npm_proxy", "@types/nodemailer", null),
        PackageSuggesterTest.index("npm-proxy", "npm_proxy", "openai", null),
        PackageSuggesterTest.index("npm-proxy", "npm_proxy", "@azure/openai", null),
        PackageSuggesterTest.cooldown("npm-group", "npm_group", "openai"),
        PackageSuggesterTest.index("npm-proxy", "npm_proxy", "lodash", null),
        PackageSuggesterTest.index("pypi-proxy", "pypi_proxy", "OpenAI_Client", null),
        PackageSuggesterTest.index("pypi-proxy", "pypi_proxy", "http5lib", null),
        PackageSuggesterTest.index(
            "maven-proxy", "maven_proxy", "com.squareup.okhttp5.okhttp",
            "com/squareup/okhttp5/okhttp/5.0.0"
        ),
        PackageSuggesterTest.index(
            "maven-proxy", "maven_proxy", "com.fasterxml.jackson.core.jackson-databind",
            "com/fasterxml/jackson/core/jackson-databind/2.17.0"
        ),
        PackageSuggesterTest.cooldown("maven-proxy", "maven_proxy", "com.theokanning.openai-gpt3-java.api"),
        PackageSuggesterTest.cooldown("maven-proxy", "maven_proxy", "io.github.databind-utils.core"),
        PackageSuggesterTest.index(
            "gradle-proxy", "gradle_proxy", "org.http5.client", "org/http5/client/1.0"
        ),
        PackageSuggesterTest.index("docker-proxy", "docker_proxy", "library/ubuntu", null),
        PackageSuggesterTest.index("docker-proxy", "docker_proxy", "openai/whisper", null),
        PackageSuggesterTest.index("php-proxy", "php_proxy", "vendor/http5", null),
        PackageSuggesterTest.index("go-proxy", "go_proxy", "github.com/foo/http5", null),
        PackageSuggesterTest.index("helm-proxy", "helm_proxy", "openai-chart", null),
        PackageSuggesterTest.index("nuget-proxy", "nuget_proxy", "Http5.Net", null),
        PackageSuggesterTest.index("gem-proxy", "gem_proxy", "types-nodeset", null)
    );

    /**
     * Lookup under test.
     */
    private final FixtureLookup lookup = new FixtureLookup(
        PackageSuggesterTest.ROWS,
        Map.of(
            "com.theokanning.openai-gpt3-java.api",
            "com/theokanning/openai-gpt3-java/api/0.18.2"
        )
    );

    @ParameterizedTest
    @ValueSource(strings = {"http5", "openai", "databind", "types/no", "HTTP5"})
    void coversEveryRowAPlainSubstringSearchReturns(final String text) {
        final List<PackageSuggester.Suggestion> found =
            new PackageSuggester(this.lookup).find(null, text, 50);
        final List<String> missing = new ArrayList<>();
        for (final SuggestRow row : PackageSuggesterTest.ROWS) {
            if (row.name().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
                final boolean covered = found.stream().anyMatch(
                    sug -> sug.names().contains(row.name()) && sug.repos().contains(row.repoName())
                );
                if (!covered) {
                    missing.add(row.repoName() + "/" + row.name());
                }
            }
        }
        MatcherAssert.assertThat(missing, new IsEqual<>(List.of()));
    }

    @Test
    void mergesSourcesAndRepositoriesOfOnePackage() {
        final JsonObject openai = new PackageSuggester(this.lookup)
            .suggest("npm", "openai", 20).getJsonObject(0);
        MatcherAssert.assertThat(
            openai.encode(),
            new IsEqual<>(
                new JsonObject()
                    .put("package", "openai")
                    .put("display", "openai")
                    .put("repoType", "npm")
                    .put("sources", new JsonArray(List.of("index", "cooldown")))
                    .put("repos", new JsonArray(List.of("npm_group", "npm_proxy")))
                    .encode()
            )
        );
    }

    @Test
    void ranksExactBeforePrefixBeforeSubstring() {
        MatcherAssert.assertThat(
            PackageSuggesterTest.packages(
                new PackageSuggester(this.lookup).suggest("npm", "openai", 20)
            ),
            new IsEqual<>(List.of("openai", "@azure/openai"))
        );
    }

    @Test
    void filtersByFamilyWithMavenCoveringGradle() {
        MatcherAssert.assertThat(
            "npm type filters to npm",
            PackageSuggesterTest.packages(
                new PackageSuggester(this.lookup).suggest("npm-proxy", "http5", 20)
            ),
            new IsEqual<>(List.of("okhttp5-shim"))
        );
        MatcherAssert.assertThat(
            "maven includes gradle-layout repositories",
            PackageSuggesterTest.packages(
                new PackageSuggester(this.lookup).suggest("maven", "http5", 20)
            ),
            new IsEqual<>(List.of("org.http5:client", "com.squareup.okhttp5:okhttp"))
        );
    }

    @Test
    void searchesEveryFormatWithoutAType() {
        final JsonArray all = new PackageSuggester(this.lookup).suggest(null, "http5", 50);
        MatcherAssert.assertThat(
            all.stream().map(obj -> ((JsonObject) obj).getString("repoType"))
                .distinct().sorted().collect(Collectors.toList()),
            new IsEqual<>(List.of("go", "gradle", "maven", "npm", "nuget", "php", "pypi"))
        );
    }

    @Test
    void derivesTheInspectorFormPerFormat() {
        final PackageSuggester sug = new PackageSuggester(this.lookup);
        MatcherAssert.assertThat(
            "maven groupId:artifactId from the index path",
            PackageSuggesterTest.packages(sug.suggest("maven", "jackson databind", 20)),
            new IsEqual<>(List.of("com.fasterxml.jackson.core:jackson-databind"))
        );
        MatcherAssert.assertThat(
            "cooldown-only maven row resolved through the index",
            PackageSuggesterTest.packages(sug.suggest("maven", "gpt3", 20)),
            new IsEqual<>(List.of("com.theokanning.openai-gpt3-java:api"))
        );
        MatcherAssert.assertThat(
            "pypi PEP 503 normalised",
            PackageSuggesterTest.packages(sug.suggest("pypi", "openai", 20)),
            new IsEqual<>(List.of("openai-client"))
        );
        MatcherAssert.assertThat(
            "docker image name as stored",
            PackageSuggesterTest.packages(sug.suggest("docker", "ubuntu", 20)),
            new IsEqual<>(List.of("library/ubuntu"))
        );
    }

    @Test
    void notesAnUnresolvedMavenName() {
        final JsonObject item = new PackageSuggester(this.lookup)
            .suggest("maven", "databind-utils", 20).getJsonObject(0);
        MatcherAssert.assertThat(
            "dotted package with a noted display",
            item.getString("package") + " | " + item.getString("display"),
            new IsEqual<>(
                "io.github.databind-utils.core | io.github.databind-utils.core"
                    + " (groupId:artifactId not in the index)"
            )
        );
    }

    @Test
    void matchesTheColonFormOfAMavenCoordinate() {
        MatcherAssert.assertThat(
            PackageSuggesterTest.packages(
                new PackageSuggester(this.lookup).suggest("maven", "core:jackson", 20)
            ),
            new IsEqual<>(List.of("com.fasterxml.jackson.core:jackson-databind"))
        );
    }

    @Test
    void respectsTheLimit() {
        MatcherAssert.assertThat(
            new PackageSuggester(this.lookup).suggest(null, "http5", 2).size(),
            new IsEqual<>(2)
        );
    }

    @Test
    void blankQuerySuggestsNothing() {
        MatcherAssert.assertThat(
            new PackageSuggester(this.lookup).suggest("npm", " ", 20).size(),
            new IsEqual<>(0)
        );
    }

    @Test
    void didYouMeanRetriesWithSeparatorsAsWordBreaks() {
        MatcherAssert.assertThat(
            PackageSuggesterTest.packages(
                new PackageSuggester(this.lookup)
                    .didYouMean("maven", "com.fasterxml.jackson:jackson-databind", 5)
            ),
            new IsEqual<>(List.of("com.fasterxml.jackson.core:jackson-databind"))
        );
    }

    @Test
    void didYouMeanLeavesOutTheTypedName() {
        MatcherAssert.assertThat(
            PackageSuggesterTest.packages(
                new PackageSuggester(this.lookup).didYouMean("npm", "OpenAI", 5)
            ),
            new IsEqual<>(List.of("@azure/openai"))
        );
    }

    /**
     * Package names of a suggestion list.
     *
     * @param arr Suggestions
     * @return Names in order
     */
    private static List<String> packages(final JsonArray arr) {
        return arr.stream().map(obj -> ((JsonObject) obj).getString("package"))
            .collect(Collectors.toList());
    }

    /**
     * Index row.
     *
     * @param type Repo type
     * @param repo Repo name
     * @param name Stored name
     * @param path Path prefix
     * @return Row
     */
    private static SuggestRow index(
        final String type, final String repo, final String name, final String path
    ) {
        return new SuggestRow(SuggestRow.INDEX, type, repo, name, path);
    }

    /**
     * Cooldown row.
     *
     * @param type Repo type
     * @param repo Repo name
     * @param name Stored name
     * @return Row
     */
    private static SuggestRow cooldown(final String type, final String repo, final String name) {
        return new SuggestRow(SuggestRow.COOLDOWN, type, repo, name, null);
    }

    /**
     * Lookup that filters its rows with the query's ILIKE patterns.
     *
     * @since 2.2.9
     */
    private static final class FixtureLookup implements SuggestLookup {

        /**
         * Rows.
         */
        private final List<SuggestRow> rows;

        /**
         * Maven dotted name to path.
         */
        private final Map<String, String> paths;

        /**
         * Ctor.
         *
         * @param rows Rows
         * @param paths Paths
         */
        FixtureLookup(final List<SuggestRow> rows, final Map<String, String> paths) {
            this.rows = rows;
            this.paths = paths;
        }

        @Override
        public List<SuggestRow> rows(
            final Collection<String> families, final SuggestQuery query, final int limit
        ) {
            return this.rows.stream()
                .filter(row -> families.isEmpty()
                    || families.contains(new PackageName(row.repoType(), "").family()))
                .filter(row -> query.patterns().stream().allMatch(
                    alts -> alts.stream().anyMatch(like -> FixtureLookup.ilike(row.name(), like))
                ))
                .limit(limit)
                .collect(Collectors.toList());
        }

        @Override
        public Map<String, String> mavenPaths(final Collection<String> names) {
            final Map<String, String> out = new HashMap<>();
            for (final String name : names) {
                if (this.paths.containsKey(name)) {
                    out.put(name, this.paths.get(name));
                }
            }
            return out;
        }

        /**
         * PostgreSQL {@code value ILIKE pattern ESCAPE '\'}.
         *
         * @param value Value
         * @param like Pattern
         * @return Match
         */
        private static boolean ilike(final String value, final String like) {
            final StringBuilder regex = new StringBuilder();
            for (int idx = 0; idx < like.length(); idx += 1) {
                final char chr = like.charAt(idx);
                if (chr == '\\' && idx + 1 < like.length()) {
                    idx += 1;
                    regex.append(Pattern.quote(String.valueOf(like.charAt(idx))));
                } else if (chr == '%') {
                    regex.append(".*");
                } else if (chr == '_') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(chr)));
                }
            }
            return Pattern.compile(
                regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            ).matcher(value).matches();
        }
    }
}
