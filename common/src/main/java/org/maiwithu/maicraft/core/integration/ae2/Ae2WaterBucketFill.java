package org.maiwithu.maicraft.core.integration.ae2;

import java.util.Map;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/**
 * 记录一次用网络空桶与水灌出一桶水的前后数量，要求背包、网络空桶和水量一起符合预期；不会把玩家原有空桶当作网络空桶消耗。
 */
final class Ae2WaterBucketFill {
    private final int waterBuckets, carriedEmptyBuckets;
    private final long networkEmptyBuckets, waterUnits, unitsPerBucket;
    private long confirmedWaterUnits;
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
    Ae2WaterBucketFill(int waterBuckets, int carriedEmptyBuckets, long networkEmptyBuckets, long waterUnits, long unitsPerBucket) {
        if (unitsPerBucket <= 0 || waterUnits < unitsPerBucket || networkEmptyBuckets < 1)
            throw new IllegalArgumentException("one network bucket and one native bucket-volume of water are required");
        this.waterBuckets = waterBuckets; this.carriedEmptyBuckets = carriedEmptyBuckets; this.networkEmptyBuckets = networkEmptyBuckets;
        this.waterUnits = confirmedWaterUnits = waterUnits; this.unitsPerBucket = unitsPerBucket;
    }

    static int carriedBuckets(LocalPlayer player) {
        // 只使用普通空桶；命名或带自定义组件的桶不借用，保留用户的特殊物品身份。
        return player.getInventory().items.stream().limit(36).filter(stack -> stack.is(Items.BUCKET)
                && ItemStack.isSameItemSameComponents(stack, Items.BUCKET.getDefaultInstance())).mapToInt(ItemStack::getCount).sum();
    }
    static int stagingAmount(int requested, int filled, long networkBuckets, int carried) {
        return (int) Math.min(carried, Math.max(0L, (long) requested - filled - networkBuckets));
    }
    static int probeCapacity(LocalPlayer player, List<ResourceLocation> ids, int missing, StockEvidence.Snapshot stock) {
        // 成品桶为零时仍可进入已有的原生灌水检查；这只是空容器上限，实际存水须在可见终端重新观察。
        if (!ids.equals(List.of(WATER_BUCKET))) return 0;
        long network = Math.min(Integer.MAX_VALUE, stock.storedCount(EMPTY_BUCKET));
        return (int) Math.min(missing, network + carriedBuckets(player));
    }

    NativeConfirmation.Verdict observe(int afterWater, int afterCarriedEmpty,
            long afterNetworkEmpty, long afterWaterUnits, boolean cursorEmpty) {
        if (afterWater == waterBuckets + 1 && afterCarriedEmpty == carriedEmptyBuckets
                && afterNetworkEmpty == networkEmptyBuckets - 1 && afterWaterUnits >= waterUnits - unitsPerBucket && afterWaterUnits <= waterUnits
                && cursorEmpty) {
            // 无限水单元或同时补水的网络可保持水量；产出桶与空桶的精确反向变化仍须同时由原生回执证实。
            confirmedWaterUnits = afterWaterUnits;
            return NativeConfirmation.Verdict.APPLIED;
        }
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
                "network_water_before", waterUnits, "network_water_after", confirmedWaterUnits,
                "exact_water_debit_observed", confirmedWaterUnits == waterUnits - unitsPerBucket);
    }
}
