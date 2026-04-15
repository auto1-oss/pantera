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
package com.auto1.pantera.pypi.meta;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link ValidFilename}.
 * @since 0.6
 */
class ValidFilenameTest {

    @ParameterizedTest
    @CsvSource({
        "my-project,0.3,my-project-0.3.tar.gz,true",
        "my-project,0.3,my-project-0.4.tar,false",
        "Another_project,123.93,Another_project-123.93.zip,true",
        "very-difficult-project,1.0a2,very-difficult-project-1.0a2-py3-any-none.whl,true",
        "one,0.0.1,two-0.2.tar.gz,false"
    })
    void checks(final String name, final String version, final String filename, final boolean res) {
        MatcherAssert.assertThat(
            new ValidFilename(
                new PackageInfo.FromMetadata(this.metadata(name, version)), filename
            ).valid(),
            new IsEqual<>(res)
        );
    }

    @ParameterizedTest
    @CsvSource({
        // CPython wheels — the bug
        "charset_normalizer,3.4.7,charset_normalizer-3.4.7-cp313-cp313-macosx_10_13_universal2.whl,true",
        "pydantic_core,2.41.5,pydantic_core-2.41.5-cp313-cp313-macosx_11_0_arm64.whl,true",
        "numpy,2.1.0,numpy-2.1.0-cp312-cp312-linux_x86_64.whl,true",
        "tensorflow,2.18.0,tensorflow-2.18.0-cp311-cp311-manylinux_2_17_x86_64.whl,true",
        // PyPy wheels
        "cryptography,43.0.1,cryptography-43.0.1-pp310-pypy310_pp73-linux_x86_64.whl,true",
        // Pure Python (existing) — regression guard
        "requests,2.31.0,requests-2.31.0-py3-none-any.whl,true",
        // Multi-tag pure Python
        "six,1.16.0,six-1.16.0-py2.py3-none-any.whl,true"
    })
    void cpythonAndPypyWheelsValidate(final String name, final String version,
        final String filename, final boolean valid) {
        MatcherAssert.assertThat(
            new ValidFilename(
                new PackageInfo.FromMetadata(this.metadata(name, version)), filename
            ).valid(),
            new IsEqual<>(valid)
        );
    }

    private String metadata(final String name, final String version) {
        return String.join(
            "\n",
            String.format("Name: %s", name),
            String.format("Version: %s", version)
        );
    }

}
