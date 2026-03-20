/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm.asto;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.rpm.Digest;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Observable;
import io.reactivex.Single;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Checksums and names of the storage items.
 * @since 1.10
 */
public final class AstoChecksumAndName {

    /**
     * Asto storage.
     */
    private final Storage asto;

    /**
     * Digest algorithm.
     */
    private final Digest dgst;

    /**
     * Ctor.
     * @param asto Asto storage
     * @param dgst Digest algorithm
     */
    public AstoChecksumAndName(final Storage asto, final Digest dgst) {
        this.asto = asto;
        this.dgst = dgst;
    }

    /**
     * Calculate checksum of all the items found by key, that ends with `.rpm`.
     * @param key Storage key
     * @return Map with item name and checksum
     */
    public CompletionStage<Map<String, String>> calculate(final Key key) {
        final RxStorageWrapper rxsto = new RxStorageWrapper(this.asto);
        return rxsto.list(key)
            .flatMapObservable(Observable::fromIterable)
            .filter(item -> item.string().endsWith(".rpm"))
            .flatMapSingle(
                // Use non-blocking RxFuture.single instead of blocking Single.fromFuture
                item -> rxsto.value(item).flatMap(
                    cnt -> com.auto1.pantera.asto.rx.RxFuture.single(
                        new ContentDigest(cnt, this.dgst::messageDigest).hex().toCompletableFuture()
                    )
                ).map(hex -> new ImmutablePair<>(keyPart(key, item), hex))
            ).toMap(Pair::getKey, Pair::getValue)
            .to(SingleInterop.get());
    }

    /**
     * Key part without initial part.
     * @param exclude Initial key part to exclude
     * @param item Item key
     * @return Item key without part to exclude
     */
    private static String keyPart(final Key exclude, final Key item) {
        String res = item.string();
        if (!exclude.equals(Key.ROOT)) {
            res = item.string().substring(exclude.string().length() + 1);
        }
        return res;
    }

}
