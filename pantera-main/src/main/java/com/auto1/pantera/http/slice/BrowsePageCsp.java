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
package com.auto1.pantera.http.slice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Inline style/script blocks of the directory-listing pages plus the
 * hash-based {@code Content-Security-Policy} values that allow exactly
 * those blocks and nothing else.
 *
 * <p>The global {@code SecurityHeadersSlice} stamps
 * {@code Content-Security-Policy: default-src 'self'} on every response,
 * which blocks inline {@code <style>}/{@code <script>} blocks and inline
 * event handlers. Browse pages are the only server-generated HTML with
 * inline assets, so instead of weakening the global policy they declare
 * their own per-route CSP that allowlists the exact SHA-256 of their
 * inline blocks ({@code SecurityHeadersSlice} never overwrites a CSP the
 * response already carries). The emitting slice MUST render the blocks
 * from these constants verbatim — any drift between the emitted bytes
 * and the hashed constant makes the browser drop the style/script again.
 *
 * <p>Inline event handlers ({@code onclick=}) cannot be allowlisted by
 * hash without {@code 'unsafe-hashes'}; the sort controls therefore bind
 * their listeners inside the hashed script block via
 * {@code addEventListener} on {@code data-sort} attributes.
 *
 * @since 2.2.1
 */
public final class BrowsePageCsp {

    /**
     * Inner text of the {@code <style>} block of the filesystem browse
     * page ({@link FileSystemBrowseSlice}) — hashed verbatim into
     * {@link #FS_CSP}.
     */
    public static final String FS_STYLE = "\n"
        + "    body { font-family: monospace; margin: 20px; background: #fafafa; }\n"
        + "    h1 { font-size: 18px; color: #333; }\n"
        + "    .controls { margin: 10px 0; font-size: 12px; }\n"
        + "    .sort-link { color: #0066cc; cursor: pointer; text-decoration: underline; margin-right: 15px; }\n"
        + "    .sort-link:hover { color: #004499; }\n"
        + "    .sort-link.active { font-weight: bold; color: #004499; }\n"
        + "    a { text-decoration: none; color: #0066cc; }\n"
        + "    a:hover { text-decoration: underline; }\n"
        + "    #listing { font-size: 14px; line-height: 1.6; }\n"
        + "    .entry { display: grid; grid-template-columns: 1fr auto auto; gap: 20px; align-items: baseline; }\n"
        + "    .entry-name { overflow: hidden; text-overflow: ellipsis; }\n"
        + "    .size { color: #666; font-size: 0.9em; text-align: right; min-width: 80px; white-space: nowrap; }\n"
        + "    .date { color: #999; font-size: 0.85em; min-width: 140px; white-space: nowrap; }\n"
        + "    .footer { font-size: 11px; color: #999; margin-top: 20px; }\n"
        + "  ";

