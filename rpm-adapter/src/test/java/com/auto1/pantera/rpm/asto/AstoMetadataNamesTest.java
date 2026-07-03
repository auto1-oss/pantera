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
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.StandardNamingPolicy;
import com.auto1.pantera.rpm.meta.XmlPackage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.cactoos.map.MapEntry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link AstoMetadataNames}.
 * @since 1.10
 */
class AstoMetadataNamesTest {

    /**
     * Test storage.
     */
    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preparesNewNames(final boolean filelists) {
        final Key temp = new Key.From(UUID.randomUUID().toString());
        new XmlPackage.Stream(filelists).get().forEach(
            item -> this.storage.save(
                new Key.From(temp, item.name()),
                new Content.From(item.lowercase().getBytes())
            ).join()
        );
        final Key repomd = new Key.From(temp, "repomd.xml");
        this.storage.save(repomd, new Content.From("repomd".getBytes())).join();
        final StandardNamingPolicy snp = StandardNamingPolicy.SHA256;
        final Map<Key, Key> res = new AstoMetadataNames(
            this.storage, new RepoConfig.Simple(Digest.SHA256, snp, filelists)
        ).prepareNames(temp).toCompletableFuture().join();
        final List<MapEntry<Key, Key>> expected = new XmlPackage.Stream(filelists).get().map(
            item -> new MapEntry<Key, Key>(
                new Key.From(temp, item.name()),
                new Key.From(
                    snp.fullName(item, DigestUtils.sha256Hex(item.lowercase().getBytes()))
                )
            )
        ).collect(Collectors.toList());
        expected.add(
            new MapEntry<>(repomd, new Key.From("repodata", "repomd.xml"))
        );
        MatcherAssert.assertThat(
            res.entrySet(),
            Matchers.containsInAnyOrder(expected.toArray())
        );
    }
}
