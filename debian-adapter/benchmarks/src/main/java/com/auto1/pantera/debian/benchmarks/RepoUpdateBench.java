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
package com.auto1.pantera.debian.benchmarks;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.BenchmarkStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.misc.UncheckedIOScalar;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.Debian;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Benchmark for {@link com.auto1.pantera.debian.Debian.Asto}.
 * @since 0.8
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 20)
public class RepoUpdateBench {

    /**
     * Benchmark directory.
     */
    private static final String BENCH_DIR = System.getenv("BENCH_DIR");

    /**
     * Repository source storage.
     */
    private InMemoryStorage readonly;

    /**
     * Deb packages list.
     */
    private List<Key> debs;

    /**
     * Count from unique names of Packages index.
     */
    private AtomicInteger count;

    @Setup
    public void setup() throws IOException {
        if (RepoUpdateBench.BENCH_DIR == null) {
            throw new IllegalStateException("BENCH_DIR environment variable must be set");
        }
        this.readonly = new InMemoryStorage();
        this.count = new AtomicInteger(0);
        try (Stream<Path> files = Files.list(Paths.get(RepoUpdateBench.BENCH_DIR))) {
            this.debs = new ArrayList<>(150);
            files.forEach(
                item -> {
                    final Key key = new Key.From(item.getFileName().toString());
                    this.readonly.save(
                        key,
                        new Content.From(
                            new UncheckedIOScalar<>(() -> Files.readAllBytes(item)).value()
                        )
                    ).join();
                    if (key.string().endsWith(".deb")) {
                        this.debs.add(key);
                    }
                }
            );
        }
    }

    @Benchmark
    public void run(final Blackhole bhl) {
        final Debian deb = new Debian.Asto(
            new BenchmarkStorage(this.readonly),
            new Config.FromYaml(
                "my-deb",
                Yaml.createYamlMappingBuilder().add("Architectures", "amd64")
                    .add("Components", "main").build(),
                new InMemoryStorage()
            )
        );
        deb.updatePackages(
            this.debs,
            new Key.From(String.format("Packages-%s.gz", this.count.incrementAndGet()))
        ).toCompletableFuture().join();
        deb.generateRelease().toCompletableFuture().join();
    }

    /**
     * Main.
     * @param args CLI args
     * @throws RunnerException On benchmark failure
     */
    public static void main(final String... args) throws RunnerException {
        new Runner(
            new OptionsBuilder()
                .include(RepoUpdateBench.class.getSimpleName())
                .forks(1)
                .build()
        ).run();
    }

}
