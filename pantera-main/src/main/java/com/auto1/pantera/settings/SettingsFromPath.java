/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.VertxMain;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.misc.JavaResource;
import com.auto1.pantera.scheduling.QuartzService;
import com.auto1.pantera.http.log.EcsLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Obtain artipie settings by path.
 * @since 0.22
 */
public final class SettingsFromPath {

    /**
     * Path to find setting by.
     */
    private final Path path;

    /**
     * Ctor.
     * @param path Path to find setting by
     */
    public SettingsFromPath(final Path path) {
        this.path = path;
    }

    /**
     * Searches settings by the provided path, if no settings are found,
     * example settings are used.
     * @param quartz Quartz service
     * @return Pantera settings
     * @throws IOException On IO error
     */
    public Settings find(final QuartzService quartz) throws IOException {
        return this.find(quartz, java.util.Optional.empty());
    }

    /**
     * Searches settings by the provided path, reusing a pre-created DataSource.
     * @param quartz Quartz service
     * @param dataSource Shared DataSource to avoid duplicate connection pools
     * @return Pantera settings
     * @throws IOException On IO error
     * @since 1.20.13
     */
    public Settings find(final QuartzService quartz,
        final java.util.Optional<javax.sql.DataSource> dataSource) throws IOException {
        boolean initialize = Boolean.parseBoolean(System.getenv("ARTIPIE_INIT"));
        if (!Files.exists(this.path)) {
            new JavaResource("example/artipie.yaml").copy(this.path);
            initialize = true;
        }
        final Settings settings = new YamlSettings(
            Yaml.createYamlInput(this.path.toFile()).readYamlMapping(),
            this.path.getParent(), quartz, dataSource
        );
        final BlockingStorage bsto = new BlockingStorage(settings.configStorage());
        final Key init = new Key.From(".artipie", "initialized");
        if (initialize && !bsto.exists(init)) {
            SettingsFromPath.copyResources(
                Arrays.asList(
                    AliasSettings.FILE_NAME, "my-bin.yaml", "my-docker.yaml", "my-maven.yaml"
                ), "repo", bsto
            );
            if (settings.authz().policyStorage().isPresent()) {
                final BlockingStorage policy = new BlockingStorage(
                    settings.authz().policyStorage().get()
                );
                SettingsFromPath.copyResources(
                    Arrays.asList(
                        "roles/reader.yml", "roles/default/github.yml", "roles/api-admin.yaml",
                        "users/artipie.yaml"
                    ), "security", policy
                );
            }
            bsto.save(init, "true".getBytes());
            EcsLogger.info("com.auto1.pantera.settings")
                .message(String.join(
                    "\n",
                    "", "", "\t+===============================================================+",
                    "\t\t\t\t\tHello!",
                    "\t\tPantera configuration was not found, created default.",
                    "\t\t\tDefault username/password: `artipie`/`artipie`. ",
                    "\t-===============================================================-", ""
                ))
                .eventCategory("configuration")
                .eventAction("default_config_create")
                .eventOutcome("success")
                .log();
        }
        return settings;
    }

    /**
     * Copies given resources list from given directory to the blocking storage.
     * @param resources What to copy
     * @param dir Example resources directory
     * @param bsto Where to copy
     * @throws IOException On error
     */
    private static void copyResources(
        final List<String> resources, final String dir, final BlockingStorage bsto
    ) throws IOException {
        for (final String res : resources) {
            final Path tmp = Files.createTempFile(
                Path.of(res).getFileName().toString(), ".tmp"
            );
            tmp.toFile().deleteOnExit();
            new JavaResource(String.format("example/%s/%s", dir, res)).copy(tmp);
            bsto.save(new Key.From(res), Files.readAllBytes(tmp));
            Files.delete(tmp);
        }
    }
}
