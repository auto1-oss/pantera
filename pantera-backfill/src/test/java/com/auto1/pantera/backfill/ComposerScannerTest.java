/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ComposerScanner}.
 *
 * @since 1.20.13
 */
final class ComposerScannerTest {

    @Test
    void scansP2Layout(@TempDir final Path temp) throws IOException {
        final Path vendorDir = temp.resolve("p2").resolve("vendor");
        Files.createDirectories(vendorDir);
        Files.writeString(
            vendorDir.resolve("package.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"vendor/package\": {",
                "      \"1.0.0\": {",
                "        \"name\": \"vendor/package\",",
                "        \"version\": \"1.0.0\",",
                "        \"dist\": {",
                "          \"url\": \"https://example.com/vendor/package-1.0.0.zip\",",
                "          \"type\": \"zip\"",
                "        }",
                "      },",
                "      \"2.0.0\": {",
                "        \"name\": \"vendor/package\",",
                "        \"version\": \"2.0.0\",",
                "        \"dist\": {",
                "          \"url\": \"https://example.com/vendor/package-2.0.0.zip\",",
                "          \"type\": \"zip\"",
                "        }",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "composer-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records for 2 versions",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "All records should have name vendor/package",
            records.stream().allMatch(
                r -> "vendor/package".equals(r.name())
            ),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 1.0.0",
            records.stream().anyMatch(r -> "1.0.0".equals(r.version())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain version 2.0.0",
            records.stream().anyMatch(r -> "2.0.0".equals(r.version())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Repo type should be composer",
            records.get(0).repoType(),
            Matchers.is("composer")
        );
    }

    @Test
    void scansPackagesJsonLayout(@TempDir final Path temp) throws IOException {
        Files.writeString(
            temp.resolve("packages.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"acme/foo\": {",
                "      \"1.0.0\": {",
                "        \"name\": \"acme/foo\",",
                "        \"version\": \"1.0.0\",",
                "        \"dist\": {",
                "          \"url\": \"https://example.com/acme/foo-1.0.0.zip\",",
                "          \"type\": \"zip\"",
                "        }",
                "      }",
                "    },",
                "    \"acme/bar\": {",
                "      \"2.0.0\": {",
                "        \"name\": \"acme/bar\",",
                "        \"version\": \"2.0.0\",",
                "        \"dist\": {",
                "          \"url\": \"https://example.com/acme/bar-2.0.0.zip\",",
                "          \"type\": \"zip\"",
                "        }",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "composer-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 2 records for 2 packages",
            records,
            Matchers.hasSize(2)
        );
        MatcherAssert.assertThat(
            "Should contain acme/foo",
            records.stream().anyMatch(r -> "acme/foo".equals(r.name())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain acme/bar",
            records.stream().anyMatch(r -> "acme/bar".equals(r.name())),
            Matchers.is(true)
        );
    }

    @Test
    void prefersP2OverPackagesJson(@TempDir final Path temp)
        throws IOException {
        final Path vendorDir = temp.resolve("p2").resolve("vendor");
        Files.createDirectories(vendorDir);
        Files.writeString(
            vendorDir.resolve("lib.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"vendor/lib\": {",
                "      \"1.0.0\": {",
                "        \"name\": \"vendor/lib\",",
                "        \"version\": \"1.0.0\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            temp.resolve("packages.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"other/pkg\": {",
                "      \"3.0.0\": {",
                "        \"name\": \"other/pkg\",",
                "        \"version\": \"3.0.0\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "composer-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record from p2 only",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Should contain vendor/lib from p2 layout",
            records.get(0).name(),
            Matchers.is("vendor/lib")
        );
        MatcherAssert.assertThat(
            "Should NOT contain other/pkg from packages.json",
            records.stream().noneMatch(r -> "other/pkg".equals(r.name())),
            Matchers.is(true)
        );
    }

    @Test
    void handlesMissingPackagesKey(@TempDir final Path temp)
        throws IOException {
        final Path vendorDir = temp.resolve("p2").resolve("vendor");
        Files.createDirectories(vendorDir);
        Files.writeString(
            vendorDir.resolve("nopackages.json"),
            String.join(
                "\n",
                "{",
                "  \"minified\": \"provider/latest\"",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "composer-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records when packages key is missing",
            records,
            Matchers.empty()
        );
    }

    @Test
    void skipsDevJsonFiles(@TempDir final Path temp) throws IOException {
        final Path vendorDir = temp.resolve("p2").resolve("vendor");
        Files.createDirectories(vendorDir);
        Files.writeString(
            vendorDir.resolve("pkg~dev.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"vendor/pkg\": {",
                "      \"dev-master\": {",
                "        \"name\": \"vendor/pkg\",",
                "        \"version\": \"dev-master\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "composer-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records when only ~dev.json files exist",
            records,
            Matchers.empty()
        );
    }

    @Test
    void handlesEmptyRoot(@TempDir final Path temp) throws IOException {
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "composer-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 0 records for empty root",
            records,
            Matchers.empty()
        );
    }

    @Test
    void skipsEmptyPackagesJsonAndScansVendorDirs(@TempDir final Path temp)
        throws IOException {
        // packages.json exists but is 0 bytes (common in Pantera proxy repos)
        Files.createFile(temp.resolve("packages.json"));
        // vendor-dir layout files exist with real content
        final Path vendorDir = temp.resolve("psr");
        Files.createDirectories(vendorDir);
        Files.writeString(
            vendorDir.resolve("log.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"psr/log\": {",
                "      \"1.0.0\": {",
                "        \"name\": \"psr/log\",",
                "        \"version\": \"1.0.0\"",
                "      }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should find 1 record from vendor-dir layout despite empty packages.json",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Record name should be psr/log",
            records.get(0).name(),
            Matchers.is("psr/log")
        );
        MatcherAssert.assertThat(
            "Record version should be 1.0.0",
            records.get(0).version(),
            Matchers.is("1.0.0")
        );
    }

    @Test
    void scansVendorDirLayout(@TempDir final Path temp) throws IOException {
        // Two vendor directories, multiple packages
        final Path psr = temp.resolve("psr");
        Files.createDirectories(psr);
        Files.writeString(
            psr.resolve("log.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"psr/log\": {",
                "      \"1.0.0\": { \"name\": \"psr/log\", \"version\": \"1.0.0\" },",
                "      \"2.0.0\": { \"name\": \"psr/log\", \"version\": \"2.0.0\" }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            psr.resolve("http-message.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"psr/http-message\": {",
                "      \"1.1.0\": { \"name\": \"psr/http-message\", \"version\": \"1.1.0\" }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final Path symfony = temp.resolve("symfony");
        Files.createDirectories(symfony);
        Files.writeString(
            symfony.resolve("http-client.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"symfony/http-client\": {",
                "      \"6.4.0\": { \"name\": \"symfony/http-client\", \"version\": \"6.4.0\" }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 4 records total (2 psr/log + 1 psr/http-message + 1 symfony/http-client)",
            records,
            Matchers.hasSize(4)
        );
        MatcherAssert.assertThat(
            "Should contain psr/log",
            records.stream().anyMatch(r -> "psr/log".equals(r.name())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain psr/http-message",
            records.stream().anyMatch(r -> "psr/http-message".equals(r.name())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should contain symfony/http-client",
            records.stream().anyMatch(r -> "symfony/http-client".equals(r.name())),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "All records should have composer repo type",
            records.stream().allMatch(r -> "composer".equals(r.repoType())),
            Matchers.is(true)
        );
    }

    @Test
    void skipsEmptyFilesInVendorDirLayout(@TempDir final Path temp)
        throws IOException {
        final Path psr = temp.resolve("psr");
        Files.createDirectories(psr);
        // One empty file (0 bytes) — should be skipped silently
        Files.createFile(psr.resolve("log.json"));
        // One non-empty file — should be scanned
        Files.writeString(
            psr.resolve("container.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"psr/container\": {",
                "      \"2.0.0\": { \"name\": \"psr/container\", \"version\": \"2.0.0\" }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record — empty file skipped, non-empty file scanned",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Record should be from psr/container (non-empty file)",
            records.get(0).name(),
            Matchers.is("psr/container")
        );
    }

    @Test
    void skipsDevJsonFilesInVendorDirLayout(@TempDir final Path temp)
        throws IOException {
        final Path openTelemetry = temp.resolve("open-telemetry");
        Files.createDirectories(openTelemetry);
        // dev file — should be skipped
        Files.writeString(
            openTelemetry.resolve("sem-conv~dev.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"open-telemetry/sem-conv\": {",
                "      \"dev-main\": { \"name\": \"open-telemetry/sem-conv\", \"version\": \"dev-main\" }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        // stable file — should be scanned
        Files.writeString(
            openTelemetry.resolve("sem-conv.json"),
            String.join(
                "\n",
                "{",
                "  \"packages\": {",
                "    \"open-telemetry/sem-conv\": {",
                "      \"1.0.0\": { \"name\": \"open-telemetry/sem-conv\", \"version\": \"1.0.0\" }",
                "    }",
                "  }",
                "}"
            ),
            StandardCharsets.UTF_8
        );
        final ComposerScanner scanner = new ComposerScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "php-proxy")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce 1 record — ~dev.json file skipped",
            records,
            Matchers.hasSize(1)
        );
        MatcherAssert.assertThat(
            "Record version should be 1.0.0 (from stable file, not dev)",
            records.get(0).version(),
            Matchers.is("1.0.0")
        );
    }
}
