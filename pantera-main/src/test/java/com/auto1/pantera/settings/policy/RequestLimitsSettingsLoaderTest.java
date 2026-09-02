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
package com.auto1.pantera.settings.policy;

import java.util.Map;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Assertions;

/**
 * {@link RequestLimitsSettingsLoader}: DB row, then environment, then the
 * documented default, for the request-body cap and the fs storage roots.
 *
 * @since 2.2.9
 */
final class RequestLimitsSettingsLoaderTest {

    @AfterEach
    void tearDown() {
        RequestLimitsSettingsLoader.uninstall();
    }

    @Test
    void activeSupplierWithoutInstallReturnsDefaults() {
        final RequestLimitsConfig resolved = RequestLimitsSettingsLoader.activeSupplier().get();
        MatcherAssert.assertThat(
            "default cap is 10 GiB",
            resolved.maxRequestBodyBytes(), new IsEqual<>(10L * 1024L * 1024L * 1024L)
        );
        final String expectedRoots = java.util.Optional
            .ofNullable(System.getProperty("pantera.fs.storage.roots"))
            .filter(val -> !val.isBlank())
            .or(() -> java.util.Optional.ofNullable(System.getenv("PANTERA_FS_STORAGE_ROOTS")))
            .orElse("/var/pantera/data");
        MatcherAssert.assertThat(
            "roots honour the property/env tiers even before install (API test fixtures rely on it)",
            resolved.fsStorageRoots(), new IsEqual<>(expectedRoots)
        );
    }

    @Test
    void dbRowWinsOverEnvironment() {
        final RequestLimitsSettingsLoader loader = new RequestLimitsSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "max_request_body_bytes", "2097152",
                "fs_storage_roots", "/srv/a:/srv/b"
            )),
            Map.of(
                "PANTERA_MAX_REQUEST_BODY_BYTES", "4194304",
                "PANTERA_FS_STORAGE_ROOTS", "/env/root"
            )::get
        );
        MatcherAssert.assertThat(
            "cap from the database row", loader.get().maxRequestBodyBytes(), new IsEqual<>(2_097_152L)
        );
        MatcherAssert.assertThat(
            "roots from the database row", loader.get().fsStorageRoots(), new IsEqual<>("/srv/a:/srv/b")
        );
        MatcherAssert.assertThat(
            "parsed policy honours the database roots",
            loader.get().fsRootPolicy().reject("/srv/b/npm").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void envTierWinsOverDefaultWhenNoRow() {
        final RequestLimitsSettingsLoader loader = new RequestLimitsSettingsLoader(
            FakeAuthSettings.empty(),
            Map.of(
                "PANTERA_MAX_REQUEST_BODY_BYTES", "4194304",
                "PANTERA_FS_STORAGE_ROOTS", "/env/root"
            )::get
        );
        MatcherAssert.assertThat(
            "cap from the environment", loader.get().maxRequestBodyBytes(), new IsEqual<>(4_194_304L)
        );
        MatcherAssert.assertThat(
            "roots from the environment", loader.get().fsStorageRoots(), new IsEqual<>("/env/root")
        );
    }

    @Test
    void invalidDbValueFallsBackToDefaults() {
        final RequestLimitsSettingsLoader loader = new RequestLimitsSettingsLoader(
            FakeAuthSettings.withRows(Map.of("max_request_body_bytes", "12")), Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "a cap below the 1 MiB floor cannot be loaded; defaults apply",
            loader.get().maxRequestBodyBytes(), new IsEqual<>(RequestLimitsConfig.defaults().maxRequestBodyBytes())
        );
    }

    @Test
    void installedSupplierIsInvalidatable() {
        RequestLimitsSettingsLoader.install(null, Map.<String, String>of()::get);
        MatcherAssert.assertThat(
            "null DAO resolves defaults",
            RequestLimitsSettingsLoader.maxRequestBodyBytes().getAsLong(),
            new IsEqual<>(RequestLimitsConfig.defaults().maxRequestBodyBytes())
        );
        RequestLimitsSettingsLoader.installed().invalidate();
        MatcherAssert.assertThat(
            "invalidate reloads without throwing",
            RequestLimitsSettingsLoader.fsRootPolicy().get().reject("/var/pantera/data/x").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void configRejectsRelativeRootsAndTinyCaps() {
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new RequestLimitsConfig(1L << 20, "data/relative"));
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new RequestLimitsConfig(1L << 20, " "));
        Assertions.assertThrows(IllegalArgumentException.class,
            (Executable) () -> new RequestLimitsConfig((1L << 20) - 1, "/var/pantera/data"));
    }


    @Test
    void invalidMaxBodyKeepsConfiguredFsRoots() {
        // One bad key must not discard the other keys: an operator typo in
        // the body cap must never silently reset the fs-roots allowlist.
        final RequestLimitsSettingsLoader loader = new RequestLimitsSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "max_request_body_bytes", "12",
                "fs_storage_roots", "/srv/keep"
            )),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "the invalid cap falls back to its own default",
            loader.get().maxRequestBodyBytes(),
            new IsEqual<>(RequestLimitsConfig.DEFAULT_MAX_REQUEST_BODY_BYTES)
        );
        MatcherAssert.assertThat(
            "the valid fs roots are KEPT despite the invalid sibling key",
            loader.get().fsStorageRoots(), new IsEqual<>("/srv/keep")
        );
    }

    @Test
    void invalidFsRootsKeepsConfiguredMaxBody() {
        final RequestLimitsSettingsLoader loader = new RequestLimitsSettingsLoader(
            FakeAuthSettings.withRows(Map.of(
                "max_request_body_bytes", "2097152",
                "fs_storage_roots", "relative/dir"
            )),
            Map.<String, String>of()::get
        );
        MatcherAssert.assertThat(
            "the valid cap is KEPT despite the invalid sibling key",
            loader.get().maxRequestBodyBytes(), new IsEqual<>(2_097_152L)
        );
        MatcherAssert.assertThat(
            "the invalid roots fall back to their own default",
            loader.get().fsStorageRoots(),
            new IsEqual<>(com.auto1.pantera.settings.repo.FsStorageRootPolicy.DEFAULT)
        );
    }
}
