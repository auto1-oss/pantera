/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */

package com.auto1.pantera.bench;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.memory.BenchmarkStorage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.rpm.Rpm;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Observable;
import io.reactivex.Single;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
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
 * Benchmark for {@link RPM}.
 * @since 1.4
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 20)
public class RpmBench {

    /**
     * Benchmark directory.
     */
    private static final String BENCH_DIR = System.getenv("BENCH_DIR");

    /**
     * Repository source storage.
     */
    private InMemoryStorage readonly;

    @Setup
    public void setup() {
        if (RpmBench.BENCH_DIR == null) {
            throw new IllegalStateException("BENCH_DIR environment variable must be set");
        }
        this.readonly = new InMemoryStorage();
        final Storage src = new FileStorage(Paths.get(RpmBench.BENCH_DIR));
        final BlockingStorage bsto = new BlockingStorage(src);
        bsto.list(new Key.From("repodata")).forEach(key -> bsto.delete(key));
        RpmBench.sync(src, this.readonly);
    }

    @Benchmark
    public void run(final Blackhole bhl) {
        new Rpm(new BenchmarkStorage(this.readonly))
            .batchUpdate(Key.ROOT)
            .to(CompletableInterop.await())
            .toCompletableFuture().join();
    }

    /**
     * Main.
     * @param args CLI args
     * @throws RunnerException On benchmark failure
     */
    public static void main(final String... args) throws RunnerException {
        new Runner(
            new OptionsBuilder()
                .include(RpmBench.class.getSimpleName())
                .forks(1)
                .build()
        ).run();
    }

    /**
     * Sync storages.
     * @param src Source storage
     * @param dst Destination storage
     */
    private static void sync(final Storage src, final Storage dst) {
        com.auto1.pantera.asto.rx.RxFuture.single(src.list(Key.ROOT))
            .flatMapObservable(Observable::fromIterable)
            .flatMapSingle(
                key -> com.auto1.pantera.asto.rx.RxFuture.single(
                    src.value(key)
                        .thenCompose(content -> dst.save(key, content))
                        .thenApply(none -> true)
                )
            ).toList().map(ignore -> true).to(SingleInterop.get())
                .toCompletableFuture().join();
    }
}
