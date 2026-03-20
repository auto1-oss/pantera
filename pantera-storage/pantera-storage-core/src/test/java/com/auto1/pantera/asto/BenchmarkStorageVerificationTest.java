/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto;

import com.auto1.pantera.asto.memory.BenchmarkStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.StorageWhiteboxVerification;
import java.util.Optional;

/**
 * Benchmark storage verification test.
 *
 * @since 1.14.0
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class BenchmarkStorageVerificationTest extends StorageWhiteboxVerification {

    @Override
    protected Storage newStorage() {
        return new BenchmarkStorage(new InMemoryStorage());
    }

    @Override
    protected Optional<Storage> newBaseForRootSubStorage() {
        return Optional.empty();
    }

    @Override
    protected Optional<Storage> newBaseForSubStorage() {
        return Optional.empty();
    }
}
