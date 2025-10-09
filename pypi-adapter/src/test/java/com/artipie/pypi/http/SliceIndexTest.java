/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.headers.ContentType;
import com.artipie.http.hm.ResponseAssert;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.RsStatus;
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
