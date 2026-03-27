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
package com.auto1.pantera.conda.meta;

import com.auto1.pantera.asto.test.TestResource;
import java.io.IOException;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

/**
 * Test for {@link InfoIndex.TarBz}.
 * @since 0.2
 */
class InfoIndexTarBzTest {

    @Test
    void readsMetadata() throws IOException, JSONException {
        JSONAssert.assertEquals(
            new InfoIndex.TarBz(
                new TestResource("anaconda-navigator-1.8.4-py35_0.tar.bz2").asInputStream()
            ).json().toString(),
            String.join(
                "\n",
                "{\n",
                "  \"arch\": \"x86_64\",",
                "  \"build\": \"py35_0\",",
                "  \"build_number\": 0,",
                "  \"depends\": [",
                "    \"anaconda-client\",",
                "    \"anaconda-project\",",
                "    \"chardet\",",
                "    \"pillow\",",
                "    \"psutil\",",
                "    \"pyqt\",",
                "    \"python >=3.5,<3.6.0a0\",",
                "    \"pyyaml\",",
                "    \"qtpy\",",
                "    \"requests\",",
                "    \"setuptools\"",
                "  ],",
                "  \"license\": \"proprietary - Continuum Analytics, Inc.\",",
                "  \"license_family\": \"Proprietary\",",
                "  \"name\": \"anaconda-navigator\",",
                "  \"platform\": \"linux\",",
                "  \"subdir\": \"linux-64\",",
                "  \"timestamp\": 1524671586445,",
                "  \"version\": \"1.8.4\"",
                "}"
            ),
            true
        );
    }

}
