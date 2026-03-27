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
package com.auto1.pantera.nuget.metadata;

import com.auto1.pantera.asto.test.TestResource;
import com.google.common.collect.Lists;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import org.json.JSONException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.skyscreamer.jsonassert.JSONAssert;

/**
 * Test for {@link DependencyGroups.FromVersions}.
 * @since 0.8
 */
class DependencyGroupsTest {

    @ParameterizedTest
    @CsvSource({
        "one:0.1:AnyFramework;two:0.2:AnyFramework;another:0.1:anotherFrameWork,json_res1.json",
        "abc:0.1:ABCFramework;xyz:0.0.1:;def:0.1:,json_res2.json",
        "::EmptyFramework;xyz:0.0.1:XyzFrame;def::DefFrame,json_res3.json"
    })
    void buildsJson(final String list, final String res) throws JSONException {
        JSONAssert.assertEquals(
            Json.createObjectBuilder().add(
                "DependencyGroups",
                new DependencyGroups.FromVersions(Lists.newArrayList(list.split(";"))).build()
            ).build().toString(),
            new String(
                new TestResource(String.format("DependencyGroupsTest/%s", res)).asBytes(),
                StandardCharsets.UTF_8
            ),
            true
        );
    }

}
