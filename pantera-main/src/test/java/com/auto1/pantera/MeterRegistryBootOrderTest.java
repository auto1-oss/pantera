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
package com.auto1.pantera;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.settings.MetricsContext;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.micrometer.backends.BackendRegistries;
import io.vertx.reactivex.core.Vertx;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The meter registry is configured before Vert.x starts. Vert.x registers
 * its pool and JVM meters while it boots; a common tag or a filter added
 * afterwards does not reach a meter that already exists, which Micrometer
 * reported on every boot ("A MeterFilter is being configured after a Meter
 * has been registered") and which left those meters without the
 * {@code job} tag every dashboard filters on.
 *
 * @since 2.2.14
 */
@Timeout(60)
final class MeterRegistryBootOrderTest {

    private Vertx vertx;

    @AfterEach
    void tearDown() {
        if (this.vertx != null) {
            // Wait: an asynchronous close would unregister the registry of
            // the next test's instance.
            this.vertx.rxClose().blockingAwait();
        }
        BackendRegistries.stop("default");
    }

    @Test
    void everyMeterRegisteredAtBootCarriesTheCommonTag() {
        this.vertx = VertxMain.vertx(MeterRegistryBootOrderTest.metrics());
        final MeterRegistry registry = BackendRegistries.getDefaultNow();
        final List<String> untagged = registry.getMeters().stream()
            .map(Meter::getId)
            .filter(id -> !"pantera".equals(id.getTag("job")))
            .map(Meter.Id::getName)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Vert.x registered meters while booting",
            registry.getMeters().isEmpty(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "none of them escaped the common tag",
            untagged, new IsEqual<>(List.of())
        );
    }

    @Test
    void scrapeRegistryIsThePrometheusRegistryWithJvmMetersBoundOnce() {
        this.vertx = VertxMain.vertx(MeterRegistryBootOrderTest.metrics());
        final MeterRegistry registry = BackendRegistries.getDefaultNow();
        MatcherAssert.assertThat(
            "the metrics endpoint scrapes a Prometheus registry",
            registry, new IsInstanceOf(PrometheusMeterRegistry.class)
        );
        final Set<String> names = registry.getMeters().stream()
            .map(meter -> meter.getId().getName())
            .collect(Collectors.toSet());
        MatcherAssert.assertThat(
            "class-loader, memory, GC, processor and thread meters are bound",
            names.containsAll(
                Set.of(
                    "jvm.classes.loaded", "jvm.memory.used", "jvm.gc.memory.allocated",
                    "system.cpu.count", "jvm.threads.live"
                )
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "each exactly once",
            registry.find("jvm.threads.live").meters().size(), new IsEqual<>(1)
        );
    }

    private static MetricsContext metrics() {
        final YamlMapping meta = Yaml.createYamlMappingBuilder().add(
            "metrics",
            Yaml.createYamlMappingBuilder()
                .add("endpoint", "/metrics/vertx")
                .add("port", "8087")
                .build()
        ).build();
        return new MetricsContext(meta);
    }
}
