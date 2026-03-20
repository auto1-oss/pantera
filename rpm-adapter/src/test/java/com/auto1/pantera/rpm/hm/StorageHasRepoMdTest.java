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
package com.auto1.pantera.rpm.hm;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.rpm.Digest;
import com.auto1.pantera.rpm.RepoConfig;
import com.auto1.pantera.rpm.StandardNamingPolicy;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.llorllale.cactoos.matchers.Assertion;

/**
 * Tests for {@link StorageHasRepoMd} matcher.
 *
 * @since 1.1
 */
public final class StorageHasRepoMdTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void matchPositive(final boolean filelists) {
        final Storage storage = new InMemoryStorage();
        new TestResource(
            "repodata/StorageHasRepoMdTest/repomd.xml"
        ).saveTo(storage, new Key.From("repodata/repomd.xml"));
        new TestResource(
            "repodata/StorageHasRepoMdTest/primary.xml.gz"
        ).saveTo(storage, new Key.From("repodata/primary.xml.gz"));
        new TestResource(
            "repodata/StorageHasRepoMdTest/other.xml.gz"
        ).saveTo(storage, new Key.From("repodata/other.xml.gz"));
        if (filelists) {
            new TestResource(
                "repodata/StorageHasRepoMdTest/filelists.xml.gz"
            ).saveTo(storage, new Key.From("repodata/filelists.xml.gz"));
        }
        new Assertion<>(
            "The matcher gives positive result for a valid repomd.xml configuration",
            storage,
            new StorageHasRepoMd(
                new RepoConfig.Simple(Digest.SHA256, StandardNamingPolicy.PLAIN, filelists)
            )
        ).affirm();
    }

    @Test
    public void doNotMatchesWhenRepomdAbsent() {
        new Assertion<>(
            "The matcher gives a negative result when storage does not have repomd.xml",
            new InMemoryStorage(),
            new IsNot<>(new StorageHasRepoMd(new RepoConfig.Simple()))
        ).affirm();
    }
}
