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
package com.auto1.pantera.asto.test;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.Utf8Order;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Contract of {@link Storage#listPage(Key, Optional, int)} and
 * {@link Storage#deleteBatch(java.util.Collection)}: every storage, and
 * every decorator of one, has to pass these tests.
 *
 * <p>The timeouts are no latency bounds: they turn a listing that never
 * ends into a failure.</p>
 *
 * @since 2.2.14
 */
public abstract class PagedStorageVerification {

    /**
     * Keys of a small repository tree, several levels deep, with files and
     * directories whose names differ only after a common stem.
     */
    private static final List<String> TREE = List.of(
        "repo/a.txt",
        "repo/b-c.txt",
        "repo/b.d/e.txt",
        "repo/b.txt",
        "repo/b/c.txt",
        "repo/b/d/e/f/g/deep.txt",
        "repo/b/d/e/f/h.txt",
        "repo/b/d/z.txt",
        "repo/c/1.txt",
        "repo/c/10.txt",
        "repo/c/2.txt",
        "repo/com/example/lib/1.0/lib-1.0.jar",
        "repo/com/example/lib/1.0/lib-1.0.pom",
        "repo/com/example/lib/2.0/lib-2.0.jar",
        "repo/com/example/lib/maven-metadata.xml",
        "repo/z.txt"
    );

    /**
     * Storage under test, empty.
     * @return Storage
     * @throws Exception On error
     */
    protected abstract Storage newStorage() throws Exception;

    @Test
    @Timeout(120)
    public void listPage_returnsEveryKeyOnceInOrderAcrossPages() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, PagedStorageVerification.TREE);
        final List<ListPage> pages = this.pages(storage, new Key.From("repo"), 5);
        final Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("the listing takes more than two pages", pages.size() > 2);
        actual.put(
            "no page exceeds the limit",
            pages.stream().allMatch(page -> page.keys().size() <= 5)
        );
        actual.put(
            "every key is returned exactly once, in UTF-8 order",
            PagedStorageVerification.strings(pages)
        );
        final Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("the listing takes more than two pages", true);
        expected.put("no page exceeds the limit", true);
        expected.put(
            "every key is returned exactly once, in UTF-8 order",
            PagedStorageVerification.sorted(PagedStorageVerification.TREE)
        );
        MatcherAssert.assertThat(actual, new IsEqual<>(expected));
    }

    @Test
    @Timeout(120)
    public void listPage_namesLastKeyAsTokenWhileMoreFollow() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, PagedStorageVerification.TREE);
        final ListPage first = storage.listPage(
            new Key.From("repo"), Optional.empty(), 4
        ).get(60, TimeUnit.SECONDS);
        final ListPage whole = storage.listPage(
            new Key.From("repo"), Optional.empty(), 1000
        ).get(60, TimeUnit.SECONDS);
        final List<String> order = PagedStorageVerification.sorted(PagedStorageVerification.TREE);
        final Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("the first page is full", PagedStorageVerification.strings(List.of(first)));
        actual.put("the token is the last key of the page", first.next());
        actual.put("a page that holds the whole listing has no token", whole.next());
        final Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("the first page is full", order.subList(0, 4));
        expected.put("the token is the last key of the page", Optional.of(order.get(3)));
        expected.put("a page that holds the whole listing has no token", Optional.empty());
        MatcherAssert.assertThat(actual, new IsEqual<>(expected));
    }

    @Test
    @Timeout(120)
    public void listPage_withLimitOneWalksTheWholeTree() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, PagedStorageVerification.TREE);
        MatcherAssert.assertThat(
            PagedStorageVerification.strings(this.pages(storage, new Key.From("repo"), 1)),
            new IsEqual<>(PagedStorageVerification.sorted(PagedStorageVerification.TREE))
        );
    }

    @Test
    @Timeout(120)
    public void listPage_keepsSiblingPrefixesApart() throws Exception {
        final Storage storage = this.newStorage();
        this.save(
            storage,
            List.of(
                "maven/a.jar", "maven/sub/b.jar",
                "maven-proxy/a.jar", "maven.old/a.jar", "maven2/a.jar", "mave/a.jar",
                "a/1", "a.b/1", "a-b/1", "ab/1"
            )
        );
        final Map<String, Object> actual = new LinkedHashMap<>();
        for (final String prefix : List.of("maven", "a", "a.b", "a-b")) {
            actual.put(
                prefix,
                PagedStorageVerification.strings(this.pages(storage, new Key.From(prefix), 3))
            );
        }
        final Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("maven", List.of("maven/a.jar", "maven/sub/b.jar"));
        expected.put("a", List.of("a/1"));
        expected.put("a.b", List.of("a.b/1"));
        expected.put("a-b", List.of("a-b/1"));
        MatcherAssert.assertThat(
            "each prefix lists the keys inside its own directory only",
            actual, new IsEqual<>(expected)
        );
    }

    @Test
    @Timeout(120)
    public void listPage_doesNotReturnKeyEqualToPrefix() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, List.of("exact", "dir/exact", "dir/other"));
        final List<String> listed = new ArrayList<>(
            PagedStorageVerification.strings(this.pages(storage, new Key.From("exact"), 10))
        );
        listed.addAll(
            PagedStorageVerification.strings(this.pages(storage, new Key.From("dir/exact"), 10))
        );
        MatcherAssert.assertThat(
            "neither a top-level nor a nested key is inside itself",
            listed, new IsEqual<>(Collections.emptyList())
        );
    }

    @Test
    @Timeout(120)
    public void listPage_resumesAfterReturnedKeysWereDeleted() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, PagedStorageVerification.TREE);
        final List<String> seen = new ArrayList<>(PagedStorageVerification.TREE.size());
        Optional<String> token = Optional.empty();
        int rounds = 0;
        do {
            final ListPage page = storage.listPage(new Key.From("repo"), token, 3)
                .get(60, TimeUnit.SECONDS);
            page.keys().forEach(key -> seen.add(key.string()));
            storage.deleteBatch(page.keys()).get(60, TimeUnit.SECONDS);
            token = page.next();
            rounds += 1;
        } while (token.isPresent() && rounds < 100);
        final Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("deleting each page before the next loses and repeats nothing", seen);
        actual.put(
            "nothing is left under the prefix",
            PagedStorageVerification.strings(this.pages(storage, new Key.From("repo"), 10))
        );
        final Map<String, Object> expected = new LinkedHashMap<>();
        expected.put(
            "deleting each page before the next loses and repeats nothing",
            PagedStorageVerification.sorted(PagedStorageVerification.TREE)
        );
        expected.put("nothing is left under the prefix", Collections.emptyList());
        MatcherAssert.assertThat(actual, new IsEqual<>(expected));
    }

    @Test
    @Timeout(120)
    public void listPage_resumesFromTokenOfKeyThatDoesNotExist() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, PagedStorageVerification.TREE);
        final List<String> all = PagedStorageVerification.sorted(PagedStorageVerification.TREE);
        final Map<String, Object> actual = new LinkedHashMap<>();
        final Map<String, Object> expected = new LinkedHashMap<>();
        for (final String token : List.of(
            "repo/b/d/e/f/gone.txt", "repo/b/nodir/nofile", "repo/c/15.txt", "repo/bb",
            "repo/zzz"
        )) {
            expected.put(
                token,
                all.stream()
                    .filter(key -> new Utf8Order().compare(key, token) > 0)
                    .collect(Collectors.toList())
            );
            actual.put(
                token,
                PagedStorageVerification.strings(
                    this.pages(storage, new Key.From("repo"), Optional.of(token), 4)
                )
            );
        }
        MatcherAssert.assertThat(
            "each absent token is followed by the keys greater than it",
            actual, new IsEqual<>(expected)
        );
    }

    @Test
    @Timeout(120)
    public void listPage_ofRootReturnsEverything() throws Exception {
        final Storage storage = this.newStorage();
        final List<String> keys = List.of("one.txt", "a/two.txt", "a/b/three.txt", "z/four.txt");
        this.save(storage, keys);
        MatcherAssert.assertThat(
            PagedStorageVerification.strings(this.pages(storage, Key.ROOT, 3)),
            new IsEqual<>(PagedStorageVerification.sorted(keys))
        );
    }

    @Test
    @Timeout(120)
    public void listPage_ofMissingPrefixIsEmpty() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, List.of("present/a.txt"));
        final ListPage page = storage.listPage(
            new Key.From("absent/deeper"), Optional.empty(), 10
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "neither keys nor a token",
            page, new IsEqual<>(new ListPage(Collections.emptyList(), Optional.empty()))
        );
    }

    @Test
    @Timeout(120)
    public void listPage_returnsKeysWithSpecialCharacters() throws Exception {
        final Storage storage = this.newStorage();
        final List<String> keys = List.of(
            "sp/with space.txt",
            "sp/plus+plus.txt",
            "sp/percent%20sign.txt",
            "sp/ключ/файл.txt",
            "sp/日本語.txt",
            "sp/tilde~and=equals&amp.txt"
        );
        this.save(storage, keys);
        MatcherAssert.assertThat(
            PagedStorageVerification.strings(this.pages(storage, new Key.From("sp"), 2)),
            new IsEqual<>(PagedStorageVerification.sorted(keys))
        );
    }

    @Test
    @Timeout(120)
    public void listPage_rejectsNonPositiveLimit() throws Exception {
        final Storage storage = this.newStorage();
        Throwable cause = storage.listPage(Key.ROOT, Optional.empty(), 0)
            .handle((page, err) -> err)
            .get(60, TimeUnit.SECONDS);
        while (cause != null && cause.getCause() != null
            && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        MatcherAssert.assertThat(cause, new IsInstanceOf(IllegalArgumentException.class));
    }

    @Test
    @Timeout(120)
    public void deleteBatch_succeedsForKeysThatDoNotExist() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, List.of("keep/a.txt"));
        storage.deleteBatch(
            List.of(
                new Key.From("never/was/here.txt"),
                new Key.From("keep/missing.txt"),
                new Key.From("keep/a.txt/below-a-file")
            )
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            PagedStorageVerification.strings(this.pages(storage, Key.ROOT, 10)),
            new IsEqual<>(List.of("keep/a.txt"))
        );
    }

    @Test
    @Timeout(120)
    public void deleteBatch_removesExistingAndIgnoresMissing() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, PagedStorageVerification.TREE);
        final List<Key> batch = List.of(
            new Key.From("repo/a.txt"),
            new Key.From("repo/not-there.txt"),
            new Key.From("repo/b/d/e/f/g/deep.txt"),
            new Key.From("repo/com/example/lib/1.0/lib-1.0.jar"),
            new Key.From("repo/com/example/lib/1.0/lib-1.0.pom")
        );
        storage.deleteBatch(batch).get(60, TimeUnit.SECONDS);
        final List<String> expected = new ArrayList<>(PagedStorageVerification.TREE);
        batch.forEach(key -> expected.remove(key.string()));
        final List<String> once = PagedStorageVerification.strings(
            this.pages(storage, Key.ROOT, 100)
        );
        storage.deleteBatch(batch).get(60, TimeUnit.SECONDS);
        final List<String> twice = PagedStorageVerification.strings(
            this.pages(storage, Key.ROOT, 100)
        );
        MatcherAssert.assertThat(
            "the existing keys of the batch are gone, the others stay, a repeat changes nothing",
            List.of(once, twice),
            new IsEqual<>(
                List.of(
                    PagedStorageVerification.sorted(expected),
                    PagedStorageVerification.sorted(expected)
                )
            )
        );
    }

    @Test
    @Timeout(120)
    public void deleteBatch_ofNothingSucceeds() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, List.of("keep/a.txt"));
        storage.deleteBatch(Collections.emptyList()).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            storage.exists(new Key.From("keep/a.txt")).get(60, TimeUnit.SECONDS),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(120)
    public void abortIncompleteUploads_succeedsOnStorageWithoutUploads() throws Exception {
        final Storage storage = this.newStorage();
        this.save(storage, List.of("repo/a.txt"));
        MatcherAssert.assertThat(
            storage.abortIncompleteUploads(new Key.From("repo")).get(60, TimeUnit.SECONDS),
            new IsEqual<>(0)
        );
    }

    /**
     * Save a small value under each key.
     * @param storage Storage
     * @param keys Key strings
     * @throws Exception On error
     */
    protected final void save(final Storage storage, final List<String> keys) throws Exception {
        for (final String key : keys) {
            storage.save(
                new Key.From(key),
                new Content.From(key.getBytes(StandardCharsets.UTF_8))
            ).get(60, TimeUnit.SECONDS);
        }
    }

    /**
     * All pages of a listing.
     * @param storage Storage
     * @param prefix Path prefix
     * @param limit Page size
     * @return Pages
     * @throws Exception On error
     */
    protected final List<ListPage> pages(
        final Storage storage, final Key prefix, final int limit
    ) throws Exception {
        return this.pages(storage, prefix, Optional.empty(), limit);
    }

    /**
     * The pages of a listing from a token on.
     * @param storage Storage
     * @param prefix Path prefix
     * @param from Token to start after
     * @param limit Page size
     * @return Pages
     * @throws Exception On error
     */
    protected final List<ListPage> pages(
        final Storage storage, final Key prefix, final Optional<String> from, final int limit
    ) throws Exception {
        final List<ListPage> pages = new ArrayList<>(8);
        Optional<String> token = from;
        do {
            final ListPage page = storage.listPage(prefix, token, limit)
                .get(60, TimeUnit.SECONDS);
            pages.add(page);
            token = page.next();
        } while (token.isPresent() && pages.size() < 10_000);
        return pages;
    }

    /**
     * Key strings of pages, in the order returned.
     * @param pages Pages
     * @return Key strings
     */
    private static List<String> strings(final List<ListPage> pages) {
        return pages.stream()
            .flatMap(page -> page.keys().stream())
            .map(Key::string)
            .collect(Collectors.toList());
    }

    /**
     * Key strings in listing order.
     * @param keys Key strings
     * @return Sorted copy
     */
    private static List<String> sorted(final List<String> keys) {
        final String[] copy = keys.toArray(new String[0]);
        Arrays.sort(copy, new Utf8Order());
        return Arrays.asList(copy);
    }
}
