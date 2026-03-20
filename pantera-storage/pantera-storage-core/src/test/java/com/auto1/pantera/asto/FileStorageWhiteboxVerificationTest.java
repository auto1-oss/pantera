/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
@SuppressWarnings("PMD.TestClassWithoutTestCases")
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
