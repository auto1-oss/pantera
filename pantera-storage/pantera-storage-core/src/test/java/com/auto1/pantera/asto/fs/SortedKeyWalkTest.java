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
package com.auto1.pantera.asto.fs;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.Utf8Order;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SortedKeyWalk}: the listing of a file storage must read
 * the directories on the way to the page, never the tree.
 *
 * <p>The timeouts are no latency bounds: they turn a walk that never ends
 * into a failure.</p>
 *
 * @since 2.2.14
 */
final class SortedKeyWalkTest {

    /**
     * Storage root.
     */
    @TempDir
    private Path root;

    @Test
    @Timeout(300)
    void readsOnlyTheDirectoriesOfThePageInLargeTree() throws Exception {
        final int dirs = 100;
        final int files = 500;
        for (int dir = 0; dir < dirs; dir += 1) {
            final Path parent = this.root.resolve(String.format("repo/pkg-%03d", dir));
            Files.createDirectories(parent);
            for (int file = 0; file < files; file += 1) {
                Files.write(parent.resolve(String.format("f-%04d.bin", file)), new byte[0]);
            }
        }
        final SortedKeyWalk first = this.walk("repo");
        final ListPage one = first.page(Optional.empty(), 100);
        MatcherAssert.assertThat(
            "the first page holds the first hundred files of the first directory",
            SortedKeyWalkTest.strings(one),
            new IsEqual<>(SortedKeyWalkTest.expected(0, 0, 100))
        );
        MatcherAssert.assertThat(
            "the token is the last key of the page",
            one.next(), new IsEqual<>(Optional.of("repo/pkg-000/f-0099.bin"))
        );
        MatcherAssert.assertThat(
            "only the prefix directory and one package directory were read",
            first.opened(), new IsEqual<>(2)
        );
        final SortedKeyWalk middle = this.walk("repo");
        final ListPage two = middle.page(Optional.of("repo/pkg-041/f-0449.bin"), 100);
        MatcherAssert.assertThat(
            "a page resumed deep in the tree runs over a directory boundary",
            SortedKeyWalkTest.strings(two),
            new IsEqual<>(
                java.util.stream.Stream.concat(
                    SortedKeyWalkTest.expected(41, 450, 50).stream(),
                    SortedKeyWalkTest.expected(42, 0, 50).stream()
                ).collect(Collectors.toList())
            )
        );
        MatcherAssert.assertThat(
            "the directories before the token were not read",
            middle.opened(), new IsEqual<>(3)
        );
        final SortedKeyWalk last = this.walk("repo");
        final ListPage three = last.page(Optional.of("repo/pkg-099/f-0489.bin"), 100);
        MatcherAssert.assertThat(
            "the last page holds what is left",
            SortedKeyWalkTest.strings(three),
            new IsEqual<>(SortedKeyWalkTest.expected(99, 490, 10))
        );
        MatcherAssert.assertThat(
            "the listing is finished",
            three.next(), new IsEqual<>(Optional.empty())
        );
        MatcherAssert.assertThat(
            "only the last package directory was read below the prefix",
            last.opened(), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(300)
    void pagesLikeSortedFullListingFromAnyToken() throws Exception {
        final Random random = new Random(20_260_214L);
        final String[] names = {"a", "b", "a-b", "a.b", "ab", "a b", "a+b", "c%d", "z", "0"};
        final TreeSet<String> keys = new TreeSet<>(new Utf8Order());
        for (int idx = 0; idx < 400; idx += 1) {
            final int depth = 1 + random.nextInt(4);
            final StringBuilder key = new StringBuilder("r");
            for (int level = 0; level < depth; level += 1) {
                key.append('/').append(names[random.nextInt(names.length)]);
            }
            final Path file = this.root.resolve(key.toString());
            if (SortedKeyWalkTest.creatable(this.root, file)) {
                Files.createDirectories(file.getParent());
                Files.write(file, new byte[0]);
                keys.add(key.toString());
            }
        }
        Files.createDirectories(this.root.resolve("r/empty/also-empty"));
        final List<String> all = new ArrayList<>(keys);
        MatcherAssert.assertThat(
            "the whole listing in one page",
            SortedKeyWalkTest.strings(this.walk("r").page(Optional.empty(), 10_000)),
            new IsEqual<>(all)
        );
        final List<String> tokens = new ArrayList<>(all);
        tokens.add("r/a/");
        tokens.add("r/a.b/zzz/q");
        tokens.add("r/a-b");
        tokens.add("q/before");
        tokens.add("s/after");
        for (final String token : tokens) {
            final int limit = 1 + random.nextInt(7);
            final List<String> rest = all.stream()
                .filter(key -> new Utf8Order().compare(key, token) > 0)
                .collect(Collectors.toList());
            final ListPage page = this.walk("r").page(Optional.of(token), limit);
            MatcherAssert.assertThat(
                String.format("page of %d after '%s'", limit, token),
                SortedKeyWalkTest.strings(page),
                new IsEqual<>(rest.subList(0, Math.min(limit, rest.size())))
            );
            if (rest.size() > limit) {
                MatcherAssert.assertThat(
                    String.format("token of the page of %d after '%s'", limit, token),
                    page.next(), new IsEqual<>(Optional.of(rest.get(limit - 1)))
                );
            }
        }
    }

    @Test
    @Timeout(60)
    void skipsEmptyDirectoriesAndDoesNotEnterLinkedOnes() throws Exception {
        Files.createDirectories(this.root.resolve("repo/empty/nested"));
        Files.createDirectories(this.root.resolve("repo/full"));
        Files.write(this.root.resolve("repo/full/a.txt"), new byte[0]);
        Files.createDirectories(this.root.resolve("elsewhere"));
        Files.write(this.root.resolve("elsewhere/secret.txt"), new byte[0]);
        Files.createSymbolicLink(
            this.root.resolve("repo/linked-dir"), this.root.resolve("elsewhere")
        );
        Files.createSymbolicLink(
            this.root.resolve("repo/linked-file.txt"), this.root.resolve("elsewhere/secret.txt")
        );
        MatcherAssert.assertThat(
            SortedKeyWalkTest.strings(this.walk("repo").page(Optional.empty(), 10)),
            new IsEqual<>(List.of("repo/full/a.txt", "repo/linked-file.txt"))
        );
    }

    @Test
    @Timeout(60)
    void listsNothingForPrefixThatIsFileOrMissing() throws Exception {
        Files.write(this.root.resolve("file"), new byte[0]);
        MatcherAssert.assertThat(
            "a file is not a prefix",
            this.walk("file").page(Optional.empty(), 10),
            new IsEqual<>(new ListPage(List.of(), Optional.empty()))
        );
        MatcherAssert.assertThat(
            "a missing directory is empty",
            this.walk("missing/deeper").page(Optional.empty(), 10),
            new IsEqual<>(new ListPage(List.of(), Optional.empty()))
        );
    }

    /**
     * Walk of a prefix.
     * @param prefix Prefix key string
     * @return Walk
     */
    private SortedKeyWalk walk(final String prefix) {
        return new SortedKeyWalk(this.root.resolve(prefix), new Key.From(prefix));
    }

    /**
     * Whether a file can be created: no file stands on its parent path and
     * no directory stands in its place.
     * @param root Storage root
     * @param file File
     * @return True if it can
     * @throws IOException On error
     */
    private static boolean creatable(final Path root, final Path file) throws IOException {
        boolean free = !Files.exists(file);
        Path cur = file.getParent();
        while (free && !cur.equals(root)) {
            free = !Files.isRegularFile(cur);
            cur = cur.getParent();
        }
        return free;
    }

    /**
     * Keys of the large tree.
     * @param dir Package directory number
     * @param from First file number
     * @param count Number of files
     * @return Key strings
     */
    private static List<String> expected(final int dir, final int from, final int count) {
        final List<String> keys = new ArrayList<>(count);
        for (int file = from; file < from + count; file += 1) {
            keys.add(String.format("repo/pkg-%03d/f-%04d.bin", dir, file));
        }
        return keys;
    }

    /**
     * Key strings of a page.
     * @param page Page
     * @return Strings
     */
    private static List<String> strings(final ListPage page) {
        return page.keys().stream().map(Key::string).collect(Collectors.toList());
    }
}
