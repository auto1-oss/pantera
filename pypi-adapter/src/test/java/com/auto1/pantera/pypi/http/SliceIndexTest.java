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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.RsStatus;
import org.apache.commons.codec.digest.DigestUtils;
import org.cactoos.map.MapEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test for {@link SliceIndex}.
 */
class SliceIndexTest {

    /**
     * Full path header name.
     */
    private static final String HDR_FULL_PATH = "X-FullPath";

    private Storage storage;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void returnsIndexListForRoot() {
        final String path = "abc/abc-0.1.tar.gz";
        final byte[] bytes = "abc".getBytes();
        this.storage.save(new Key.From(path), new Content.From(bytes)).join();
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(new MapEntry<>(path, bytes))
        );
    }

    @Test
    void returnsIndexListForRootWithFullPathHeader() {
        final byte[] bytes = "qwerty".getBytes();
        this.storage.save(new Key.From("abc/abc-0.1.tar.gz"), new Content.From(bytes))
            .join();
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/"),
                Headers.from(SliceIndexTest.HDR_FULL_PATH, "/username/pypi"),
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(new MapEntry<>("username/pypi/abc/abc-0.1.tar.gz", bytes))
        );
    }

    @Test
    void returnsIndexList() {
        final String gzip = "def/def-0.1.tar.gz";
        final String wheel = "def/def-0.2.whl";
        this.storage.save(new Key.From(gzip), new Content.From(gzip.getBytes())).join();
        this.storage.save(new Key.From(wheel), new Content.From(wheel.getBytes())).join();
        this.storage.save(
            new Key.From("ghi", "jkl", "hij-0.3.whl"), new Content.From("000".getBytes())
        ).join();
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/def"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(gzip, gzip.getBytes()), new MapEntry<>(wheel, wheel.getBytes())
            )
        );
    }

    @Test
    void returnsIndexListWithFullPathHeader() {
        final byte[] one = "1".getBytes();
        this.storage.save(new Key.From("def/def-0.1.tar.gz"), new Content.From(one))
            .join();
        final byte[] two = "2".getBytes();
        this.storage.save(new Key.From("def/def-0.2.whl"), new Content.From(two)).join();
        this.storage.save(
            new Key.From("ghi", "jkl", "hij-0.3.whl"), new Content.From("3".getBytes())
        ).join();
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/def"),
                Headers.from(SliceIndexTest.HDR_FULL_PATH, "/username/repo/def"),
                Content.EMPTY
            ).join(),
                SliceIndexTest.html(
                    new MapEntry<>("username/repo/def/def-0.1.tar.gz", one),
                    new MapEntry<>("username/repo/def/def-0.2.whl", two)
                )
        );
    }

    @Test
    void returnsIndexListForMixedItems() {
        final String rqline = "abc";
        final String one = "abc/file.txt";
        final String two = "abc/folder_one/file.txt";
        final String three = "abc/folder_two/abc/file.txt";
        this.storage.save(new Key.From(two), new Content.From(two.getBytes())).join();
        this.storage.save(new Key.From(one), new Content.From(one.getBytes())).join();
        this.storage.save(new Key.From(three), new Content.From(three.getBytes()))
            .join();
        this.storage.save(
            new Key.From("def", "ghi", "hij-0.3.whl"), new Content.From("sd".getBytes())
        ).join();
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", String.format("/%s", rqline)),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                    new MapEntry<>(one, one.getBytes()),
                    new MapEntry<>(two, two.getBytes()),
                    new MapEntry<>(three, three.getBytes())
                )
        );
    }

    @Test
    void returnsIndexListForMixedItemsWithFullPath() {
        final byte[] one = "a".getBytes();
        final byte[] two = "b".getBytes();
        final byte[] three = "c".getBytes();
        this.storage.save(new Key.From("abc/folder_one/file.txt"), new Content.From(one)).join();
        this.storage.save(new Key.From("abc/file.txt"), new Content.From(two)).join();
        this.storage.save(new Key.From("abc/folder_two/abc/file.txt"), new Content.From(three))
            .join();
        this.storage.save(
            new Key.From("def", "ghi", "hij-0.3.whl"), new Content.From("w".getBytes())
        ).join();
        new SliceIndex(this.storage).response(
            new RequestLine("GET", "/abc"),
            Headers.from(SliceIndexTest.HDR_FULL_PATH, "/username/pypi/abc"),
            Content.EMPTY
        );
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/abc"),
                Headers.from(SliceIndexTest.HDR_FULL_PATH, "/username/pypi/abc"),
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>("username/pypi/abc/file.txt", two),
                new MapEntry<>("username/pypi/abc/folder_one/file.txt", one),
                new MapEntry<>("username/pypi/abc/folder_two/abc/file.txt", three)
            )
        );
    }

    @Test
    void returnsIndexListForEmptyStorage() {
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/def"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            RsStatus.NOT_FOUND
        );
    }

    @Test
    void returnsIndexListForEmptyStorageWithFullPath() {
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/def"),
                Headers.from(SliceIndexTest.HDR_FULL_PATH, "/username/pypi/def"),
                Content.EMPTY
            ).join(),
            RsStatus.NOT_FOUND
        );
    }

    @Test
    void returnsStatusAndHeaders() {
        final String path = "some";
        this.storage.save(
            new Key.From(path, "abc-0.0.1.tar.gz"), new Content.From(new byte[]{})
        ).join();
        Response r = new SliceIndex(this.storage).response(
            new RequestLine("GET", "/"), Headers.EMPTY, Content.EMPTY
        ).join();
        ResponseAssert.check(r, RsStatus.OK, ContentType.html(), new ContentLength(179));
    }

    @Test
    void returnsIndexFromPypiFolder() {
        // Create artifact in standard location
        final String packageName = "mypackage";
        this.storage.save(
            new Key.From(packageName, "0.1.0", "mypackage-0.1.0.whl"),
            new Content.From("content".getBytes())
        ).join();
        
        // Create index in .pypi/ folder structure
        final String indexHtml = "<!DOCTYPE html>\n<html>\n  <body>\n<a href=\"0.1.0/mypackage-0.1.0.whl#sha256=abc\">mypackage-0.1.0.whl</a><br/>\n</body>\n</html>";
        this.storage.save(
            new Key.From(".pypi", packageName, packageName + ".html"),
            new Content.From(indexHtml.getBytes())
        ).join();
        
        Response r = new SliceIndex(this.storage).response(
            new RequestLine("GET", "/" + packageName),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        ResponseAssert.check(r, RsStatus.OK, ContentType.html());
    }

    @Test
    void returnsRepoIndexFromPypiFolder() {
        // Create artifacts
        this.storage.save(
            new Key.From("package1", "0.1.0", "package1-0.1.0.whl"),
            new Content.From("content1".getBytes())
        ).join();
        this.storage.save(
            new Key.From("package2", "0.2.0", "package2-0.2.0.whl"),
            new Content.From("content2".getBytes())
        ).join();
        
        // Create repo-level index in .pypi/simple.html
        final String simpleHtml = "<!DOCTYPE html>\n<html>\n  <body>\n<a href=\"package1/\">package1</a><br/>\n<a href=\"package2/\">package2</a><br/>\n</body>\n</html>";
        this.storage.save(
            new Key.From(".pypi", "simple.html"),
            new Content.From(simpleHtml.getBytes())
        ).join();
        
        Response r = new SliceIndex(this.storage).response(
            new RequestLine("GET", "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        
        ResponseAssert.check(r, RsStatus.OK, ContentType.html());
    }

    @Test
    void returnsRepoIndexFromPypiFolderForSimplePath() {
        this.storage.save(
            new Key.From(".pypi", "simple.html"),
            new Content.From("<html></html>".getBytes())
        ).join();
        Response r = new SliceIndex(this.storage).response(
            new RequestLine("GET", "/simple/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        ResponseAssert.check(r, RsStatus.OK, ContentType.html());
    }

    @Test
    void returnsPackageIndexFromPypiFolderForSimplePath() {
        final String packageName = "pkg";
        this.storage.save(
            new Key.From(".pypi", packageName, packageName + ".html"),
            new Content.From("<html></html>".getBytes())
        ).join();
        Response r = new SliceIndex(this.storage).response(
            new RequestLine("GET", String.format("/simple/%s/", packageName)),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        ResponseAssert.check(r, RsStatus.OK, ContentType.html());
    }

    @Test
    void returnsIndexListForSimplePackageWhenFallbackUsed() {
        final String gzip = "def/def-0.1.tar.gz";
        final String wheel = "def/def-0.2.whl";
        this.storage.save(new Key.From(gzip), new Content.From(gzip.getBytes())).join();
        this.storage.save(new Key.From(wheel), new Content.From(wheel.getBytes())).join();
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/def/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(gzip, gzip.getBytes()),
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );
    }

    @Test
    void normalizesPackageNameWithHyphens() {
        // Package stored with normalized name (hyphens)
        final String normalized = "sm-pipelines";
        final String gzip = normalized + "/" + normalized + "-0.1.0.tar.gz";
        final String wheel = normalized + "/" + normalized + "-0.2.0.whl";
        this.storage.save(new Key.From(gzip), new Content.From(gzip.getBytes())).join();
        this.storage.save(new Key.From(wheel), new Content.From(wheel.getBytes())).join();

        // Request with hyphens should work
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/sm-pipelines/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(gzip, gzip.getBytes()),
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );

        // Request with underscores should also work (normalized to hyphens)
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/sm_pipelines/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(gzip, gzip.getBytes()),
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );

        // Request with mixed case should also work (normalized to lowercase)
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/SM-Pipelines/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(gzip, gzip.getBytes()),
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );
    }

    @Test
    void normalizesPackageNameWithUnderscores() {
        // Package stored with normalized name (underscores become hyphens)
        final String normalized = "my-package";
        final String wheel = normalized + "/" + normalized + "-1.0.0.whl";
        this.storage.save(new Key.From(wheel), new Content.From(wheel.getBytes())).join();

        // Request with underscores should find the hyphenated storage path
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/my_package/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );
    }

    @Test
    void normalizesPackageNameWithDots() {
        // Package stored with normalized name (dots become hyphens)
        final String normalized = "my-package";
        final String wheel = normalized + "/" + normalized + "-1.0.0.whl";
        this.storage.save(new Key.From(wheel), new Content.From(wheel.getBytes())).join();

        // Request with dots should find the hyphenated storage path
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/my.package/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );
    }

    @Test
    void normalizesPackageNameWithMixedSeparators() {
        // Package stored with normalized name (all separators become single hyphen)
        final String normalized = "my-super-package";
        final String wheel = normalized + "/" + normalized + "-2.0.0.whl";
        this.storage.save(new Key.From(wheel), new Content.From(wheel.getBytes())).join();

        // Request with mixed separators should find the normalized storage path
        ResponseAssert.check(
            new SliceIndex(this.storage).response(
                new RequestLine("GET", "/simple/My._Super__Package/"),
                Headers.EMPTY,
                Content.EMPTY
            ).join(),
            SliceIndexTest.html(
                new MapEntry<>(wheel, wheel.getBytes())
            )
        );
    }

    @SafeVarargs
    private static byte[] html(final Map.Entry<String, byte[]>... items) {
        return
            String.format(
                "<!DOCTYPE html>\n<html>\n  </body>\n%s\n</body>\n</html>",
                Stream.of(items).map(
                    item -> String.format(
                        "<a href=\"/%s#sha256=%s\">%s</a><br/>", item.getKey(),
                        DigestUtils.sha256Hex(item.getValue()),
                        Stream.of(item.getKey().split("/"))
                            .reduce((first, second) -> second).orElse("")
                    )
                ).collect(Collectors.joining())
            ).getBytes();
    }

}
