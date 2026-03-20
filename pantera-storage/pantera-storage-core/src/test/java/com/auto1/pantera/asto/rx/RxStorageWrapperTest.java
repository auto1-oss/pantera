/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.rx;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.asto.ext.ContentAs;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Single;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tests for {@link RxStorageWrapper}.
 *
 * @since 1.11
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class RxStorageWrapperTest {

    /**
     * Original storage.
     */
    private Storage original;

    /**
     * Reactive wrapper of original storage.
     */
    private RxStorageWrapper wrapper;

    @BeforeEach
    void setUp() {
        this.original = new InMemoryStorage();
        this.wrapper = new RxStorageWrapper(this.original);
    }

    @Test
    void checksExistence() {
        final Key key = new Key.From("a");
        this.original.save(key, Content.EMPTY).join();
        MatcherAssert.assertThat(
            this.wrapper.exists(key).blockingGet(),
            new IsEqual<>(true)
        );
    }

    @Test
    void listsItemsByPrefix() {
        this.original.save(new Key.From("a/b/c"), Content.EMPTY).join();
        this.original.save(new Key.From("a/d"), Content.EMPTY).join();
        this.original.save(new Key.From("z"), Content.EMPTY).join();
        final Collection<String> keys = this.wrapper.list(new Key.From("a"))
            .blockingGet()
            .stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            keys,
            new IsEqual<>(Arrays.asList("a/b/c", "a/d"))
        );
    }

    @Test
    void savesItems() {
        this.wrapper.save(
            new Key.From("foo/file1"), Content.EMPTY
        ).blockingAwait();
        this.wrapper.save(
            new Key.From("file2"), Content.EMPTY
        ).blockingAwait();
        final Collection<String> keys = this.original.list(Key.ROOT)
            .join()
            .stream()
            .map(Key::string)
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            keys,
            new IsEqual<>(Arrays.asList("file2", "foo/file1"))
        );
    }

    @Test
    void movesItems() {
        final Key source = new Key.From("foo/file1");
        final Key destination = new Key.From("bla/file2");
        final byte[] bvalue = "my file1 content"
            .getBytes(StandardCharsets.UTF_8);
        this.original.save(
            source, new Content.From(bvalue)
        ).join();
        this.original.save(
            destination, Content.EMPTY
        ).join();
        this.wrapper.move(source, destination).blockingAwait();
        MatcherAssert.assertThat(
            new BlockingStorage(this.original)
                .value(destination),
            new IsEqual<>(bvalue)
        );
    }

    @Test
    @SuppressWarnings("deprecation")
    void readsSize() {
        final Key key = new Key.From("file.txt");
        final String text = "my file content";
        this.original.save(
            key,
            new Content.From(
                text.getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        MatcherAssert.assertThat(
            this.wrapper.size(key).blockingGet(),
            new IsEqual<>((long) text.length())
        );
    }

    @Test
    void readsValue() {
        final Key key = new Key.From("a/z");
        final byte[] bvalue = "value to read"
            .getBytes(StandardCharsets.UTF_8);
        this.original.save(
            key, new Content.From(bvalue)
        ).join();
        MatcherAssert.assertThat(
            new Remaining(
                new Concatenation(
                    this.wrapper.value(key).blockingGet()
                ).single()
                .blockingGet(),
                true
            ).bytes(),
            new IsEqual<>(bvalue)
        );
    }

    @Test
    void deletesItem() throws Exception {
        final Key key = new Key.From("key_to_delete");
        this.original.save(key, Content.EMPTY).join();
        this.wrapper.delete(key).blockingAwait();
        MatcherAssert.assertThat(
            this.original.exists(key).get(),
            new IsEqual<>(false)
        );
    }

    @Test
    void runsExclusively() {
        final Key key = new Key.From("exclusively_key");
        final Function<RxStorage, Single<Integer>> operation = sto -> Single.just(1);
        this.wrapper.exclusively(key, operation).blockingGet();
        MatcherAssert.assertThat(
            this.wrapper.exclusively(key, operation).blockingGet(),
            new IsEqual<>(1)
        );
    }

    @Test
    void testSchedulingRxStorageWrapperS3() {
        final Key key = new Key.From("test.txt");
        final String data = "five\tsix eight";
        final Executor executor = Executors.newSingleThreadExecutor();
        final RxStorageWrapper rxsto = new RxStorageWrapper(this.original);
        rxsto.save(key, new Content.From(data.getBytes(StandardCharsets.US_ASCII))).blockingAwait();
        final String result = this.original.value(key).thenApplyAsync(content -> {
            final String res = content.asString();
            MatcherAssert.assertThat("Values must match", res.equals(data));
            return rxsto.value(key).to(ContentAs.STRING).to(SingleInterop.get()).thenApply(s -> s).toCompletableFuture().join();
        }, executor).toCompletableFuture().join();
        MatcherAssert.assertThat("Values must match", result.equals(data));
    }

    /**
     * Test high concurrency scenario to verify no backpressure violations occur.
     * This is a regression test for the observeOn() bug that caused:
     * - MissingBackpressureException: Queue is full?!
     * - Connection resets under concurrent load
     * - Resource exhaustion (OOM kills)
     *
     * The bug was: adding .observeOn(Schedulers.io()) to all RxStorageWrapper methods
     * caused backpressure violations when the observeOn buffer (128 items) overflowed
     * under high concurrency.
     */
    @Test
    void highConcurrencyWithoutBackpressureViolations() throws Exception {
        final int concurrentOperations = 200; // More than observeOn buffer size (128)
        final int fileSize = 1024 * 100; // 100KB per file

        // Create test data
        final byte[] testData = new byte[fileSize];
        Arrays.fill(testData, (byte) 42);

        // Execute many concurrent save operations
        final java.util.List<io.reactivex.Completable> saveOps = new java.util.ArrayList<>();
        for (int i = 0; i < concurrentOperations; i++) {
            final Key key = new Key.From("concurrent", "file-" + i + ".dat");
            saveOps.add(this.wrapper.save(key, new Content.From(testData)));
        }

        // Wait for all saves to complete - should NOT throw MissingBackpressureException
        io.reactivex.Completable.merge(saveOps).blockingAwait();

        // Verify all files were saved correctly
        final java.util.List<io.reactivex.Single<Boolean>> existsOps = new java.util.ArrayList<>();
        for (int i = 0; i < concurrentOperations; i++) {
            final Key key = new Key.From("concurrent", "file-" + i + ".dat");
            existsOps.add(this.wrapper.exists(key));
        }

        // All files should exist
        final java.util.List<Boolean> results = io.reactivex.Single.merge(existsOps)
            .toList()
            .blockingGet();

        MatcherAssert.assertThat(
            "All concurrent operations should succeed",
            results.stream().allMatch(exists -> exists),
            new IsEqual<>(true)
        );

        // Execute many concurrent read operations
        final java.util.List<io.reactivex.Single<Content>> readOps = new java.util.ArrayList<>();
        for (int i = 0; i < concurrentOperations; i++) {
            final Key key = new Key.From("concurrent", "file-" + i + ".dat");
            readOps.add(this.wrapper.value(key));
        }

        // Wait for all reads to complete - should NOT throw MissingBackpressureException
        final java.util.List<Content> contents = io.reactivex.Single.merge(readOps)
            .toList()
            .blockingGet();

        MatcherAssert.assertThat(
            "All files should be readable",
            contents.size(),
            new IsEqual<>(concurrentOperations)
        );
    }

    /**
     * Test that RxStorageWrapper handles rapid sequential operations without issues.
     * This verifies that removing observeOn() doesn't break normal operation.
     */
    @Test
    void rapidSequentialOperationsWork() {
        final int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            final Key key = new Key.From("rapid", "item-" + i);
            final String data = "data-" + i;

            // Save
            this.wrapper.save(key, new Content.From(data.getBytes(StandardCharsets.UTF_8)))
                .blockingAwait();

            // Verify exists
            MatcherAssert.assertThat(
                this.wrapper.exists(key).blockingGet(),
                new IsEqual<>(true)
            );

            // Read back
            final String readData = this.wrapper.value(key)
                .to(ContentAs.STRING)
                .to(SingleInterop.get())
                .toCompletableFuture()
                .join();

            MatcherAssert.assertThat(readData, new IsEqual<>(data));
        }
    }
}
