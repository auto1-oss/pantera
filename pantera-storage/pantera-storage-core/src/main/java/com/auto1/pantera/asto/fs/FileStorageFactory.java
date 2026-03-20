/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.factory.PanteraStorageFactory;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StorageFactory;
import java.nio.file.Paths;

/**
 * File storage factory.
 *
 * @since 1.13.0
 */
@PanteraStorageFactory("fs")
public final class FileStorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final Config cfg) {
        return new FileStorage(
            Paths.get(new Config.StrictStorageConfig(cfg).string("path"))
        );
    }
}
