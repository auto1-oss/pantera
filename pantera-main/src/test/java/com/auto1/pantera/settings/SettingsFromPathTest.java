/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.scheduling.QuartzService;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link SettingsFromPath}.
 * @since 0.22
 */
class SettingsFromPathTest {

    @Test
    void createsSettings(final @TempDir Path temp) throws IOException {
        final Path stng = temp.resolve("pantera.yaml");
        Files.write(
            Yaml.createYamlMappingBuilder().add(
                "meta",
                Yaml.createYamlMappingBuilder().add(
                    "storage",
                    Yaml.createYamlMappingBuilder().add("type", "fs")
                        .add("path", temp.resolve("repo").toString()).build()
                ).build()
            ).build().toString().getBytes(),
            stng.toFile()
        );
        final Settings settings = new SettingsFromPath(stng).find(new QuartzService());
        MatcherAssert.assertThat(
            settings,
            new IsInstanceOf(YamlSettings.class)
        );
    }
}
