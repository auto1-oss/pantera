/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.third.party.factory.first2;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.factory.PanteraStorageFactory;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StorageFactory;
import com.auto1.pantera.asto.memory.InMemoryStorage;

/**
 * Test storage factory.
 *
 * @since 1.13.0
 */
@PanteraStorageFactory("test-first")
public final class TestFirst2StorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final Config cfg) {
        return new InMemoryStorage();
    }
}
