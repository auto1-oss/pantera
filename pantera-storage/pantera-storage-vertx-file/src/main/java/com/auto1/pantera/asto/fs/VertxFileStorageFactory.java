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
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.factory.PanteraStorageFactory;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StorageFactory;
import io.vertx.reactivex.core.Vertx;
import java.nio.file.Paths;

/**
 * File storage factory.
 *
 * @since 0.1
 */
@PanteraStorageFactory("vertx-file")
public final class VertxFileStorageFactory implements StorageFactory {
    @Override
    public Storage newStorage(final Config cfg) {
        return new VertxFileStorage(
            Paths.get(new Config.StrictStorageConfig(cfg).string("path")),
            Vertx.vertx()
        );
    }
}
