/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.cooldown.CooldownDependency;
import com.auto1.pantera.npm.proxy.NpmRemote;
import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.reactivex.Maybe;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

final class NpmCooldownInspectorTest {

    @Test
    void resolvesReleaseDateAndDependencies() {
        final FakeRemote remote = new FakeRemote();
        final NpmCooldownInspector inspector = new NpmCooldownInspector(remote);
        final Optional<Instant> release = inspector.releaseDate("main", "1.0.0").join();
        MatcherAssert.assertThat(release.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat(release.get(), Matchers.is(Instant.parse("2024-01-01T00:00:00Z")));
        final List<CooldownDependency> deps = inspector.dependencies("main", "1.0.0").join();
        MatcherAssert.assertThat(deps, Matchers.hasSize(2));
        MatcherAssert.assertThat(
            deps.stream().anyMatch(dep ->
                "dep-a".equals(dep.artifact()) && "2.1.0".equals(dep.version())
            ), Matchers.is(true)
        );
        MatcherAssert.assertThat(
            deps.stream().anyMatch(dep ->
                "dep-b".equals(dep.artifact()) && "1.1.0".equals(dep.version())
            ), Matchers.is(true)
        );
    }

    private static final class FakeRemote implements NpmRemote {

        @Override
        public Maybe<NpmPackage> loadPackage(final String name) {
            if ("main".equals(name)) {
                return Maybe.just(
                    new NpmPackage(
                        name,
                        "{\"time\":{\"1.0.0\":\"2024-01-01T00:00:00Z\"}," +
                            "\"versions\":{\"1.0.0\":{\"dependencies\":{\"dep-a\":\"^2.0.0\"}," +
                            "\"optionalDependencies\":{\"dep-b\":\"1.1.0\"}}}}",
                        "Mon, 01 Jan 2024 00:00:00 GMT",
                        Instant.now().atOffset(java.time.ZoneOffset.UTC)
                    )
                );
            }
            if ("dep-a".equals(name)) {
                return Maybe.just(
                    new NpmPackage(
                        name,
                        "{\"versions\":{\"2.0.0\":{},\"2.1.0\":{},\"3.0.0\":{}}}",
                        "Mon, 01 Jan 2024 00:00:00 GMT",
                        Instant.now().atOffset(java.time.ZoneOffset.UTC)
                    )
                );
            }
            if ("dep-b".equals(name)) {
                return Maybe.just(
                    new NpmPackage(
                        name,
                        "{\"versions\":{\"1.1.0\":{}}}",
                        "Mon, 01 Jan 2024 00:00:00 GMT",
                        Instant.now().atOffset(java.time.ZoneOffset.UTC)
                    )
                );
            }
            return Maybe.empty();
        }

        @Override
        public Maybe<NpmAsset> loadAsset(final String path, final Path tmp) {
            return Maybe.empty();
        }

        @Override
        public void close() throws IOException {
            // no-op
        }
    }
}
