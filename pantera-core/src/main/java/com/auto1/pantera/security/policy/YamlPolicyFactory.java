/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.security.policy;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Policy factory to create {@link CachedYamlPolicy}. Yaml policy is read from storage,
 * and it's required to describe this storage in the configuration.
 * Configuration format is the following:
 *<pre>{@code
 * policy:
 *   type: local
 *   eviction_millis: 60000 # not required, default 3 min
 *   storage:
 *     type: fs
 *     path: /some/path
 *}</pre>
 * The storage itself is expected to have yaml files with permissions in the following structure:
 *<pre>{@code
 * ..
 * ├── roles
 * │   ├── java-dev.yaml
 * │   ├── admin.yaml
 * │   ├── ...
 * ├── users
 * │   ├── david.yaml
 * │   ├── jane.yaml
 * │   ├── ...
 *}</pre>
 * @since 1.2
 */
@PanteraPolicyFactory("local")
public final class YamlPolicyFactory implements PolicyFactory {

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public Policy<?> getPolicy(final Config config) {
        final Config sub = config.config("storage");
        long eviction;
        try {
            eviction = Long.parseLong(config.string("eviction_millis"));
        } catch (final Exception err) {
            eviction = 180_000L;
        }
        try {
            return new CachedYamlPolicy(
                new BlockingStorage(
                    StoragesLoader.STORAGES.newObject(
                        sub.string("type"),
                        new Config.YamlStorageConfig(
                            Yaml.createYamlInput(sub.toString()).readYamlMapping()
                        )
                    )
                ),
                eviction
            );
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }
}
