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
package com.auto1.pantera.helm;

import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The Chart.yaml file.
 *
 * @since 0.2
 */
@SuppressWarnings("unchecked")
public final class ChartYaml {

    /**
     * Mapping for fields from index.yaml file.
     */
    private final Map<String, Object> mapping;

    /**
     * Ctor.
     * @param yaml Yaml for entry of chart (one specific version)
     */
    public ChartYaml(final String yaml) {
        this(
            (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml)
        );
    }

    /**
     * Ctor.
     * @param mapfromyaml Mapping of fields for chart (one specific version)
     */
    public ChartYaml(final Map<String, Object> mapfromyaml) {
        this.mapping = mapfromyaml;
    }

    /**
     * Obtain a name of the chart.
     * @return Name of the chart.
     */
    public String name() {
        return (String) this.mapping.get("name");
    }

    /**
     * Obtain a version of the chart.
     * @return Version of the chart.
     */
    public String version() {
        return (String) this.mapping.get("version");
    }

    /**
     * Return Chart.yaml fields.
     * @return The fields.
     */
    public Map<String, Object> fields() {
        return this.mapping;
    }

    /**
     * Obtain a list of urls of the chart.
     * @return Urls of the chart.
     */
    public List<String> urls() {
        return (List<String>) this.mapping.get("urls");
    }

    @Override
    public String toString() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(this.mapping);
    }
}
