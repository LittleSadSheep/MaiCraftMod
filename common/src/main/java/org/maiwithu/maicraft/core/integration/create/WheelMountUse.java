package org.maiwithu.maicraft.core.integration.create;

import java.util.Map;
import java.util.LinkedHashMap;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Offroad 在服务端交换轮胎，再同步轮座槽位；方块外观状态不变也能确认一次真实安装或取下。 */
public final class WheelMountUse implements NativeConfirmation {
    private final Level level;
    private final BlockPos position;
    private final Object entity;
    private final ItemStack before;
    private ItemStack observed;

    private WheelMountUse(Level level, BlockPos position, Object entity, ItemStack before) {
        this.level = level; this.position = position.immutable(); this.entity = entity; this.before = before.copy();
    }

    public static WheelMountUse prepare(Level level, BlockPos position) {
        if (!level.isLoaded(position) || !BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock())
                .toString().equals("offroad:wheel_mount")) return null;
        Object entity = level.getBlockEntity(position);
        ItemStack held = read(entity);
        return held == null ? null : new WheelMountUse(level, position, entity, held);
    }

    @Override public Verdict observe(LocalPlayerContext context) {
        // 始终读取同一个已加载轮座；拆掉或替换轮座不能被当作原来的轮胎交换已完成。
        if (context.level() != level || !level.isLoaded(position) || level.getBlockEntity(position) != entity)
            return Verdict.PENDING;
        observed = read(entity);
        return changed(before, observed);
    }

    static Verdict changed(ItemStack before, ItemStack after) {
        // 同种同组件轮胎互换可能没有可见差异，继续保持未知，不能仅凭目标已经有轮胎就认领本次点击。
        return after == null || before.getCount() == after.getCount() && ItemStack.isSameItemSameComponents(before, after)
                ? Verdict.PENDING : Verdict.APPLIED;
    }

    public Map<String, Object> evidence() {
        return Map.of("position", position.toShortString(), "before", stack(before),
                "after", observed == null ? Map.of("observed", false) : stack(observed),
                "source", "native_wheel_mount_slot", "slot_changed", changed(before, observed) == Verdict.APPLIED);
    }

    private Map<String, Object> stack(ItemStack stack) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); facts.put("count", stack.getCount());
        // 轮胎的组件也属于实际安装物，组件替换时不能只返回看似未变的物品名和数量。
        if (!stack.isEmpty()) {
            try {
                var encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), stack).result();
                facts.put("components_observed", encoded.isPresent()); encoded.ifPresent(value -> facts.put("stack", value));
            } catch (RuntimeException | LinkageError unavailable) { facts.put("components_observed", false); }
        }
        return facts;
    }

    private static ItemStack read(Object entity) {
        // 可选模组未提供读取接口时只放弃这项确认线索，原生点击仍沿用通用确认，不写轮座或玩家背包。
        if (entity == null) return null;
        try {
            Object held = NativeApi.call(entity, null, "getHeldItem");
            return held instanceof ItemStack stack ? stack.copy() : null;
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }
}
