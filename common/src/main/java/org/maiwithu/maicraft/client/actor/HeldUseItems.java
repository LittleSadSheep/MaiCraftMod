package org.maiwithu.maicraft.client.actor;

import com.mojang.serialization.DynamicOps;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** 只判断正在持用的同一物品；模组组件 equals 异常时比较原生序列化内容，避免同步包把砂纸误判为换物松手。 */
public final class HeldUseItems {
    private HeldUseItems() {}
    public static boolean same(ItemStack left, ItemStack right, Supplier<HolderLookup.Provider> registries) {
        if (ItemStack.isSameItemSameComponents(left, right)) return true;
        if (!ItemStack.isSameItem(left, right) || !left.getComponents().keySet().equals(right.getComponents().keySet())) return false;
        // 数量扣减由原生持用确认负责；物品、全部组件、手、槽位和倒计时仍各自维持身份约束。
        try {
            var provider = registries.get();
            DynamicOps<Tag> ops = provider == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, provider);
            for (var type : left.getComponents().keySet()) if (!component(type, left, right, ops)) return false;
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }
    private static <T> boolean component(DataComponentType<T> type, ItemStack left, ItemStack right, DynamicOps<Tag> ops) {
        T before = left.get(type), after = right.get(type);
        if (before == after || Objects.equals(before, after)) return true;
        // 无可用序列化证据的瞬态组件仍拒绝接管，不能只因物品名称相同就续用另一份工具。
        if (type.codec() == null) return false;
        var a = type.codec().encodeStart(ops, before).result();
        var b = type.codec().encodeStart(ops, after).result();
        return a.isPresent() && b.isPresent() && a.get().equals(b.get());
    }
}
