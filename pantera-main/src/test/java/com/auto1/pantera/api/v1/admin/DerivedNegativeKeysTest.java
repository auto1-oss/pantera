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

import com.auto1.pantera.http.cache.NegativeCacheKey;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link DerivedNegativeKeys}: every key the serving path could have written.
 *
 * @since 2.2.9
 */
final class DerivedNegativeKeysTest {

    @Test
    void derivesGroupKeyAndEachMemberKeyForAnNpmTarball() {
        final FakeTopology topo = new FakeTopology()
            .proxy("npm_proxy", "npm-proxy")
            .local("npm_local", "npm")
            .group("npm_group", "npm-group", "npm_local", "npm_proxy");
        final List<NegativeCacheKey> keys = new DerivedNegativeKeys(topo)
            .keys(topo.repo("npm_group").orElseThrow(), "/lodash/-/lodash-4.17.21.tgz")
            .stream().map(DerivedNegativeKeys.Derived::key).collect(Collectors.toList());
        MatcherAssert.assertThat(
            keys,
            new IsEqual<>(List.of(
                new NegativeCacheKey("npm_group", "npm-group", "lodash", "4.17.21/lodash-4.17.21.tgz"),
                new NegativeCacheKey("npm_local", "npm", "lodash", "4.17.21"),
                new NegativeCacheKey("npm_proxy", "npm-proxy", "lodash", "4.17.21")
            ))
        );
    }

    @Test
    void stripsThePypiSimpleAliasForTheProxyView() {
        final FakeTopology topo = new FakeTopology().proxy("pypi_proxy", "pypi-proxy");
        MatcherAssert.assertThat(
            new DerivedNegativeKeys(topo)
                .keys(topo.repo("pypi_proxy").orElseThrow(), "/simple/requests/")
                .get(0).key(),
            new IsEqual<>(NegativeCacheKey.fromPath("pypi_proxy", "pypi-proxy", "/requests/"))
        );
    }

    @Test
    void expandsNestedGroups() {
        final FakeTopology topo = new FakeTopology()
            .proxy("m_proxy", "maven-proxy")
            .group("inner", "maven-group", "m_proxy")
            .group("outer", "maven-group", "inner");
        final List<String> scopes = new DerivedNegativeKeys(topo)
            .keys(topo.repo("outer").orElseThrow(), "/com/example/foo/1.0/foo-1.0.jar")
            .stream().map(der -> der.key().scope()).collect(Collectors.toList());
        MatcherAssert.assertThat(scopes, new IsEqual<>(List.of("outer", "inner", "m_proxy")));
    }
}
