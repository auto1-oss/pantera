/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Result of hierarchical listing operation.
 * Contains both files and directories (common prefixes) at a single level.
 * 
 * <p>This interface supports efficient directory browsing by returning only
 * immediate children instead of recursively traversing the entire tree.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // List immediate children of "com/" directory
 * ListResult result = storage.list(new Key.From("com/"), "/").join();
 * 
 * // Files at this level: com/README.md
 * Collection<Key> files = result.files();
 * 
 * // Subdirectories: com/google/, com/apache/, com/example/
 * Collection<Key> dirs = result.directories();
 * }</pre>
 * 
 * @since 1.18.19
 */
public interface ListResult {
    
    /**
     * Files at the current level (non-recursive).
     * 
     * <p>Returns only files that are direct children of the listed prefix,
     * not files in subdirectories.</p>
     * 
     * @return Collection of file keys, never null
     */
    Collection<Key> files();
    
    /**
     * Subdirectories (common prefixes) at the current level.
     * 
     * <p>Returns directory prefixes that are direct children of the listed prefix.
     * Each directory key typically ends with the delimiter (e.g., "/").</p>
     * 
     * @return Collection of directory keys, never null
     */
    Collection<Key> directories();
    
    /**
     * Check if the result is empty (no files and no directories).
     * 
     * @return True if both files and directories are empty
     */
    default boolean isEmpty() {
        return files().isEmpty() && directories().isEmpty();
    }
    
    /**
     * Get total count of items (files + directories).
     * 
     * @return Total number of items
     */
    default int size() {
        return files().size() + directories().size();
    }
    
    /**
     * Simple immutable implementation of ListResult.
     */
    final class Simple implements ListResult {
        
        /**
         * Files at this level.
         */
        private final Collection<Key> fls;
        
        /**
         * Directories at this level.
         */
        private final Collection<Key> dirs;
        
        /**
         * Constructor.
         * 
         * @param files Files at this level
         * @param directories Directories at this level
         */
        public Simple(final Collection<Key> files, final Collection<Key> directories) {
            this.fls = Collections.unmodifiableList(new ArrayList<>(files));
            this.dirs = Collections.unmodifiableList(new ArrayList<>(directories));
        }
        
        @Override
        public Collection<Key> files() {
            return this.fls;
        }
        
        @Override
        public Collection<Key> directories() {
            return this.dirs;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ListResult{files=%d, directories=%d}",
                this.fls.size(),
                this.dirs.size()
            );
        }
    }
    
    /**
     * Empty list result (no files, no directories).
     */
    ListResult EMPTY = new Simple(Collections.emptyList(), Collections.emptyList());
}
