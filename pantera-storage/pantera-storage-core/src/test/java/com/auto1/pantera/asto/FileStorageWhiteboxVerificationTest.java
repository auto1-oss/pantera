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
package com.auto1.pantera.asto;

import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.test.StorageWhiteboxVerification;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

/**
 * File storage verification test.
 *
 * @since 1.14.0
 */
public final class FileStorageWhiteboxVerificationTest extends StorageWhiteboxVerification {

    /**
     * Temp test dir.
     */
    @TempDir
    private Path temp;

    @Override
    protected Storage newStorage() {
        return new FileStorage(this.temp.resolve("base"));
    }

    @Override
    protected Optional<Storage> newBaseForRootSubStorage() {
        return Optional.of(new FileStorage(this.temp.resolve("root-sub-storage")));
    }

    @Override
    protected Optional<Storage> newBaseForSubStorage() throws Exception {
        return Optional.of(new FileStorage(this.temp.resolve("sub-storage")));
    }
}
