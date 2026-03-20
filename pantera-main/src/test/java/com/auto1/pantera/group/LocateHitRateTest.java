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
package com.auto1.pantera.group;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.DbArtifactIndex;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * End-to-end hit rate test for the full locate flow:
 * HTTP URL path -> ArtifactNameParser -> locateByName() -> DB lookup -> repo found.
 *
 * This test populates the DB with artifacts exactly as each adapter stores them,
 * then verifies that URL paths clients actually send result in successful lookups.
 * Hit rate must be >= 95%.
 *
 * @since 1.21.0
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
@Testcontainers
final class LocateHitRateTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    private DataSource dataSource;
    private DbArtifactIndex index;

    @BeforeEach
    void setUp() throws Exception {
        this.dataSource = new ArtifactDbFactory(
            Yaml.createYamlMappingBuilder().add(
                "artifacts_database",
                Yaml.createYamlMappingBuilder()
                    .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                    .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                    .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                    .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                    .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                    .build()
            ).build(),
            "artifacts"
        ).initialize();
        try (Connection conn = this.dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM artifacts");
        }
        this.index = new DbArtifactIndex(this.dataSource);
    }

    @AfterEach
    void tearDown() {
        if (this.index != null) {
            this.index.close();
        }
    }

    @Test
    void mavenEndToEndHitRate() throws Exception {
        // Populate DB with Maven artifacts in the format UploadSlice/MavenProxyPackageProcessor
        // stores them: name = groupId.artifactId (dots), version = version string
        final String[][] artifacts = {
            {"com.google.guava.guava", "31.1.3-jre"},
            {"com.google.guava.guava", "32.1.2-jre"},
            {"org.apache.commons.commons-lang3", "3.14.0"},
            {"org.slf4j.slf4j-api", "2.0.9"},
            {"junit.junit", "4.13.2"},
            {"io.netty.netty-all", "4.1.100.Final"},
            {"com.fasterxml.jackson.core.jackson-databind", "2.16.0"},
            {"org.projectlombok.lombok", "1.18.30"},
            {"org.springframework.spring-core", "6.1.0"},
            {"org.apache.maven.plugins.maven-compiler-plugin", "3.11.0"},
            {"org.apache.maven.plugins.maven-surefire-plugin", "3.2.3"},
            {"org.example.mylib", "1.0-SNAPSHOT"},
        };
        for (final String[] art : artifacts) {
            this.index.index(new ArtifactDocument(
                "maven", "maven-central", art[0],
                art[0].substring(art[0].lastIndexOf('.') + 1),
                art[1], 100_000L, Instant.now(), "proxy"
            )).join();
        }

        // URLs that Maven clients actually send
        final String[] urls = {
            // Artifact JARs
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar",
            "/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar",
            "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar",
            "/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
            "/junit/junit/4.13.2/junit-4.13.2.jar",
            "/io/netty/netty-all/4.1.100.Final/netty-all-4.1.100.Final.jar",
            "/com/fasterxml/jackson/core/jackson-databind/2.16.0/jackson-databind-2.16.0.jar",
            "/org/projectlombok/lombok/1.18.30/lombok-1.18.30.jar",
            "/org/springframework/spring-core/6.1.0/spring-core-6.1.0.jar",
            // POMs
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom",
            "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom",
            "/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.pom",
            // Checksums
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha1",
            "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar.md5",
            "/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.pom.sha256",
            // Sources/javadoc
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-sources.jar",
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre-javadoc.jar",
            // Metadata
            "/com/google/guava/guava/maven-metadata.xml",
            "/org/apache/commons/commons-lang3/maven-metadata.xml",
            "/org/slf4j/slf4j-api/maven-metadata.xml",
            "/org/slf4j/slf4j-api/2.0.9/maven-metadata.xml",
            "/com/google/guava/guava/maven-metadata.xml.sha1",
            // Gradle module
            "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.module",
            // Plugins
            "/org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.jar",
            "/org/apache/maven/plugins/maven-compiler-plugin/3.11.0/maven-compiler-plugin-3.11.0.pom",
            "/org/apache/maven/plugins/maven-surefire-plugin/3.2.3/maven-surefire-plugin-3.2.3.jar",
            // SNAPSHOT
            "/org/example/mylib/1.0-SNAPSHOT/mylib-1.0-20230101.120000-1.jar",
            "/org/example/mylib/1.0-SNAPSHOT/maven-metadata.xml",
        };

        int hits = 0;
        int total = urls.length;
        for (final String url : urls) {
            final Optional<String> parsed = ArtifactNameParser.parse("maven-group", url);
            if (parsed.isPresent()) {
                final List<String> repos = this.index.locateByName(parsed.get()).join();
                if (!repos.isEmpty()) {
                    hits++;
                }
            }
        }
        final double hitRate = (double) hits / total * 100;
        MatcherAssert.assertThat(
            String.format(
                "Maven E2E hit rate %.1f%% must be >= 95%% (%d/%d)",
                hitRate, hits, total
            ),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    @Test
    void npmEndToEndHitRate() throws Exception {
        // npm stores: name = package name (with scope), version = version string
        final String[][] artifacts = {
            {"lodash", "4.17.21"},
            {"@babel/core", "7.23.5"},
            {"@types/node", "20.10.4"},
            {"express", "4.18.2"},
            {"react", "18.2.0"},
            {"typescript", "5.3.3"},
            {"webpack", "5.89.0"},
            {"@angular/core", "17.0.8"},
        };
        for (final String[] art : artifacts) {
            this.index.index(new ArtifactDocument(
                "npm", "npm-proxy", art[0], art[0],
                art[1], 50_000L, Instant.now(), "proxy"
            )).join();
        }

        final String[] urls = {
            // Metadata
            "/lodash",
            "/@babel/core",
            "/@types/node",
            "/express",
            "/react",
            "/typescript",
            "/webpack",
            "/@angular/core",
            // Tarballs
            "/lodash/-/lodash-4.17.21.tgz",
            "/@babel/core/-/@babel/core-7.23.5.tgz",
            "/@types/node/-/@types/node-20.10.4.tgz",
            "/express/-/express-4.18.2.tgz",
            "/react/-/react-18.2.0.tgz",
            "/typescript/-/typescript-5.3.3.tgz",
            "/webpack/-/webpack-5.89.0.tgz",
            "/@angular/core/-/@angular/core-17.0.8.tgz",
        };

        int hits = 0;
        for (final String url : urls) {
            final Optional<String> parsed = ArtifactNameParser.parse("npm-group", url);
            if (parsed.isPresent()) {
                final List<String> repos = this.index.locateByName(parsed.get()).join();
                if (!repos.isEmpty()) {
                    hits++;
                }
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("npm E2E hit rate %.1f%% must be >= 95%% (%d/%d)",
                hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    @Test
    void dockerEndToEndHitRate() throws Exception {
        // Docker stores: name = image name, version = manifest digest
        final String[][] artifacts = {
            {"library/nginx", "sha256:abc123"},
            {"library/ubuntu", "sha256:def456"},
            {"myorg/myapp", "sha256:789xyz"},
            {"alpine", "sha256:aaa111"},
            {"python", "sha256:bbb222"},
        };
        for (final String[] art : artifacts) {
            this.index.index(new ArtifactDocument(
                "docker", "docker-proxy", art[0], art[0],
                art[1], 200_000_000L, Instant.now(), "proxy"
            )).join();
        }

        final String[] urls = {
            "/v2/library/nginx/manifests/latest",
            "/v2/library/nginx/manifests/1.25",
            "/v2/library/nginx/manifests/sha256:abc123",
            "/v2/library/nginx/blobs/sha256:abc123",
            "/v2/library/nginx/tags/list",
            "/v2/library/ubuntu/manifests/22.04",
            "/v2/library/ubuntu/blobs/sha256:def456",
            "/v2/myorg/myapp/manifests/v1.0.0",
            "/v2/myorg/myapp/blobs/sha256:789xyz",
            "/v2/myorg/myapp/tags/list",
            "/v2/alpine/manifests/3.18",
            "/v2/alpine/blobs/sha256:aaa111",
            "/v2/python/manifests/3.12-slim",
            "/v2/python/blobs/sha256:bbb222",
        };

        int hits = 0;
        for (final String url : urls) {
            final Optional<String> parsed = ArtifactNameParser.parse("docker-group", url);
            if (parsed.isPresent()) {
                final List<String> repos = this.index.locateByName(parsed.get()).join();
                if (!repos.isEmpty()) {
                    hits++;
                }
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("Docker E2E hit rate %.1f%% must be >= 95%% (%d/%d)",
                hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    @Test
    void pypiEndToEndHitRate() throws Exception {
        // PyPI stores: name = normalized project name (lowercase, hyphens)
        final String[][] artifacts = {
            {"numpy", "1.24.0"},
            {"requests", "2.31.0"},
            {"flask", "3.0.0"},
            {"django", "5.0"},
            {"scipy", "1.12.0"},
            {"my-package", "1.0.0"},
        };
        for (final String[] art : artifacts) {
            this.index.index(new ArtifactDocument(
                "pypi", "pypi-proxy", art[0], art[0],
                art[1], 10_000_000L, Instant.now(), "proxy"
            )).join();
        }

        final String[] urls = {
            "/simple/numpy/",
            "/simple/requests/",
            "/simple/flask/",
            "/simple/django/",
            "/simple/scipy/",
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
            final Optional<String> parsed = ArtifactNameParser.parse("pypi-group", url);
            if (parsed.isPresent()) {
                final List<String> repos = this.index.locateByName(parsed.get()).join();
                if (!repos.isEmpty()) {
                    hits++;
                }
            }
        }
        final double hitRate = (double) hits / urls.length * 100;
        MatcherAssert.assertThat(
            String.format("PyPI E2E hit rate %.1f%% must be >= 95%% (%d/%d)",
                hitRate, hits, urls.length),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }

    @Test
    void combinedEndToEndHitRate() throws Exception {
        // Populate a realistic mixed-adapter database
        // Maven artifacts
        for (final String name : List.of(
            "com.google.guava.guava", "org.apache.commons.commons-lang3",
            "org.slf4j.slf4j-api", "junit.junit", "io.netty.netty-all"
        )) {
            this.index.index(new ArtifactDocument(
                "maven", "maven-central", name,
                name.substring(name.lastIndexOf('.') + 1),
                "1.0.0", 100_000L, Instant.now(), "proxy"
            )).join();
        }
        // npm artifacts
        for (final String name : List.of("lodash", "@babel/core", "express")) {
            this.index.index(new ArtifactDocument(
                "npm", "npm-proxy", name, name,
                "1.0.0", 50_000L, Instant.now(), "proxy"
            )).join();
        }
        // Docker artifacts
        for (final String name : List.of("library/nginx", "alpine")) {
            this.index.index(new ArtifactDocument(
                "docker", "docker-proxy", name, name,
                "sha256:abc", 200_000_000L, Instant.now(), "proxy"
            )).join();
        }

        final String[][] cases = {
            {"maven-group", "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar"},
            {"maven-group", "/com/google/guava/guava/maven-metadata.xml"},
            {"maven-group", "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.pom"},
            {"maven-group", "/com/google/guava/guava/31.1.3-jre/guava-31.1.3-jre.jar.sha1"},
            {"maven-group", "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar"},
            {"maven-group", "/org/apache/commons/commons-lang3/maven-metadata.xml"},
            {"maven-group", "/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"},
            {"maven-group", "/junit/junit/4.13.2/junit-4.13.2.jar"},
            {"maven-group", "/io/netty/netty-all/4.1.100.Final/netty-all-4.1.100.Final.jar"},
            {"npm-group", "/lodash"},
            {"npm-group", "/lodash/-/lodash-4.17.21.tgz"},
            {"npm-group", "/@babel/core"},
            {"npm-group", "/@babel/core/-/@babel/core-7.23.5.tgz"},
            {"npm-group", "/express"},
            {"npm-group", "/express/-/express-4.18.2.tgz"},
            {"docker-group", "/v2/library/nginx/manifests/latest"},
            {"docker-group", "/v2/library/nginx/blobs/sha256:abc"},
            {"docker-group", "/v2/alpine/manifests/3.18"},
            {"docker-group", "/v2/alpine/tags/list"},
        };

        int hits = 0;
        for (final String[] tc : cases) {
            final Optional<String> parsed = ArtifactNameParser.parse(tc[0], tc[1]);
            if (parsed.isPresent()) {
                final List<String> repos = this.index.locateByName(parsed.get()).join();
                if (!repos.isEmpty()) {
                    hits++;
                }
            }
        }
        final double hitRate = (double) hits / cases.length * 100;
        MatcherAssert.assertThat(
            String.format(
                "Combined E2E hit rate %.1f%% must be >= 95%% (%d/%d)",
                hitRate, hits, cases.length
            ),
            hitRate >= 95.0,
            new IsEqual<>(true)
        );
    }
}
