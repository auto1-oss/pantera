/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.settings.repo;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class RepoConfigWatcherTest {

    @Test
    void triggersOnContentChange() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From("alpha.yaml"), new Content.From("repo:\n  type: maven".getBytes())).join();
        final AtomicInteger counter = new AtomicInteger();
        final RepoConfigWatcher watcher = new RepoConfigWatcher(storage, Duration.ofMillis(1), counter::incrementAndGet);
        watcher.runOnce().join();
        storage.save(new Key.From("alpha.yaml"), new Content.From("repo:\n  type: npm".getBytes())).join();
        watcher.runOnce().join();
        Assertions.assertEquals(1, counter.get());
        watcher.close();
    }

    @Test
    void detectsDeletion() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From("beta.yaml"), new Content.From("repo:\n  type: maven".getBytes())).join();
        final AtomicInteger counter = new AtomicInteger();
        final RepoConfigWatcher watcher = new RepoConfigWatcher(storage, Duration.ofMillis(1), counter::incrementAndGet);
        watcher.runOnce().join();
        storage.delete(new Key.From("beta.yaml")).join();
        watcher.runOnce().join();
        Assertions.assertEquals(1, counter.get());
        watcher.close();
    }
}
