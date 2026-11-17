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
package com.auto1.pantera;

import com.auto1.pantera.asto.Key;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link RepoStorageKey}.
 *
 * @since 2.2.12
 */
final class RepoStorageKeyTest {

    @ParameterizedTest(name = "{0} port={1} {2} -> {3}")
    @CsvSource(nullValues = "-", value = {
        // Main port: the repository name is the first path segment.
        "maven, false, /libs/org/example/tool/1.0/tool-1.0-bin.zip, org/example/tool/1.0/tool-1.0-bin.zip",
        "file,  false, /bin/tools/linux-amd64/kubectl, tools/linux-amd64/kubectl",
        "npm,   false, /npm/@acme/pkg/-/@acme/pkg-1.0.0.tgz, @acme/pkg/-/@acme/pkg-1.0.0.tgz",
        // The repository root names no object.
        "maven, false, /libs, -",
        "maven, false, /libs/, -",
        "maven, false, /, -",
        // conda and conan on their own port are served from the root.
        "conda, true,  /linux-64/pkg-1.0-0.tar.bz2, linux-64/pkg-1.0-0.tar.bz2",
        "conan, true,  /v1/files/zlib/1.3/_/_/0/export/conanfile.py, v1/files/zlib/1.3/_/_/0/export/conanfile.py",
        "conda, false, /my-conda/linux-64/pkg-1.0-0.tar.bz2, linux-64/pkg-1.0-0.tar.bz2",
        // Every other type trims the repository name on its own port too.
        "maven, true,  /libs/org/example/tool/1.0/tool-1.0.jar, org/example/tool/1.0/tool-1.0.jar",
        // conda's URL token is not part of the key.
        "conda, false, /my-conda/t/secret-token/noarch/pkg-1.0-0.conda, noarch/pkg-1.0-0.conda",
        "conda, true,  /t/secret-token/noarch/pkg-1.0-0.conda, noarch/pkg-1.0-0.conda",
        // A path segment named t is only a token marker for conda.
        "file,  false, /bin/t/x/readme.txt, t/x/readme.txt",
        // Checksum and signature files are not looked up.
        "maven, false, /libs/org/example/tool/1.0/tool-1.0.jar.sha1, -",
        "maven, false, /libs/org/example/tool/1.0/tool-1.0.pom.MD5, -",
        "maven, false, /libs/org/example/tool/1.0/tool-1.0.jar.sha256, -",
        "maven, false, /libs/org/example/tool/1.0/tool-1.0.jar.sha512, -",
        "maven, false, /libs/org/example/tool/1.0/tool-1.0.jar.asc, -",
        "file,  false, /bin/tools/kubectl.sig, -"
    })
    void mapsRequestPathToStorageKey(
        final String type, final boolean port, final String path, final String key
    ) {
        MatcherAssert.assertThat(
            new RepoStorageKey(type, port).apply(path).map(Key::string),
            new IsEqual<>(Optional.ofNullable(key))
        );
    }
}
