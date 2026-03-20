/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.Publish;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link CliPublish}.
 * @since 0.9
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class CliPublishTest {

    @Test
    void metaFileAndTgzArchiveExist() {
        final Storage asto = new InMemoryStorage();
        final Key prefix = new Key.From("@hello/simple-npm-project");
        final Key name = new Key.From("uploaded-artifact");
        new TestResource("json/cli_publish.json").saveTo(asto, name);
        new CliPublish(asto).publish(prefix, name).join();
        // Generate meta.json from per-version files
        new PerVersionLayout(asto).generateMetaJson(prefix)
            .thenCompose(meta -> asto.save(
                new Key.From(prefix, "meta.json"),
                new com.auto1.pantera.asto.Content.From(meta.toString().getBytes(StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            "Tgz archive was created",
            asto.exists(new Key.From(String.format("%s/-/%s-1.0.1.tgz", prefix, prefix))).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Meta json file was create",
            asto.exists(new Key.From(prefix, "meta.json")).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void returnsCorrectPackageInfo() {
        final Storage asto = new InMemoryStorage();
        final Key prefix = new Key.From("@hello/simple-npm-project");
        final Key name = new Key.From("uploaded-artifact");
        new TestResource("json/cli_publish.json").saveTo(asto, name);
        final Publish.PackageInfo res = new CliPublish(asto).publishWithInfo(prefix, name).join();
        // Generate meta.json from per-version files
        new PerVersionLayout(asto).generateMetaJson(prefix)
            .thenCompose(meta -> asto.save(
                new Key.From(prefix, "meta.json"),
                new com.auto1.pantera.asto.Content.From(meta.toString().getBytes(StandardCharsets.UTF_8))
            ))
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            "Tgz archive was created",
            asto.exists(new Key.From(String.format("%s/-/%s-1.0.1.tgz", prefix, prefix))).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Meta json file was create",
            asto.exists(new Key.From(prefix, "meta.json")).join(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Returns correct package name",
            res.packageName(), new IsEqual<>("@hello/simple-npm-project")
        );
        MatcherAssert.assertThat(
            "Returns correct package version",
            res.packageVersion(), new IsEqual<>("1.0.1")
        );
        MatcherAssert.assertThat(
            "Returns correct package version",
            res.tarSize(), new IsEqual<>(306L)
        );
    }

}
