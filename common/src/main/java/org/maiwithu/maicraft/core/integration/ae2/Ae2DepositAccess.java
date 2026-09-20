package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Method;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** 从客户端真正持有的菜单 host 绑定访问来源；不依赖 AE2 只保存在服务端菜单上的 locator。 */
final class Ae2DepositAccess {
    record Bound(Object host, BlockPos position, Direction side, Integer itemSlot, BlockEntity blockEntity, ItemStack itemBefore) {
        Bound {
            if (position != null) position = position.immutable();
            itemBefore = itemBefore == null ? ItemStack.EMPTY : itemBefore.copy();
        }
    }
    private Ae2DepositAccess() {}
    static Bound read(AbstractContainerMenu menu, LocalPlayer player, Ae2ReflectionBridge bridge, Ae2TerminalAccess.FixedTarget fixed) {
        Object host = call(menu, "getHost");
        if (host == null) throw new Ae2ProtocolException("AE2 deposit client menu has no native host");
        if (fixed != null) {
            // 刚刚实际打开过的固定终端优先用已观察坐标和面，且该面当前仍须装着菜单的同一个 part。
            BlockEntity entity = loadedEntity(player, fixed.position());
            if (entity == null || entity.getLevel() != player.level() || !bridge.matchesFixedTerminalMenu(menu, entity, fixed.side()))
                throw new Ae2ProtocolException("AE2 deposit fixed target does not own the current client menu host");
            return new Bound(host, fixed.position(), fixed.side(), null, entity, null);
        }
        if (host instanceof BlockEntity entity) {
            if (loadedEntity(player, entity.getBlockPos()) != entity || entity.getLevel() != player.level())
                throw new Ae2ProtocolException("AE2 deposit block-entity host is no longer in the current loaded world");
            return new Bound(host, entity.getBlockPos(), null, null, entity, null);
        }
        if (has(host, "getPlayerInventorySlot") && has(host, "getItemStack")) {
            Object rawSlot = call(host, "getPlayerInventorySlot");
            if (!(rawSlot instanceof Integer slot) || !wirelessPresent(host, player, slot))
                throw new Ae2ProtocolException("AE2 deposit wireless host has no verified carried inventory slot");
            return new Bound(host, null, null, slot, null, player.getInventory().getItem(slot));
        }
        // 预先打开的固定菜单没有本次 fixedTarget；从实际 part 的公开宿主与面读取，不猜最近的终端。
        if (has(host, "getBlockEntity") && has(host, "getSide") && call(host, "getBlockEntity") instanceof BlockEntity entity
                && call(host, "getSide") instanceof Direction side && loadedEntity(player, entity.getBlockPos()) == entity
                && entity.getLevel() == player.level() && bridge.matchesFixedTerminalMenu(menu, entity, side))
            return new Bound(host, entity.getBlockPos(), side, null, entity, null);
        throw new Ae2ProtocolException("AE2 deposit client host has no verifiable fixed or carried access: " + host.getClass().getName());
    }
    static boolean current(Bound bound, AbstractContainerMenu menu, LocalPlayer player, Ae2ReflectionBridge bridge) {
        if (call(menu, "getHost") != bound.host) return false;
        if (bound.itemSlot != null) return wirelessPresent(bound.host, player, bound.itemSlot)
                && sameTerminalIgnoringEnergy(bound.itemBefore, player.getInventory().getItem(bound.itemSlot));
        BlockEntity entity = loadedEntity(player, bound.position);
        if (entity == null || entity != bound.blockEntity || entity.getLevel() != player.level()) return false;
        return bound.side == null ? bound.host == entity
                : call(bound.host, "getBlockEntity") == entity && call(bound.host, "getSide") == bound.side
                        && bridge.matchesFixedTerminalMenu(menu, entity, bound.side);
    }
    private static BlockEntity loadedEntity(LocalPlayer player, BlockPos position) {
        if (!player.level().isLoaded(position)) return null;
        BlockEntity entity = player.level().getBlockEntity(position);
        return entity == null || entity.isRemoved() ? null : entity;
    }
    private static boolean wirelessPresent(Object host, LocalPlayer player, int slot) {
        return slot >= 0 && slot < 36 && Ae2TerminalAccess.findWireless(player, slot, slot) != null
                && call(host, "getPlayer") == player && Integer.valueOf(slot).equals(call(host, "getPlayerInventorySlot"))
                && call(host, "getItemStack") == player.getInventory().getItem(slot);
    }
    private static boolean has(Object target, String method) {
        try { target.getClass().getMethod(method); return true; }
        catch (NoSuchMethodException unavailable) { return false; }
    }
    static boolean sameTerminalIgnoringEnergy(ItemStack before, ItemStack after) {
        if (before.getCount() != after.getCount() || before.getItem() != after.getItem()) return false;
        // 无线终端在原生存入时会消耗自身电量；只在副本上忽略这一项，名称、绑定、升级等组件仍须完全一致。
        var energy = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("ae2:stored_energy"));
        var left = before.copy(); var right = after.copy();
        if (energy != null) { left.remove(energy); right.remove(energy); }
        return ItemStack.isSameItemSameComponents(left, right);
    }
    private static Object call(Object target, String method) {
        try {
            Method read = target.getClass().getMethod(method); read.setAccessible(true); return read.invoke(target);
        } catch (ReflectiveOperationException unavailable) { throw new Ae2ProtocolException("AE2 deposit access observation unavailable: " + method, unavailable); }
    }
}
