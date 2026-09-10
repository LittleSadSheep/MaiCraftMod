package org.maiwithu.maicraft.core.integration.ae2;

import java.util.Map;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/**
 * 记录一次用网络空桶与水灌出一桶水的前后数量，要求背包、网络空桶和水量一起符合预期；不会把玩家原有空桶当作网络空桶消耗。
 */
record Ae2WaterBucketFill(int waterBuckets, int carriedEmptyBuckets,
                         long networkEmptyBuckets, long waterUnits, long unitsPerBucket) {
    static final ResourceLocation EMPTY_BUCKET = ResourceLocation.parse("minecraft:bucket");
    static final ResourceLocation WATER_BUCKET = ResourceLocation.parse("minecraft:water_bucket");
    static boolean supports(Ae2ResourceSupply.Request request) {
        return request.operation() == Ae2ResourceSupply.Operation.SUPPLY && request.groups().size() == 1
                && request.groups().getFirst().acceptableItemIds().equals(List.of(WATER_BUCKET));
    }
    static long count(List<Ae2ReflectionBridge.Entry> entries, ResourceLocation item) {
        return entries.stream().filter(entry -> entry.itemId().equals(item))
                .filter(entry -> !item.equals(EMPTY_BUCKET) || entry.sample().getComponentsPatch().isEmpty())
                .mapToLong(Ae2ReflectionBridge.Entry::storedAmount).sum();
    }
    Ae2WaterBucketFill {
        if (unitsPerBucket <= 0 || waterUnits < unitsPerBucket || networkEmptyBuckets < 1)
            throw new IllegalArgumentException("one network bucket and one native bucket-volume of water are required");
    }

    NativeConfirmation.Verdict observe(int afterWater, int afterCarriedEmpty,
            long afterNetworkEmpty, long afterWaterUnits, boolean cursorEmpty) {
        if (afterWater == waterBuckets + 1 && afterCarriedEmpty == carriedEmptyBuckets
                && afterNetworkEmpty == networkEmptyBuckets - 1 && afterWaterUnits == waterUnits - unitsPerBucket
                && cursorEmpty) return NativeConfirmation.Verdict.APPLIED;
        if (afterWater < waterBuckets || afterWater > waterBuckets + 1 || afterCarriedEmpty != carriedEmptyBuckets
                || afterNetworkEmpty < networkEmptyBuckets - 1 || afterNetworkEmpty > networkEmptyBuckets
                || afterWaterUnits < waterUnits - unitsPerBucket || afterWaterUnits > waterUnits)
            return NativeConfirmation.Verdict.DIVERGED;
        return NativeConfirmation.Verdict.PENDING;
    }

    Map<String, Object> confirmedData() {
        return Map.of("fluid_id", "minecraft:water", "units_per_bucket", unitsPerBucket,
                "unit", "ae_native", "water_inventory_before", waterBuckets, "water_inventory_after", waterBuckets + 1,
                "network_empty_buckets_before", networkEmptyBuckets, "network_empty_buckets_after", networkEmptyBuckets - 1,
                "network_water_before", waterUnits, "network_water_after", waterUnits - unitsPerBucket);
    }
}
