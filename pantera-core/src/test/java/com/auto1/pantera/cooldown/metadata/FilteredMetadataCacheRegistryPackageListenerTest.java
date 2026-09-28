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
package com.auto1.pantera.cooldown.metadata;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Caches that hold cooldown-filtered bytes outside the envelope cache (a
 * Maven group's merged metadata) must hear about every package-level change
 * on every node, or a block / unblock stays invisible through them.
 *
 * @since 2.2.9
 */
final class FilteredMetadataCacheRegistryPackageListenerTest {

    private FilteredMetadataCache shared;

    private final List<String> changed = new CopyOnWriteArrayList<>();

    private final AtomicInteger allChanged = new AtomicInteger();

    private final List<String> published = new CopyOnWriteArrayList<>();

    private final AtomicInteger publishedAll = new AtomicInteger();

    /**
     * Strong reference: the registry holds listeners weakly.
     */
    private FilteredMetadataCacheRegistry.PackageListener listener;

    /**
     * Strong reference for the failing listener.
     */
    private FilteredMetadataCacheRegistry.PackageListener broken;

    @BeforeEach
    void setUp() {
        this.shared = new FilteredMetadataCache(100, Duration.ofMinutes(5), Duration.ofMinutes(5), null);
        final FilteredMetadataCacheRegistry registry = FilteredMetadataCacheRegistry.instance();
        registry.setSharedCache(this.shared);
        registry.setPackagePublisher(this.published::add, this.publishedAll::incrementAndGet);
        this.listener = new FilteredMetadataCacheRegistry.PackageListener() {
            @Override
            public void packageChanged(final String packageName) {
                FilteredMetadataCacheRegistryPackageListenerTest.this.changed.add(packageName);
            }

            @Override
            public void allChanged() {
                FilteredMetadataCacheRegistryPackageListenerTest.this.allChanged.incrementAndGet();
            }
        };
        registry.addPackageListener("test", this.listener);
    }

    @AfterEach
    void tearDown() {
        FilteredMetadataCacheRegistry.instance().setPackagePublisher(pkg -> { }, () -> { });
        FilteredMetadataCacheRegistry.instance().clear();
    }

    @Test
    void blockOrUnblockInvalidationNotifiesListenersAndPeers() {
        this.shared.invalidate("maven-proxy", "maven_proxy", "com.google.guava.guava");
        MatcherAssert.assertThat(
            "local listener told", this.changed, new IsEqual<>(List.of("com.google.guava.guava"))
        );
        MatcherAssert.assertThat(
            "peers told", this.published, new IsEqual<>(List.of("com.google.guava.guava"))
        );
    }

    @Test
    void refreshOrUploadInvalidationNotifies() {
        FilteredMetadataCacheRegistry.instance().invalidateAfterProxyRefresh("maven-proxy", "com.a.b");
        MatcherAssert.assertThat(this.changed, new IsEqual<>(List.of("com.a.b")));
    }

    @Test
    void policyChangeNotifiesEverything() {
        this.shared.clear();
        MatcherAssert.assertThat("local all-changed", this.allChanged.get(), new IsEqual<>(1));
        MatcherAssert.assertThat("peers all-changed", this.publishedAll.get(), new IsEqual<>(1));
    }

    @Test
    void repoWideInvalidationNotifiesEverything() {
        this.shared.invalidateAll("maven-proxy", "maven_proxy");
        MatcherAssert.assertThat(this.allChanged.get(), new IsEqual<>(1));
    }

    @Test
    void nonSharedCacheDoesNotNotify() {
        new FilteredMetadataCache(100, Duration.ofMinutes(5), Duration.ofMinutes(5), null)
            .invalidate("maven-proxy", "maven_proxy", "com.a.b");
        MatcherAssert.assertThat(this.changed.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void peerMessageNotifiesLocallyWithoutRepublishing() {
        FilteredMetadataCacheRegistry.instance().packageReceiver().invalidate("com.a.b");
        MatcherAssert.assertThat("local listener told", this.changed, new IsEqual<>(List.of("com.a.b")));
        MatcherAssert.assertThat("no echo to peers", this.published.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void everyLiveListenerIsNotifiedNotJustTheLastRegistered() {
        final List<String> second = new CopyOnWriteArrayList<>();
        final FilteredMetadataCacheRegistry.PackageListener other =
            new FilteredMetadataCacheRegistry.PackageListener() {
                @Override
                public void packageChanged(final String packageName) {
                    second.add(packageName);
                }

                @Override
                public void allChanged() {
                    // not exercised
                }
            };
        FilteredMetadataCacheRegistry.instance().addPackageListener("test-2", other);
        this.shared.invalidate("maven-proxy", "maven_proxy", "com.a.b");
        MatcherAssert.assertThat("first told", this.changed, new IsEqual<>(List.of("com.a.b")));
        MatcherAssert.assertThat("second told", second, new IsEqual<>(List.of("com.a.b")));
    }

    @Test
    void failingListenerDoesNotBreakTheInvalidation() {
        this.broken = new FilteredMetadataCacheRegistry.PackageListener() {
            @Override
            public void packageChanged(final String packageName) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void allChanged() {
                throw new IllegalStateException("boom");
            }
        };
        FilteredMetadataCacheRegistry.instance().addPackageListener("broken", this.broken);
        this.shared.invalidate("maven-proxy", "maven_proxy", "com.a.b");
        MatcherAssert.assertThat(
            "healthy listener still told", this.changed, new IsEqual<>(List.of("com.a.b"))
        );
    }
}