    /**
     * Inner text of the {@code <script>} block of the filesystem browse
     * page — client-side sorting. Listeners are bound via
     * {@code addEventListener} (not {@code onclick=}) so the block can be
     * allowlisted by hash. Hashed verbatim into {@link #FS_CSP}.
     */
    public static final String FS_SCRIPT = "\n"
        + "let currentSort = 'name';\n"
        + "let sortReverse = false;\n"
        + "\n"
        + "function sortBy(ev, field) {\n"
        + "  if (currentSort === field) {\n"
        + "    sortReverse = !sortReverse;\n"
        + "  } else {\n"
        + "    currentSort = field;\n"
        + "    sortReverse = false;\n"
        + "  }\n"
        + "  \n"
        + "  // Update active link\n"
        + "  document.querySelectorAll('.sort-link').forEach(link => {\n"
        + "    link.classList.remove('active');\n"
        + "  });\n"
        + "  ev.target.classList.add('active');\n"
        + "  \n"
        + "  const listing = document.getElementById('listing');\n"
        + "  \n"
        + "  // Get all sortable entries (elements with data-name attribute)\n"
        + "  const entries = Array.from(listing.querySelectorAll('.entry[data-name]')).map(div => ({\n"
        + "    element: div,\n"
        + "    name: div.getAttribute('data-name'),\n"
        + "    sizeBytes: parseInt(div.getAttribute('data-size') || '0'),\n"
        + "    date: div.getAttribute('data-date') || ''\n"
        + "  }));\n"
        + "  \n"
        + "  // Sort entries\n"
        + "  entries.sort((a, b) => {\n"
        + "    let cmp = 0;\n"
        + "    if (field === 'name') {\n"
        + "      cmp = a.name.localeCompare(b.name);\n"
        + "    } else if (field === 'size') {\n"
        + "      cmp = a.sizeBytes - b.sizeBytes;\n"
        + "    } else if (field === 'date') {\n"
        + "      cmp = a.date.localeCompare(b.date);\n"
        + "    }\n"
        + "    return sortReverse ? -cmp : cmp;\n"
        + "  });\n"
        + "  \n"
        + "  // Find and preserve parent link (../) if it exists\n"
        + "  const allLinks = Array.from(listing.querySelectorAll('a'));\n"
        + "  const parentLink = allLinks.find(a => a.textContent.trim() === '../');\n"
        + "  \n"
        + "  // Clear listing and rebuild\n"
        + "  listing.innerHTML = '';\n"
        + "  \n"
        + "  // Add parent link first if it exists\n"
        + "  if (parentLink) {\n"
        + "    listing.appendChild(parentLink.cloneNode(true));\n"
        + "    listing.appendChild(document.createTextNode('\\n'));\n"
        + "  }\n"
        + "  \n"
        + "  // Add sorted entries\n"
        + "  entries.forEach(entry => {\n"
        + "    listing.appendChild(entry.element);\n"
        + "    listing.appendChild(document.createTextNode('\\n'));\n"
        + "  });\n"
        + "}\n"
        + "\n"
        + "// CSP-compatible listener binding — inline onclick handlers are\n"
        + "// blocked by the hash-based policy this page declares.\n"
        + "document.querySelectorAll('.sort-link').forEach(link => {\n"
        + "  link.addEventListener('click', ev => sortBy(ev, link.getAttribute('data-sort')));\n"
        + "});\n";

    /**
     * Inner text of the {@code <style>} block of the streaming browse
     * page ({@link StreamingBrowseSlice}) — hashed verbatim into
     * {@link #STREAMING_CSP}. The item-count footer styling lives here
     * as a class because inline {@code style=} attributes are equally
     * blocked by hash-based CSP.
     */
    public static final String STREAMING_STYLE = "\n"
        + "    body { font-family: monospace; margin: 20px; }\n"
        + "    h1 { font-size: 18px; }\n"
        + "    a { display: block; padding: 2px 0; text-decoration: none; }\n"
        + "    a:hover { background: #f0f0f0; }\n"
        + "    .footer { font-size: 12px; color: #666; }\n"
        + "  ";

    /**
     * Per-route CSP of the filesystem browse page: same-origin defaults
     * plus exactly its own style and script blocks by SHA-256.
     */
    public static final String FS_CSP = "default-src 'self'"
        + "; style-src 'sha256-" + sha256Base64(FS_STYLE) + "'"
        + "; script-src 'sha256-" + sha256Base64(FS_SCRIPT) + "'";

    /**
     * Per-route CSP of the streaming browse page: same-origin defaults
     * plus exactly its own style block by SHA-256 (the page carries no
     * script).
     */
    public static final String STREAMING_CSP = "default-src 'self'"
        + "; style-src 'sha256-" + sha256Base64(STREAMING_STYLE) + "'";

    /**
     * Response header name the browse slices set.
     */
    public static final String HEADER = "Content-Security-Policy";

    /**
     * Not instantiable — constants only.
     */
    private BrowsePageCsp() {
    }

    /**
     * Standard-base64 SHA-256 as used in CSP hash sources.
     *
     * @param text Inline block content, hashed as UTF-8
     * @return Base64 digest without the {@code sha256-} prefix
     */
    private static String sha256Base64(final String text) {
        try {
            return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))
            );
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
