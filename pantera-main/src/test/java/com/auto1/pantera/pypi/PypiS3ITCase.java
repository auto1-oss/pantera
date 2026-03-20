/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.pypi;

import com.auto1.pantera.test.ContainerResultMatcher;
import com.auto1.pantera.test.TestDeployment;
import org.cactoos.list.ListOf;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import java.io.IOException;

/**
 * Integration tests for Pypi repository.
 *
 * @since 0.12
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@EnabledOnOs({OS.LINUX, OS.MAC})
final class PypiS3ITCase {

    /**
     * Curl exit code when resource not retrieved and `--fail` is used, http 400+.
     */
    private static final int CURL_NOT_FOUND = 22;

    /**
     * Test deployments.
     *
     */
    @RegisterExtension
    final TestDeployment containers = new TestDeployment(
        () -> TestDeployment.PanteraContainer.defaultDefinition()
            .withRepoConfig("pypi-repo/pypi-s3.yml", "my-python")
            .withUser("security/users/alice.yaml", "alice")
            .withRole("security/roles/readers.yaml", "readers")
            .withExposedPorts(8080),
        () -> new TestDeployment.ClientContainer("pantera/pypi-tests:1.0")
            .withWorkingDirectory("/w")
            .withNetworkAliases("minioc")
            .withExposedPorts(9000)
            .waitingFor(
                new AbstractWaitStrategy() {
                    @Override
                    protected void waitUntilReady() {
                        // Don't wait for minIO port.
                    }
                }
            )
    );

    @BeforeEach
    void setUp() throws IOException {
        this.containers.assertExec(
            "Failed to start Minio", new ContainerResultMatcher(),
            "bash", "-c", "nohup /root/bin/minio server /var/minio 2>&1|tee /tmp/minio.log &"
        );
        this.containers.assertExec(
            "Failed to wait for Minio", new ContainerResultMatcher(),
            "timeout", "30",  "sh", "-c", "until nc -z localhost 9000; do sleep 0.1; done"
        );
    }

    @ParameterizedTest
    @CsvSource("8080,my-python,9000")
    //"8081,my-python-port,9000" todo https://github.com/pantera/pantera/issues/1350
    void uploadAndinstallPythonPackage(final String port, final String repo, final String s3port) throws IOException {
        this.containers.assertExec(
            "panteratestpkg-0.0.3.tar.gz must not exist in S3 storage after test",
            new ContainerResultMatcher(new IsEqual<>(PypiS3ITCase.CURL_NOT_FOUND)),
            "curl -f -kv http://minioc:%s/buck1/my-python/panteratestpkg/panteratestpkg-0.0.3.tar.gz".formatted(s3port, repo).split(" ")
        );
        this.containers.assertExec(
            "Failed to upload",
            new ContainerResultMatcher(
                Matchers.is(0),
                new StringContainsInOrder(
                    new ListOf<>(
                        "Uploading panteratestpkg-0.0.3.tar.gz", "100%"
                    )
                )
            ),
            "python3", "-m", "twine", "upload", "--repository-url",
            String.format("http://pantera:%s/%s/", port, repo),
            "-u", "alice", "-p", "123",
            "/w/example-pckg/dist/panteratestpkg-0.0.3.tar.gz"
        );
        this.containers.assertExec(
            "Failed to install package",
            new ContainerResultMatcher(
                Matchers.equalTo(0),
                new StringContainsInOrder(
                    new ListOf<>(
                        String.format("Looking in indexes: http://pantera:%s/%s", port, repo),
                        "Collecting panteratestpkg",
                        String.format(
                            "  Downloading http://pantera:%s/%s/panteratestpkg/%s",
                            port, repo, "panteratestpkg-0.0.3.tar.gz"
                        ),
                        "Building wheels for collected packages: panteratestpkg",
                        "  Building wheel for panteratestpkg (setup.py): started",
                        String.format(
                            "  Building wheel for panteratestpkg (setup.py): %s",
                            "finished with status 'done'"
                        ),
                        "Successfully built panteratestpkg",
                        "Installing collected packages: panteratestpkg",
                        "Successfully installed panteratestpkg-0.0.3"
                    )
                )
            ),
            "python", "-m", "pip", "install", "--trusted-host", "pantera", "--index-url",
            String.format("http://pantera:%s/%s", port, repo),
            "panteratestpkg"
        );
        this.containers.assertExec(
            "panteratestpkg-0.0.3.tar.gz must exist in S3 storage after test",
            new ContainerResultMatcher(new IsEqual<>(0)),
            "curl -f -kv http://minioc:%s/buck1/my-python/panteratestpkg/panteratestpkg-0.0.3.tar.gz".formatted(s3port, repo).split(" ")
        );
    }

    @ParameterizedTest
    @CsvSource("8080,my-python,9000")
    //"8081,my-python-port,9000" todo https://github.com/pantera/pantera/issues/1350
    void canUpload(final String port, final String repo, final String s3port) throws Exception {
        this.containers.assertExec(
            "panteratestpkg-0.0.3.tar.gz must not exist in S3 storage after test",
            new ContainerResultMatcher(new IsEqual<>(PypiS3ITCase.CURL_NOT_FOUND)),
            "curl -f -kv http://minioc:%s/buck1/my-python/panteratestpkg/panteratestpkg-0.0.3.tar.gz".formatted(s3port, repo).split(" ")
        );
        this.containers.assertExec(
            "Failed to upload",
            new ContainerResultMatcher(
                Matchers.is(0),
                new StringContainsInOrder(
                    new ListOf<>(
                        "Uploading panteratestpkg-0.0.3.tar.gz", "100%"
                    )
                )
            ),
            "python3", "-m", "twine", "upload", "--repository-url",
            String.format("http://pantera:%s/%s/", port, repo),
            "-u", "alice", "-p", "123",
            "/w/example-pckg/dist/panteratestpkg-0.0.3.tar.gz"
        );
        this.containers.assertExec(
            "panteratestpkg-0.0.3.tar.gz must exist in S3 storage after test",
            new ContainerResultMatcher(new IsEqual<>(0)),
            "curl -f -kv http://minioc:%s/buck1/my-python/panteratestpkg/panteratestpkg-0.0.3.tar.gz".formatted(s3port, repo).split(" ")
        );
    }
}
