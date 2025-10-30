/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Test for {@link BrowseSlice}.
 */
class BrowseSliceTest {

    /**
     * Storage for tests.
     */
    private Storage storage;

    /**
     * Browse slice under test.
     */
    private BrowseSlice slice;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.slice = new BrowseSlice(this.storage);
    }

    @Test
    void rendersRootDirectory() throws Exception {
        // Arrange: Create some files in storage
        this.storage.save(new Key.From("file1.txt"), Content.EMPTY).join();
        this.storage.save(new Key.From("dir1/file2.txt"), Content.EMPTY).join();

        // Act: Request root directory
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: Check response
        MatcherAssert.assertThat(
            "Response should be successful",
            response.status().success(),
            Matchers.is(true)
        );

        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Body should contain HTML",
            body,
            Matchers.containsString("<html>")
        );
        MatcherAssert.assertThat(
            "Body should contain file1.txt",
            body,
            Matchers.containsString("file1.txt")
        );
        MatcherAssert.assertThat(
            "Body should contain dir1",
            body,
            Matchers.containsString("dir1")
        );
    }

    @Test
    void rendersSubdirectory() throws Exception {
        // Arrange: Create nested structure
        this.storage.save(new Key.From("maven/org/example/artifact.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("maven/org/example/pom.xml"), Content.EMPTY).join();

        // Act: Request subdirectory with full path in header
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/maven/org"),
            Headers.from(new Header("X-FullPath", "/repo_name/maven/org")),
            Content.EMPTY
        ).join();

        // Assert
        MatcherAssert.assertThat(
            "Response should be successful",
            response.status().success(),
            Matchers.is(true)
        );

        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Body should contain parent directory link",
            body,
            Matchers.containsString("../")
        );
        MatcherAssert.assertThat(
            "Body should contain example directory",
            body,
            Matchers.containsString("example")
        );
    }

    @Test
    void preservesRepositoryNameInLinks() throws Exception {
        // Arrange
        this.storage.save(new Key.From("group/artifact/1.0/file.jar"), Content.EMPTY).join();

        // Act: Request with full path including repository name
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/group"),
            Headers.from(new Header("X-FullPath", "/maven_repo/group")),
            Content.EMPTY
        ).join();

        // Assert: Links should preserve the full path
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Links should include repository name",
            body,
            Matchers.containsString("/maven_repo/group/artifact/")
        );
    }

    @Test
    void handlesEmptyDirectory() throws Exception {
        // Act: Request empty directory
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert
        MatcherAssert.assertThat(
            "Response should be successful",
            response.status().success(),
            Matchers.is(true)
        );

        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "Body should contain HTML",
            body,
            Matchers.containsString("<html>")
        );
        MatcherAssert.assertThat(
            "Body should contain pre tag",
            body,
            Matchers.containsString("<pre>")
        );
    }

    @Test
    void escapesHtmlInFilenames() throws Exception {
        // Arrange: Create file with HTML characters
        this.storage.save(new Key.From("<script>alert.txt"), Content.EMPTY).join();

        // Act
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: HTML should be escaped
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "HTML should be escaped",
            body,
            Matchers.containsString("&lt;script&gt;")
        );
        MatcherAssert.assertThat(
            "Raw HTML should not be present",
            body,
            Matchers.not(Matchers.containsString("<script>alert"))
        );
    }

    @Test
    void deduplicatesDirectoriesWithMultipleArtifacts() throws Exception {
        // Arrange: Create multiple artifacts in same directory
        this.storage.save(new Key.From("org/example/artifact1.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("org/example/artifact2.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("org/example/artifact3.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("org/other/file.txt"), Content.EMPTY).join();

        // Act: Request root directory
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: "org" should appear only once
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        // Count occurrences of "org/" link
        int count = 0;
        int index = 0;
        while ((index = body.indexOf(">org/</a>", index)) != -1) {
            count++;
            index += 9;
        }
        
        MatcherAssert.assertThat(
            "Directory 'org' should appear exactly once",
            count,
            Matchers.is(1)
        );
    }

    @Test
    void distinguishesFilesFromDirectories() throws Exception {
        // Arrange: Create files and directories with similar names
        this.storage.save(new Key.From("test.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("test/artifact.jar"), Content.EMPTY).join();

        // Act: Request root directory
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: Both should appear, one as file, one as directory
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "Should contain test.jar as file",
            body,
            Matchers.containsString(">test.jar</a>")
        );
        MatcherAssert.assertThat(
            "Should contain test as directory",
            body,
            Matchers.containsString(">test/</a>")
        );
        
        // Verify file doesn't have trailing slash
        MatcherAssert.assertThat(
            "File should not have trailing slash in link",
            body,
            Matchers.not(Matchers.containsString(">test.jar/</a>"))
        );
    }

    @Test
    void showsEmptyListingForNonExistentFile() throws Exception {
        // Arrange: Request a file that doesn't exist
        // BrowseSlice will try to list it and show empty directory

        // Act: Request non-existent file
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/nonexistent.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: Should show empty directory listing (no items)
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        // Should contain HTML structure
        MatcherAssert.assertThat(
            "Should contain HTML",
            body,
            Matchers.containsString("<html>")
        );
        
        // Should show the path in title
        MatcherAssert.assertThat(
            "Should show path in title",
            body,
            Matchers.containsString("Index of /nonexistent.jar")
        );
    }

    @Test
    void handlesVersionNumberDirectories() throws Exception {
        // Arrange: Create Maven-style version directory structure
        this.storage.save(new Key.From("com/example/artifact/1.3.0/artifact-1.3.0.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("com/example/artifact/1.3.0/artifact-1.3.0.pom"), Content.EMPTY).join();
        this.storage.save(new Key.From("com/example/artifact/2.0.1/artifact-2.0.1.jar"), Content.EMPTY).join();

        // Act: Request version directory (should NOT be treated as file)
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/com/example/artifact/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: Should show version directories
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "Should contain version 1.3.0 as directory",
            body,
            Matchers.containsString(">1.3.0/</a>")
        );
        
        MatcherAssert.assertThat(
            "Should contain version 2.0.1 as directory",
            body,
            Matchers.containsString(">2.0.1/</a>")
        );
        
        // Verify they're shown as directories with trailing slash
        MatcherAssert.assertThat(
            "Should have trailing slash for directories",
            body,
            Matchers.containsString(">1.3.0/</a>")
        );
    }

    @Test
    void recognizesChecksumFilesAsFiles() throws Exception {
        // Arrange: Create artifact with checksum files
        this.storage.save(new Key.From("artifact-1.0.jar"), Content.EMPTY).join();
        this.storage.save(new Key.From("artifact-1.0.jar.md5"), Content.EMPTY).join();
        this.storage.save(new Key.From("artifact-1.0.jar.sha1"), Content.EMPTY).join();
        this.storage.save(new Key.From("artifact-1.0.jar.sha256"), Content.EMPTY).join();
        this.storage.save(new Key.From("artifact-1.0.jar.sha512"), Content.EMPTY).join();

        // Act: Request root directory
        final Response response = this.slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        // Assert: All checksum files should be shown as files, not directories
        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        // Should show as files (with file icon, not directory icon)
        MatcherAssert.assertThat(
            "Should contain .md5 file",
            body,
            Matchers.containsString(">artifact-1.0.jar.md5</a>")
        );
        
        MatcherAssert.assertThat(
            "Should contain .sha1 file",
            body,
            Matchers.containsString(">artifact-1.0.jar.sha1</a>")
        );
        
        MatcherAssert.assertThat(
            "Should contain .sha256 file",
            body,
            Matchers.containsString(">artifact-1.0.jar.sha256</a>")
        );
        
        MatcherAssert.assertThat(
            "Should contain .sha512 file",
            body,
            Matchers.containsString(">artifact-1.0.jar.sha512</a>")
        );
        
        // Verify they don't have trailing slashes (not directories)
        MatcherAssert.assertThat(
            "Checksum files should not have trailing slash",
            body,
            Matchers.not(Matchers.containsString(">artifact-1.0.jar.md5/</a>"))
        );
    }
}
