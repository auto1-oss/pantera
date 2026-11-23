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
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Utf8Order;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lists the files of a directory tree as keys, one page at a time, in the
 * order of an S3 listing: by the UTF-8 bytes of the whole key.
 *
 * <p>The walk is depth-first and reads one directory at a time: its entries
 * are sorted, a directory under the name it has inside a key (with the
 * trailing {@code /}), then visited in that order. Entries and whole subtrees
 * that lie at or before the resume token are dropped while the directory is
 * read, before they are sorted or examined. So the memory in use is the page
 * plus the entries of the directories on the current path, never the tree,
 * and every directory stream is closed before the walk descends.</p>
 *
 * <p>Symbolic links to files are listed, linked directories are not entered
 * (only the directory the walk starts at may be a link). A directory that
 * disappears during the walk counts as empty.</p>
 *
 * <p>Blocking: callers run it off the event loop. Not thread-safe, one walk
 * per page.</p>
 *
 * @since 2.2.14
 */
public final class SortedKeyWalk {

    /**
     * Key order.
     */
    private static final Comparator<String> ORDER = new Utf8Order();

    /**
     * Directory the listing starts at.
     */
    private final Path start;

    /**
     * Key prefix of the entries of that directory: empty for the storage
     * root, else the prefix key with a trailing delimiter.
     */
    private final String base;

    /**
     * Directories read so far.
     */
    private int opened;

    /**
     * Ctor.
     * @param start Directory of the prefix
     * @param prefix Path prefix the directory stands for
     */
    public SortedKeyWalk(final Path start, final Key prefix) {
        this.start = start;
        final String str = prefix.string();
        if (str.isEmpty()) {
            this.base = str;
        } else {
            this.base = str + Key.DELIMITER;
        }
    }

    /**
     * One page of keys.
     * @param after Resume token: the key the previous page ended at
     * @param limit Page size
     * @return Page
     */
    public ListPage page(final Optional<String> after, final int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                String.format("Page limit must be positive: %d", limit)
            );
        }
        final List<Key> keys = new ArrayList<>(Math.min(limit, 1024));
        boolean more = false;
        if (Files.isDirectory(this.start)) {
            more = this.walk(this.start, this.base, after.orElse(null), limit, keys);
        }
        final Optional<String> next;
        if (more) {
            next = Optional.of(keys.get(keys.size() - 1).string());
        } else {
            next = Optional.empty();
        }
        return new ListPage(keys, next);
    }

    /**
     * Number of directories read by this walk.
     * @return Count
     */
    public int opened() {
        return this.opened;
    }

    /**
     * Collect the keys of a directory.
     * @param dir Directory
     * @param prefix Key prefix of its entries
     * @param after Resume token if it lies inside this directory, else null
     * @param limit Page size
     * @param out Keys collected
     * @return True if the page is full and entries were left unvisited
     */
    private boolean walk(
        final Path dir, final String prefix, final String after,
        final int limit, final List<Key> out
    ) {
        boolean full = false;
        for (final Entry entry : this.entries(dir, prefix, after)) {
            if (out.size() >= limit) {
                full = true;
                break;
            }
            final String key = prefix + entry.name();
            if (entry.dir()) {
                final String inside = key + Key.DELIMITER;
                final String deeper;
                if (after != null && after.startsWith(inside)) {
                    deeper = after;
                } else {
                    deeper = null;
                }
                if (this.walk(dir.resolve(entry.name()), inside, deeper, limit, out)) {
                    full = true;
                    break;
                }
            } else {
                out.add(new Key.From(key));
            }
        }
        return full;
    }

    /**
     * Entries of a directory that may still yield keys, in key order.
     * @param dir Directory
     * @param prefix Key prefix of its entries
     * @param after Resume token or null
     * @return Sorted entries
     */
    private List<Entry> entries(final Path dir, final String prefix, final String after) {
        final List<Entry> found = new ArrayList<>(16);
        this.opened += 1;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (final Path path : stream) {
                final String name = path.getFileName().toString();
                SortedKeyWalk.kind(path, prefix + name, after).ifPresent(
                    isdir -> found.add(Entry.of(name, isdir))
                );
            }
        } catch (final NoSuchFileException | NotDirectoryException gone) {
            found.clear();
        } catch (final DirectoryIteratorException | IOException err) {
            throw new PanteraIOException(err);
        }
        found.sort(Comparator.comparing(Entry::order, SortedKeyWalk.ORDER));
        return found;
    }

    /**
     * Decide what a directory entry contributes to the rest of the listing.
     * A file counts if its key is after the token. A directory counts if the
     * keys below it are: they all start with its key and the delimiter, so
     * they are after the token when that string is, and some of them may be
     * when the token itself starts with it. An entry that fails both tests
     * as a directory fails as a file too and is dropped without touching
     * the file system.
     * @param path Entry path
     * @param key Key of the entry
     * @param after Resume token or null
     * @return True for a directory to enter, false for a file to list, empty
     *  for an entry to skip
     */
    private static Optional<Boolean> kind(final Path path, final String key, final String after) {
        final boolean later = after == null || SortedKeyWalk.ORDER.compare(key, after) > 0;
        final String inside = key + Key.DELIMITER;
        Optional<Boolean> kind = Optional.empty();
        if (later || SortedKeyWalk.ORDER.compare(inside, after) > 0
            || after.startsWith(inside)) {
            try {
                final BasicFileAttributes attrs = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
                );
                if (attrs.isDirectory()) {
                    kind = Optional.of(true);
                } else if (later && (attrs.isRegularFile()
                    || attrs.isSymbolicLink() && Files.isRegularFile(path))) {
                    kind = Optional.of(false);
                }
            } catch (final NoSuchFileException gone) {
                kind = Optional.empty();
            } catch (final IOException err) {
                throw new PanteraIOException(err);
            }
        }
        return kind;
    }

    /**
     * Directory entry.
     * @param name File name
     * @param dir Whether it is a directory
     * @param order Name it sorts under: the way it appears inside a key, a
     *  directory with its trailing delimiter
     * @since 2.2.14
     */
    private record Entry(String name, boolean dir, String order) {

        /**
         * Entry of a name.
         * @param name File name
         * @param dir Whether it is a directory
         * @return Entry
         */
        private static Entry of(final String name, final boolean dir) {
            final String order;
            if (dir) {
                order = name + Key.DELIMITER;
            } else {
                order = name;
            }
            return new Entry(name, dir, order);
        }
    }
}
