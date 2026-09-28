package org.maiwithu.maicraft.core.integration.ae2;

import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 分别给出两种流体单位，检查背包与网络先后同步时应继续等待，多拿一桶或多消耗水则不能确认成一次灌水。
public final class Ae2WaterBucketFillTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
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
        // 已安装无限单元报告Integer.MAX_VALUE个单位且提取后不递减；回执应保留实际读数而非伪造扣水。
        long infinite = (long) Integer.MAX_VALUE * 1000;
        var constant = new Ae2WaterBucketFill(0, 0, 1, infinite, 1000);
        check(constant.observe(1, 0, 0, infinite, true) == NativeConfirmation.Verdict.APPLIED, "constant water stock is compatible with an exact bucket exchange");
        check(constant.confirmedData().get("network_water_after").equals(infinite)
                && Boolean.FALSE.equals(constant.confirmedData().get("exact_water_debit_observed")), "actual unchanged water quantity remains explicit");
        check(Ae2WaterBucketFill.stagingAmount(3, 1, 1, 5) == 1 && Ae2WaterBucketFill.stagingAmount(1, 0, 1, 5) == 0,
                "stage only the missing empty containers");
        try (var h = new InteractionWorldTestHarness()) {
            var stock = new StockEvidence.Snapshot(StockEvidence.Source.AE2, Map.of(), Set.of(), 1);
            h.inventory.setItem(0, new ItemStack(Items.BUCKET));
            check(Ae2WaterBucketFill.probeCapacity(h.player, List.of(Ae2WaterBucketFill.WATER_BUCKET), 3, stock) == 1,
                    "one carried bucket permits a native water-fill query despite zero finished water buckets");
            check(Ae2WaterBucketFill.probeCapacity(h.player, List.of(Ae2WaterBucketFill.EMPTY_BUCKET), 1, stock) == 0,
                    "other requested items cannot use the water-fill exception");
        }
        System.out.println("Ae2WaterBucketFillTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
