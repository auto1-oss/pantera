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
package com.auto1.pantera.gem;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the RubyGems index regeneration deserializing
 * repository-controlled Marshal data.
 *
 * <p>Before 2.2.9 {@code Gem#update} copied the stored {@code specs.4.8} /
 * {@code latest_specs.4.8} blobs into the indexing temp dir and
 * {@code metarunner.rb} ran {@code Marshal.load} on their bytes to merge the
 * new gem into the existing index. Those blobs live in repository storage,
 * where a principal holding repository WRITE (or, before Batch 1, the
 * unauthenticated import route) can replace them — and Ruby's
 * {@code Marshal.load} instantiates whatever classes the stream names,
 * which is the classic Ruby deserialization gadget surface inside a full
 * JRuby runtime.</p>
 *
 * <p>The planted stream here names a class that does not exist
 * ({@code o:EvilClass}). Under the vulnerable code the loader resolves the
 * attacker-chosen class name and the update fails with
 * {@code undefined class/module EvilClass} — proof that attacker bytes drove
 * class resolution. After the fix the stored index is never deserialized:
 * the index is rebuilt from the trusted {@code .gem} specs, the update
 * succeeds, and the planted bytes are overwritten.</p>
 *
 * @since 2.2.9
 */
final class GemSpecsDeserializationTest {

    @Test
    void plantedSpecsMarshalIsNeverDeserialized() throws Exception {
        final Storage repo = new InMemoryStorage();
        final Key target = new Key.From("gems", UUID.randomUUID().toString());
        new TestResource("builder-3.2.4.gem").saveTo(repo, target);
        // Marshal 4.8 stream: object of class "EvilClass" with zero ivars.
        // (0x0e = symbol length 9 encoded as n+5.)
        final byte[] planted = {
            0x04, 0x08, 'o', ':', 0x0e,
            'E', 'v', 'i', 'l', 'C', 'l', 'a', 's', 's', 0x00,
        };
        final BlockingStorage blocking = new BlockingStorage(repo);
        blocking.save(new Key.From("specs.4.8"), planted);
        blocking.save(new Key.From("latest_specs.4.8"), planted);

        new Gem(repo).update(target).toCompletableFuture().join();

        final byte[] specs = blocking.value(new Key.From("specs.4.8"));
        MatcherAssert.assertThat(
            "the attacker-planted Marshal index must be replaced, never loaded",
            Arrays.equals(specs, planted), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the regenerated index must be built from the real gem specs",
            new String(specs, StandardCharsets.ISO_8859_1).contains("builder"),
            new IsEqual<>(true)
        );
    }
}
