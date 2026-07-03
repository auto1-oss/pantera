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
package  com.auto1.pantera.conan;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import java.util.Arrays;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for RevisionsIndexCore class.
 * @since 0.1
 */
class RevisionsIndexCoreTest {

    /**
     * Test storage.
     */
    private Storage storage;

    /**
     * Test instance.
     */
    private RevisionsIndexCore core;

    @BeforeEach
    public void setUp() {
        this.storage = new InMemoryStorage();
        this.core = new RevisionsIndexCore(this.storage);
    }

    @Test
    public void noRevdataSize() {
        final Key key = new Key.From("revisions.new");
        MatcherAssert.assertThat(
            "Revisions list size is incorrect",
            this.core.getRevisions(key).toCompletableFuture().join().size() == 0
        );
    }

    @Test
    public void emptyRevdataSize() {
        final Key key = new Key.From("revisions.new");
        this.core.addToRevdata(0, key).join();
        this.core.removeRevision(0, key).join();
        MatcherAssert.assertThat(
            "Revisions list size is incorrect",
            this.core.getRevisions(key).toCompletableFuture().join().size() == 0
        );
    }

    @Test
    public void getRevisions() {
        final Key key = new Key.From("revisions.new");
        new TestResource("conan-test/revisions.3.txt").saveTo(this.storage, key);
        final List<Integer> revs = this.core.getRevisions(key).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Revisions list contents is incorrect",
            revs.equals(Arrays.asList(1, 2, 3))
        );
    }

    @Test
    public void fillNewRevdata() {
        final Key key = new Key.From("revisions.new");
        this.core.addToRevdata(1, key).join();
        this.core.addToRevdata(2, key).join();
        this.core.addToRevdata(3, key).join();
        final List<Integer> revs = this.core.getRevisions(key).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "Revisions list size is incorrect",
            revs.size() == 3
        );
        MatcherAssert.assertThat(
            "Revisions list contents is incorrect",
            revs.equals(Arrays.asList(1, 2, 3))
        );
    }

    @Test
    public void removeFromNoRevdata() {
        final Key key = new Key.From("revisions.new");
        this.core.removeRevision(0, key).join();
        MatcherAssert.assertThat(
            "Revisions list size is incorrect",
            this.core.getRevisions(key).toCompletableFuture().join().size() == 0
        );
    }

    @Test
    public void removeFromEmptyRevdata() {
        final Key key = new Key.From("revisions.new");
        this.core.addToRevdata(0, key).join();
        this.core.removeRevision(0, key).join();
        this.core.removeRevision(0, key).join();
        MatcherAssert.assertThat(
            "Revisions list size is incorrect",
            this.core.getRevisions(key).toCompletableFuture().join().size() == 0
        );
    }

    @Test
    public void removeFromRevdata() {
        final Key key = new Key.From("revisions.new");
        this.core.addToRevdata(0, key).join();
        this.core.addToRevdata(1, key).join();
        this.core.addToRevdata(2, key).join();
        this.core.removeRevision(1, key).join();
        MatcherAssert.assertThat(
            "Revisions list size is incorrect",
            this.core.getRevisions(key).toCompletableFuture().join().size() == 2
        );
        MatcherAssert.assertThat(
            "Revisions list contents is incorrect",
            this.core.getRevisions(key).toCompletableFuture().join().equals(Arrays.asList(0, 2))
        );
    }

    @Test
    public void emptyRevValue() {
        final Key key = new Key.From("revisions.new");
        MatcherAssert.assertThat(
            "Revision value is incorrect",
            this.core.getLastRev(key).toCompletableFuture().join().equals(-1)
        );
    }

    @Test
    public void lastRevValue() {
        final Key key = new Key.From("revisions.new");
        this.core.addToRevdata(1, key).join();
        this.core.addToRevdata(3, key).join();
        this.core.addToRevdata(2, key).join();
        MatcherAssert.assertThat(
            "Revision value is incorrect",
            this.core.getLastRev(key).toCompletableFuture().join().equals(3)
        );
    }
}
