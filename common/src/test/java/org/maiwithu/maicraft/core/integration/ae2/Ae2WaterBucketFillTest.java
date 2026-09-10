package org.maiwithu.maicraft.core.integration.ae2;

import org.maiwithu.maicraft.client.actor.NativeConfirmation;

// 分别给出两种流体单位，检查背包与网络先后同步时应继续等待，多拿一桶或多消耗水则不能确认成一次灌水。
public final class Ae2WaterBucketFillTest {
    public static void main(String[] args) {
        for (long bucketUnits : new long[]{1000, 81000}) {
            var fill = new Ae2WaterBucketFill(2, 3, 8, 4 * bucketUnits, bucketUnits);
            check(fill.observe(2, 3, 8, 4 * bucketUnits, true) == NativeConfirmation.Verdict.PENDING,
                    "unchanged facts are not confirmation");
            check(fill.observe(3, 3, 8, 4 * bucketUnits, true) == NativeConfirmation.Verdict.PENDING,
                    "inventory may synchronize before the network");
            check(fill.observe(2, 3, 7, 3 * bucketUnits, true) == NativeConfirmation.Verdict.PENDING,
                    "the network may synchronize before inventory");
            check(fill.observe(3, 3, 7, 3 * bucketUnits, false) == NativeConfirmation.Verdict.PENDING,
                    "the cursor must settle as well");
            check(fill.observe(3, 3, 7, 3 * bucketUnits, true) == NativeConfirmation.Verdict.APPLIED,
                    "one native unit of filling needs all three inventory deltas");
            check(fill.observe(4, 3, 7, 3 * bucketUnits, true) == NativeConfirmation.Verdict.DIVERGED,
                    "extra full buckets cannot pass as one approved fill");
            check(fill.observe(3, 2, 7, 3 * bucketUnits, true) == NativeConfirmation.Verdict.DIVERGED,
                    "a player-carried bucket was not authorized as the network bucket");
            check(fill.observe(3, 3, 6, 3 * bucketUnits, true) == NativeConfirmation.Verdict.DIVERGED,
                    "two consumed empty buckets are not one fill");
            check(fill.observe(3, 3, 7, 2 * bucketUnits, true) == NativeConfirmation.Verdict.DIVERGED,
                    "extra fluid extraction is not a valid receipt");
            check(fill.confirmedData().get("units_per_bucket").equals(bucketUnits),
                    "the receipt retains the loader's own fluid unit");
        }
        System.out.println("Ae2WaterBucketFillTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
