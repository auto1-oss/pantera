/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.third.party.factory.first;

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
public final class TestFirstStorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final Config cfg) {
        return new InMemoryStorage();
    }
}
