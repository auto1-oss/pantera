/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.group;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

/**
 * Extensive tests for {@link ArtifactNameParser}.
 * Validates URL-to-name extraction for all supported adapter types.
 * The hit rate (successful parse) must be >= 95% across realistic URL patterns.
 *
 * @since 1.21.0
 */
@SuppressWarnings("PMD.TooManyMethods")
final class ArtifactNameParserTest {

    // ---- Maven: artifact downloads ----

    @ParameterizedTest
    @CsvSource({
        // Standard artifact JAR
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar, com.google.guava.guava",
        // Artifact POM
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom, com.google.guava.guava",
        // Sources JAR
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-sources.jar, com.google.guava.guava",
        // Javadoc JAR
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-javadoc.jar, com.google.guava.guava",
        // WAR file
        "/com/example/webapp/1.0/webapp-1.0.war, com.example.webapp",
        // AAR file (Android)
        "/com/android/support/appcompat-v7/28.0.0/appcompat-v7-28.0.0.aar, com.android.support.appcompat-v7",
        // Gradle module metadata
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.module, com.google.guava.guava",
        // Single-segment groupId
        "/junit/junit/4.13.2/junit-4.13.2.jar, junit.junit",
        // Deep groupId
        "/org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.jar, org.apache.maven.plugins.maven-compiler-plugin",
        // SNAPSHOT version
        "/org/example/mylib/1.0-SNAPSHOT/mylib-1.0-20230101.120000-1.jar, org.example.mylib",
        // Without leading slash
        "com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar, com.google.guava.guava",
    })
    void mavenArtifactFiles(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Maven artifact: " + url,
            ArtifactNameParser.parse("maven-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    // ---- Maven: checksums and signatures ----

    @ParameterizedTest
    @CsvSource({
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha1, com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha256, com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.md5, com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom.asc, com.google.guava.guava",
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha512, com.google.guava.guava",
    })
    void mavenChecksums(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Maven checksum: " + url,
            ArtifactNameParser.parse("maven-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    // ---- Maven: metadata requests ----

    @ParameterizedTest
    @CsvSource({
        // Metadata at artifact level (no version directory)
        "/com/google/guava/guava/maven-metadata.xml, com.google.guava.guava",
        // Metadata checksum
        "/com/google/guava/guava/maven-metadata.xml.sha1, com.google.guava.guava",
        // Metadata at version level
        "/com/google/guava/guava/31.1.3-jre/maven-metadata.xml, com.google.guava.guava",
        // Plugin metadata
        "/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml, org.apache.maven.plugins.maven-compiler-plugin",
    })
    void mavenMetadata(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Maven metadata: " + url,
            ArtifactNameParser.parse("maven-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    // ---- Maven: also works with maven-proxy repo type ----

    @Test
    void mavenProxyRepoType() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse(
                "maven-proxy",
                "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar"
            ),
            new IsEqual<>(Optional.of("com.google.guava.guava"))
        );
    }

    @Test
    void mavenLocalRepoType() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse(
                "maven",
                "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar"
            ),
            new IsEqual<>(Optional.of("com.google.guava.guava"))
        );
    }

    // ---- Maven: edge cases ----

    @Test
    void mavenRootPath() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("maven-group", "/"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void mavenEmptyPath() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("maven-group", ""),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- npm: tarball downloads ----

    @ParameterizedTest
    @CsvSource({
        "/lodash/-/lodash-4.17.21.tgz, lodash",
        "/@babel/core/-/@babel/core-7.23.0.tgz, @babel/core",
        "/@types/node/-/@types/node-20.10.0.tgz, @types/node",
        "/@angular/core/-/@angular/core-17.0.0.tgz, @angular/core",
        "/express/-/express-4.18.2.tgz, express",
        // Without leading slash
        "lodash/-/lodash-4.17.21.tgz, lodash",
    })
    void npmTarballs(final String url, final String expected) {
        MatcherAssert.assertThat(
            "npm tarball: " + url,
            ArtifactNameParser.parse("npm-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    // ---- npm: metadata requests ----

    @ParameterizedTest
    @CsvSource({
        "/lodash, lodash",
        "/@babel/core, @babel/core",
        "/@types/node, @types/node",
        "/express, express",
    })
    void npmMetadata(final String url, final String expected) {
        MatcherAssert.assertThat(
            "npm metadata: " + url,
            ArtifactNameParser.parse("npm-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    @Test
    void npmRootPath() {
        // Root path "/" strips to empty string, which is useless for lookup
        final Optional<String> result = ArtifactNameParser.parse("npm-group", "/");
        // Either empty or an empty string — both are acceptable
        MatcherAssert.assertThat(
            "Root path should not produce a useful name",
            result.filter(s -> !s.isEmpty()),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- Docker: manifest and blob requests ----

    @ParameterizedTest
    @CsvSource({
        "/v2/library/nginx/manifests/latest, library/nginx",
        "/v2/library/nginx/manifests/sha256:abc123, library/nginx",
        "/v2/library/nginx/blobs/sha256:abc123, library/nginx",
        "/v2/library/nginx/tags/list, library/nginx",
        "/v2/myimage/manifests/1.0, myimage",
        "/v2/myorg/myimage/manifests/latest, myorg/myimage",
        "/v2/registry.example.com/myorg/myimage/manifests/v1, registry.example.com/myorg/myimage",
    })
    void dockerPaths(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Docker: " + url,
            ArtifactNameParser.parse("docker-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v2/", "/v2", "/"})
    void dockerBasePaths(final String url) {
        MatcherAssert.assertThat(
            "Docker base path should not match: " + url,
            ArtifactNameParser.parse("docker-group", url),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- PyPI: simple index and packages ----

    @ParameterizedTest
    @CsvSource({
        "/simple/numpy/, numpy",
        "/simple/requests/, requests",
        "/simple/my-package/, my-package",
        "/simple/My_Package/, my-package",
        "/simple/my.package/, my-package",
    })
    void pypiSimpleIndex(final String url, final String expected) {
        MatcherAssert.assertThat(
            "PyPI simple: " + url,
            ArtifactNameParser.parse("pypi-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/packages/numpy-1.24.0.whl, numpy",
        "/packages/numpy-1.24.0-cp310-cp310-manylinux_2_17_x86_64.whl, numpy",
        "/packages/requests-2.31.0.tar.gz, requests",
        "/packages/my_package-1.0.0.zip, my-package",
    })
    void pypiPackages(final String url, final String expected) {
        MatcherAssert.assertThat(
            "PyPI package: " + url,
            ArtifactNameParser.parse("pypi-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    @Test
    void pypiSimpleRoot() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("pypi-group", "/simple/"),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- Go: module paths ----

    @ParameterizedTest
    @CsvSource({
        "/github.com/gin-gonic/gin/@v/v1.9.1.info, github.com/gin-gonic/gin",
        "/github.com/gin-gonic/gin/@v/v1.9.1.mod, github.com/gin-gonic/gin",
        "/github.com/gin-gonic/gin/@v/v1.9.1.zip, github.com/gin-gonic/gin",
        "/github.com/gin-gonic/gin/@v/list, github.com/gin-gonic/gin",
        "/github.com/gin-gonic/gin/@latest, github.com/gin-gonic/gin",
        "/golang.org/x/text/@v/v0.14.0.info, golang.org/x/text",
    })
    void goPaths(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Go: " + url,
            ArtifactNameParser.parse("go-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    // ---- Gradle: same as Maven ----

    @ParameterizedTest
    @CsvSource({
        "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar, com.google.guava.guava",
        "/org/gradle/gradle-tooling-api/8.5/gradle-tooling-api-8.5.jar, org.gradle.gradle-tooling-api",
        "/com/android/tools/build/gradle/8.2.0/gradle-8.2.0.pom, com.android.tools.build.gradle",
        "/com/google/guava/guava/maven-metadata.xml, com.google.guava.guava",
    })
    void gradleUsessameparserAsMaven(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Gradle: " + url,
            ArtifactNameParser.parse("gradle-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    // ---- Gem: gem downloads and API ----

    @ParameterizedTest
    @CsvSource({
        "/gems/rails-7.1.2.gem, rails",
        "/gems/nokogiri-1.15.4.gem, nokogiri",
        "/gems/aws-sdk-core-3.190.0.gem, aws-sdk-core",
        "/api/v1/gems/rails.json, rails",
        "/quick/Marshal.4.8/rails-7.1.2.gemspec.rz, rails",
        "/quick/Marshal.4.8/nokogiri-1.15.4.gemspec.rz, nokogiri",
    })
    void gemPaths(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Gem: " + url,
            ArtifactNameParser.parse("gem-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    @Test
    void gemDependenciesQuery() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("gem-group",
                "/api/v1/dependencies?gems=rails"),
            new IsEqual<>(Optional.of("rails"))
        );
    }

    // ---- PHP/Composer: package metadata ----

    @ParameterizedTest
    @CsvSource({
        "/p2/monolog/monolog.json, monolog/monolog",
        "/p2/symfony/console.json, symfony/console",
        "/p/vendor/package.json, vendor/package",
    })
    void composerPaths(final String url, final String expected) {
        MatcherAssert.assertThat(
            "Composer: " + url,
            ArtifactNameParser.parse("php-group", url),
            new IsEqual<>(Optional.of(expected))
        );
    }

    @Test
    void composerSatisCacheBusting() {
        // Satis format: /p2/vendor/package$hash.json
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("php-group",
                "/p2/monolog/monolog$abc123def.json"),
            new IsEqual<>(Optional.of("monolog/monolog"))
        );
    }

    @Test
    void composerPackagesJsonReturnsEmpty() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("php-group", "/packages.json"),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- Unknown/unsupported repo types ----

    @ParameterizedTest
    @ValueSource(strings = {"file-group", "helm-group", "unknown", ""})
    void unsupportedTypesReturnEmpty(final String repoType) {
        MatcherAssert.assertThat(
            "Unsupported type '" + repoType + "' should return empty",
            ArtifactNameParser.parse(repoType, "/some/path/file.tar.gz"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void nullRepoType() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse(null, "/some/path"),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void nullPath() {
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("maven-group", null),
            new IsEqual<>(Optional.empty())
        );
    }

    // ---- normalizeType ----

    @ParameterizedTest
    @CsvSource({
        "maven-group, maven",
        "maven-proxy, maven",
        "maven-local, maven",
        "maven, maven",
        "npm-group, npm",
        "npm-proxy, npm",
        "docker-group, docker",
        "docker-remote, docker",
        "pypi-group, pypi",
        "go-group, go",
        "gradle-group, gradle",
        "gem-group, gem",
        "php-group, php",
        "file-group, file",
    })
    void normalizeType(final String input, final String expected) {
        MatcherAssert.assertThat(
            ArtifactNameParser.normalizeType(input),
            new IsEqual<>(expected)
        );
    }

    // ---- Hit rate test: Maven ----

    @Test
    void mavenHitRateAbove95Percent() {
        final String[] urls = {
            // Standard artifacts
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-sources.jar",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-javadoc.jar",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.module",
            // Checksums
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha1",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.md5",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom.sha1",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom.sha256",
            // Metadata
            "/com/google/guava/guava/maven-metadata.xml",
            "/com/google/guava/guava/maven-metadata.xml.sha1",
            "/com/google/guava/guava/31.1.3-jre/maven-metadata.xml",
            // Different libraries
            "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar",
            "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom",
            "/org/apache/commons/commons-lang3/maven-metadata.xml",
            "/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
            "/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.pom",
            "/org/slf4j/slf4j-api/maven-metadata.xml",
            "/junit/junit/4.13.2/junit-4.13.2.jar",
            "/junit/junit/4.13.2/junit-4.13.2.pom",
            "/io/netty/netty-all/4.1.100.Final/netty-all-4.1.100.Final.jar",
            "/io/netty/netty-all/4.1.100.Final/netty-all-4.1.100.Final.pom",
            "/org/springframework/spring-core/6.1.0/spring-core-6.1.0.jar",
            "/com/fasterxml/jackson/core/jackson-databind/2.16.0/jackson-databind-2.16.0.jar",
            "/org/projectlombok/lombok/1.18.30/lombok-1.18.30.jar",
            // SNAPSHOT
            "/org/example/mylib/1.0-SNAPSHOT/mylib-1.0-20230101.120000-1.jar",
            "/org/example/mylib/1.0-SNAPSHOT/maven-metadata.xml",
            // Plugins
            "/org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.jar",
            "/org/apache/maven/plugins/maven-surefire-plugin/3.2.3/maven-surefire-plugin-3.2.3.jar",
            // Gradle wrapper
            "/org/gradle/gradle-tooling-api/8.5/gradle-tooling-api-8.5.jar",
        };
        int hits = 0;
        for (final String url : urls) {
            final Optional<String> result = ArtifactNameParser.parse("maven-group", url);
            if (result.isPresent() && !result.get().isEmpty()) {
                hits++;
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("Maven hit rate %.1f%% must be >= 95%% (%d/%d)", hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    // ---- Hit rate test: npm ----

    @Test
    void npmHitRateAbove95Percent() {
        final String[] urls = {
            // Metadata
            "/lodash",
            "/@babel/core",
            "/@types/node",
            "/express",
            "/react",
            "/react-dom",
            "/@angular/core",
            "/@angular/common",
            "/typescript",
            "/webpack",
            // Tarballs
            "/lodash/-/lodash-4.17.21.tgz",
            "/@babel/core/-/@babel/core-7.23.5.tgz",
            "/@types/node/-/@types/node-20.10.4.tgz",
            "/express/-/express-4.18.2.tgz",
            "/react/-/react-18.2.0.tgz",
            "/@angular/core/-/@angular/core-17.0.8.tgz",
            "/typescript/-/typescript-5.3.3.tgz",
            "/webpack/-/webpack-5.89.0.tgz",
            "/@verdaccio/auth/-/@verdaccio/auth-7.0.0.tgz",
            "/@nestjs/core/-/@nestjs/core-10.3.0.tgz",
        };
        int hits = 0;
        for (final String url : urls) {
            final Optional<String> result = ArtifactNameParser.parse("npm-group", url);
            if (result.isPresent() && !result.get().isEmpty()) {
                hits++;
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("npm hit rate %.1f%% must be >= 95%% (%d/%d)", hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    // ---- Hit rate test: Docker ----

    @Test
    void dockerHitRateAbove95Percent() {
        final String[] urls = {
            "/v2/library/nginx/manifests/latest",
            "/v2/library/nginx/manifests/1.25",
            "/v2/library/nginx/manifests/sha256:abc123def",
            "/v2/library/nginx/blobs/sha256:abc123def",
            "/v2/library/nginx/tags/list",
            "/v2/library/ubuntu/manifests/22.04",
            "/v2/library/ubuntu/blobs/sha256:xyz789",
            "/v2/myorg/myapp/manifests/v1.0.0",
            "/v2/myorg/myapp/blobs/sha256:abc",
            "/v2/myorg/myapp/tags/list",
            "/v2/registry.example.com/project/service/manifests/latest",
            "/v2/registry.example.com/project/service/blobs/sha256:deadbeef",
            "/v2/alpine/manifests/3.18",
            "/v2/alpine/blobs/sha256:abc",
            "/v2/python/manifests/3.12-slim",
        };
        int hits = 0;
        for (final String url : urls) {
            final Optional<String> result = ArtifactNameParser.parse("docker-group", url);
            if (result.isPresent() && !result.get().isEmpty()) {
                hits++;
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("Docker hit rate %.1f%% must be >= 95%% (%d/%d)", hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    // ---- Hit rate test: PyPI ----

    @Test
    void pypiHitRateAbove95Percent() {
        final String[] urls = {
            "/simple/numpy/",
            "/simple/requests/",
            "/simple/flask/",
            "/simple/django/",
            "/simple/scipy/",
            "/simple/pandas/",
            "/simple/tensorflow/",
            "/simple/my-package/",
            "/simple/My_Package/",
            "/packages/numpy-1.24.0.whl",
            "/packages/requests-2.31.0.tar.gz",
            "/packages/flask-3.0.0.whl",
            "/packages/django-5.0.tar.gz",
            "/packages/scipy-1.12.0-cp39-cp39-linux_x86_64.whl",
            "/packages/my_package-1.0.0.zip",
        };
        int hits = 0;
        for (final String url : urls) {
            final Optional<String> result = ArtifactNameParser.parse("pypi-group", url);
            if (result.isPresent() && !result.get().isEmpty()) {
                hits++;
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("PyPI hit rate %.1f%% must be >= 95%% (%d/%d)", hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    // ---- Cross-adapter hit rate: overall ----

    @Test
    void overallHitRateAbove95Percent() {
        final String[][] cases = {
            {"maven-group", "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar"},
            {"maven-group", "/com/google/guava/guava/maven-metadata.xml"},
            {"maven-group", "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar"},
            {"maven-group", "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom"},
            {"maven-group", "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar.sha1"},
            {"maven-group", "/org/apache/commons/commons-lang3/maven-metadata.xml"},
            {"maven-group", "/io/netty/netty-all/4.1.100.Final/netty-all-4.1.100.Final.jar"},
            {"maven-group", "/junit/junit/4.13.2/junit-4.13.2.jar"},
            {"npm-group", "/lodash"},
            {"npm-group", "/lodash/-/lodash-4.17.21.tgz"},
            {"npm-group", "/@babel/core"},
            {"npm-group", "/@babel/core/-/@babel/core-7.23.5.tgz"},
            {"npm-group", "/@types/node/-/@types/node-20.10.4.tgz"},
            {"docker-group", "/v2/library/nginx/manifests/latest"},
            {"docker-group", "/v2/library/nginx/blobs/sha256:abc123"},
            {"docker-group", "/v2/myorg/myapp/manifests/v1.0.0"},
            {"docker-group", "/v2/myorg/myapp/tags/list"},
            {"pypi-group", "/simple/numpy/"},
            {"pypi-group", "/simple/requests/"},
            {"pypi-group", "/packages/numpy-1.24.0.whl"},
            {"go-group", "/github.com/gin-gonic/gin/@v/v1.9.1.info"},
            {"go-group", "/github.com/gin-gonic/gin/@latest"},
            // Gradle (uses Maven format)
            {"gradle-group", "/org/gradle/gradle-tooling-api/8.5/gradle-tooling-api-8.5.jar"},
            {"gradle-group", "/com/android/tools/build/gradle/8.2.0/gradle-8.2.0.pom"},
            // Gem
            {"gem-group", "/gems/rails-7.1.2.gem"},
            {"gem-group", "/gems/nokogiri-1.15.4.gem"},
            {"gem-group", "/api/v1/gems/rails.json"},
            {"gem-group", "/quick/Marshal.4.8/rails-7.1.2.gemspec.rz"},
            // PHP/Composer
            {"php-group", "/p2/monolog/monolog.json"},
            {"php-group", "/p2/symfony/console.json"},
        };
        int hits = 0;
        for (final String[] tc : cases) {
            final Optional<String> result = ArtifactNameParser.parse(tc[0], tc[1]);
            if (result.isPresent() && !result.get().isEmpty()) {
                hits++;
            }
        }
        final double hitRate = (double) hits / cases.length * 100;
        MatcherAssert.assertThat(
            String.format("Overall hit rate %.1f%% must be >= 95%% (%d/%d)", hitRate, hits, cases.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    // ---- Correctness: parsed names match what adapters store in DB ----

    @Test
    void mavenParsedNameMatchesDbFormat() {
        // formatArtifactName replaces / with . on the groupId/artifactId path
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("maven-group",
                "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar"),
            new IsEqual<>(Optional.of("com.google.guava.guava"))
        );
    }

    @Test
    void npmParsedNameMatchesDbFormat() {
        // npm stores the exact package name (with scope if any)
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("npm-group",
                "/@babel/core/-/@babel/core-7.23.5.tgz"),
            new IsEqual<>(Optional.of("@babel/core"))
        );
    }

    @Test
    void dockerParsedNameMatchesDbFormat() {
        // Docker stores the image name including namespace
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("docker-group",
                "/v2/library/nginx/manifests/latest"),
            new IsEqual<>(Optional.of("library/nginx"))
        );
    }

    @Test
    void pypiParsedNameMatchesDbFormat() {
        // PyPI normalizes names (underscores/dots/hyphens → hyphens, lowercase)
        MatcherAssert.assertThat(
            ArtifactNameParser.parse("pypi-group",
                "/simple/My_Package/"),
            new IsEqual<>(Optional.of("my-package"))
        );
    }
}
