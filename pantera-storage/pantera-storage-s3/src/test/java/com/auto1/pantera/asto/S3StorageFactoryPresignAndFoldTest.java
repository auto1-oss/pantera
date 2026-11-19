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

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMappingBuilder;
import com.auto1.pantera.asto.blob.Presigner;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.asto.s3.S3Storage;
import java.lang.reflect.Field;
import java.net.URI;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNull;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.s3.model.StorageClass;

/**
 * Factory-level coverage for WS1.0: proves (a) a storage built via the ordinary
 * {@code s3}/{@code s3-express} config path supports presigning end to end with
 * the configured custom endpoint and path-style honored, and (b) the
 * {@code S3ExpressStorageFactory} fold into {@code S3StorageFactory} (spec
 * &sect;I) preserved both defaults it used to hardcode -- path-style off and
 * storage class {@code EXPRESS_ONEZONE} -- while making both overridable via the
 * ordinary {@code path-style} / {@code storage-class} config keys. All offline:
 * presigning is local signing, so no MinIO/S3 server is needed.
 */
@Timeout(10)
final class S3StorageFactoryPresignAndFoldTest {

    @Test
    void s3StorageSupportsPresignWithConfiguredEndpointAndPathStyle() {
        final Storage storage = S3StorageFactoryPresignAndFoldTest.storage("s3", "true", null);
        MatcherAssert.assertThat(storage, new IsInstanceOf(Presigner.class));
        final URI url = ((Presigner) storage).presignGet(new Key.From("g/a/1.0/a-1.0.jar"), 300);
        MatcherAssert.assertThat("custom endpoint host honored", url.getHost(), new IsEqual<>("minio.local"));
        MatcherAssert.assertThat(
            "path-style: true puts the bucket in the path",
            url.getPath(),
            new StringStartsWith("/pantera-bucket/")
        );
    }

    @Test
    void s3StorageHonorsExplicitVirtualHostedStyle() {
        final Storage storage = S3StorageFactoryPresignAndFoldTest.storage("s3", "false", null);
        final URI url = ((Presigner) storage).presignGet(new Key.From("k"), 300);
        MatcherAssert.assertThat(
            "path-style: false puts the bucket in the host",
            url.getHost(),
            new IsEqual<>("pantera-bucket.minio.local")
        );
    }

    @Test
    void s3DefaultsToPathStyleAndStandardStorageClass() throws ReflectiveOperationException {
        final Storage storage = S3StorageFactoryPresignAndFoldTest.storage("s3", null, null);
        MatcherAssert.assertThat(
            "s3 defaults storage-class to null (S3 STANDARD)",
            S3StorageFactoryPresignAndFoldTest.storageClassOf(storage),
            new IsNull<>()
        );
        final URI url = ((Presigner) storage).presignGet(new Key.From("k"), 300);
        MatcherAssert.assertThat("s3 defaults to path-style on", url.getHost(), new IsEqual<>("minio.local"));
    }

    @Test
    void s3ExpressFoldPreservesVirtualHostedAndExpressOneZoneDefaults() throws ReflectiveOperationException {
        final Storage storage = S3StorageFactoryPresignAndFoldTest.storage("s3-express", null, null);
        MatcherAssert.assertThat(
            "s3-express still defaults storage-class to EXPRESS_ONEZONE after the fold",
            S3StorageFactoryPresignAndFoldTest.storageClassOf(storage),
            new IsEqual<>(StorageClass.EXPRESS_ONEZONE)
        );
        final URI url = ((Presigner) storage).presignGet(new Key.From("k"), 300);
        MatcherAssert.assertThat(
            "s3-express still defaults to virtual-hosted-style after the fold",
            url.getHost(),
            new IsEqual<>("pantera-bucket.minio.local")
        );
    }

    @Test
    void s3ExpressStillAcceptsExplicitPathStyleAndStorageClassOverrides() throws ReflectiveOperationException {
        final Storage storage = S3StorageFactoryPresignAndFoldTest.storage("s3-express", "true", "STANDARD_IA");
        MatcherAssert.assertThat(
            "explicit storage-class overrides the s3-express default",
            S3StorageFactoryPresignAndFoldTest.storageClassOf(storage),
            new IsEqual<>(StorageClass.STANDARD_IA)
        );
        final URI url = ((Presigner) storage).presignGet(new Key.From("k"), 300);
        MatcherAssert.assertThat(
            "explicit path-style: true overrides the s3-express default",
            url.getHost(),
            new IsEqual<>("minio.local")
        );
    }

    private static Object storageClassOf(final Storage storage) throws ReflectiveOperationException {
        final Field field = S3Storage.class.getDeclaredField("storageClass");
        field.setAccessible(true);
        return field.get(storage);
    }

    private static Storage storage(final String type, final String pathStyle, final String storageClass) {
        YamlMappingBuilder builder = Yaml.createYamlMappingBuilder()
            .add("region", "us-east-1")
            .add("bucket", "pantera-bucket")
            .add("endpoint", "http://minio.local:9000")
            .add(
                "credentials",
                Yaml.createYamlMappingBuilder()
                    .add("type", "basic")
                    .add("accessKeyId", "AKIAFAKE")
                    .add("secretAccessKey", "secretFake")
                    .build()
            );
        if (pathStyle != null) {
            builder = builder.add("path-style", pathStyle);
        }
        if (storageClass != null) {
            builder = builder.add("storage-class", storageClass);
        }
        return StoragesLoader.STORAGES.newObject(type, new Config.YamlStorageConfig(builder.build()));
    }
}
