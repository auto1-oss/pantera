/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.memory;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ValueNotFoundException;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BenchmarkStorage#size(Key)}.
 * @since 1.2.0
 */
@SuppressWarnings("deprecation")
final class BenchmarkStorageSizeTest {
    @Test
    void returnsSizeWhenPresentInLocalAndNotDeleted() {
        final byte[] data = "example data".getBytes();
        final InMemoryStorage memory = new InMemoryStorage();
        final BenchmarkStorage bench = new BenchmarkStorage(memory);
        final Key key = new Key.From("someLocalKey");
        bench.save(key, new Content.From(data)).join();
        MatcherAssert.assertThat(
            bench.size(key).join(),
            new IsEqual<>((long) data.length)
        );
    }

    @Test
    void returnsSizeWhenPresentInBackendAndNotDeleted() {
        final byte[] data = "super data".getBytes();
        final Key key = new Key.From("someBackendKey");
        final NavigableMap<String, byte[]> backdata = new TreeMap<>();
        backdata.put(key.string(), data);
        final InMemoryStorage memory = new InMemoryStorage(backdata);
        final BenchmarkStorage bench = new BenchmarkStorage(memory);
        MatcherAssert.assertThat(
            bench.size(key).join(),
            new IsEqual<>((long) data.length)
        );
    }

    @Test
    void throwsIfKeyWasDeleted() {
        final InMemoryStorage memory = new InMemoryStorage();
        final BenchmarkStorage bench = new BenchmarkStorage(memory);
        final Key key = new Key.From("somekey");
        bench.save(key, new Content.From("will be deleted".getBytes())).join();
        bench.delete(key).join();
        final Throwable thr = Assertions.assertThrows(
            CompletionException.class,
            () -> bench.size(key).join()
        );
        MatcherAssert.assertThat(
            thr.getCause(),
            new IsInstanceOf(ValueNotFoundException.class)
        );
    }
}
