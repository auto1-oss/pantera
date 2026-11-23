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
package com.auto1.pantera.asto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Cuts one {@link ListPage} out of a collection of keys held in memory: the
 * keys strictly inside the path prefix and after the resume token, in
 * {@link Utf8Order}, at most {@code limit} of them.
 *
 * <p>For storages that have no cheaper way to page (in-memory, test
 * doubles); a storage of real size pages at its backend.</p>
 *
 * @since 2.2.14
 */
public final class PagedKeys {

    /**
     * Key order.
     */
    private static final Comparator<String> ORDER = new Utf8Order();

    /**
     * Path prefix with its trailing delimiter, empty for the root.
     */
    private final String inside;

    /**
     * Resume token.
     */
    private final Optional<String> after;

    /**
     * Page size.
     */
    private final int limit;

    /**
     * Ctor.
     * @param prefix Path prefix
     * @param after Resume token: the last key already returned
     * @param limit Page size
     */
    public PagedKeys(final Key prefix, final Optional<String> after, final int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                String.format("Page limit must be positive: %d", limit)
            );
        }
        final String str = prefix.string();
        if (str.isEmpty()) {
            this.inside = str;
        } else {
            this.inside = str + Key.DELIMITER;
        }
        this.after = after;
        this.limit = limit;
    }

    /**
     * The page.
     * @param all Candidate keys; those outside the prefix are ignored
     * @return Page
     */
    public ListPage page(final Collection<? extends Key> all) {
        final List<String> found = new ArrayList<>(Math.min(all.size(), 1024));
        for (final Key key : all) {
            final String str = key.string();
            if (this.wanted(str)) {
                found.add(str);
            }
        }
        return this.pageOf(found);
    }

    /**
     * The path prefix with its trailing delimiter; empty for the root.
     * @return String every key of the listing starts with
     */
    public String inside() {
        return this.inside;
    }

    /**
     * Whether a key belongs to the rest of the listing.
     * @param key Key string
     * @return True if inside the prefix and after the token
     */
    public boolean wanted(final String key) {
        return key.length() > this.inside.length() && key.startsWith(this.inside)
            && this.after.map(token -> PagedKeys.ORDER.compare(key, token) > 0).orElse(true);
    }

    /**
     * The page of keys already known to be {@link #wanted(String)}.
     * @param found Wanted keys, in any order; sorted in place
     * @return Page
     */
    public ListPage pageOf(final List<String> found) {
        found.sort(PagedKeys.ORDER);
        final int size = Math.min(this.limit, found.size());
        final List<Key> keys = new ArrayList<>(size);
        for (final String str : found.subList(0, size)) {
            keys.add(new Key.From(str));
        }
        final Optional<String> next;
        if (found.size() > size) {
            next = Optional.of(found.get(size - 1));
        } else {
            next = Optional.empty();
        }
        return new ListPage(keys, next);
    }
}
