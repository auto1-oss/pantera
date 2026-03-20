/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.debian.metadata;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.ContentIs;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.debian.Config;
import org.cactoos.list.ListOf;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Test for {@link InRelease.Asto}.
 */
class InReleaseAstoTest {

    /**
     * Test storage.
     */
    private Storage asto;

    @BeforeEach
    void init() {
        this.asto = new InMemoryStorage();
    }

    @Test
    void generatesInRelease() {
        final String name = "my-deb";
        final Key key = new Key.From("dists", name, "Release");
        new TestResource("Release").saveTo(this.asto, key);
        final String secret = "secret-keys.gpg";
        new TestResource(secret).saveTo(this.asto);
        new InRelease.Asto(
            this.asto,
            new Config.FromYaml(
                name,
                Yaml.createYamlMappingBuilder().add("gpg_password", "1q2w3e4r5t6y7u")
                    .add("gpg_secret_key", secret).build(),
                this.asto
            )
        ).generate(key).toCompletableFuture().join();
        MatcherAssert.assertThat(
            this.asto.value(new Key.From("dists", name, "InRelease")).join().asString(),
            new AllOf<>(
                new ListOf<Matcher<? super String>>(
                    new StringContains(new String(new TestResource("Release").asBytes())),
                    new StringContains("-----BEGIN PGP SIGNED MESSAGE-----"),
                    new StringContains("Hash: SHA256"),
                    new StringContains("-----BEGIN PGP SIGNATURE-----"),
                    new StringContains("-----END PGP SIGNATURE-----")
                )
            )
        );
    }

    @Test
    void generatesIfGpgIsNotSet() {
        final String name = "my-repo";
        final Key.From key = new Key.From("dists", name, "Release");
        final byte[] bytes = "abc123".getBytes(StandardCharsets.UTF_8);
        this.asto.save(key, new Content.From(bytes)).join();
        final InRelease release = new InRelease.Asto(
            this.asto,
            new Config.FromYaml(name, Yaml.createYamlMappingBuilder().build(), this.asto)
        );
        release.generate(key).toCompletableFuture().join();
        MatcherAssert.assertThat(
            this.asto.value(release.key()).join(),
            new ContentIs(bytes)
        );
    }

}
