package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Method;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 读取服务器开菜单时附带的原生位置；预先打开的终端也必须遵守本次仓库范围和保护规则。 */
final class Ae2DepositAccess {
    record Bound(Object locator, BlockPos position, Direction side, Integer itemSlot) {
        Bound { if (position != null) position = position.immutable(); }
    }
    private Ae2DepositAccess() {}
    static Bound read(AbstractContainerMenu menu, LocalPlayer player) {
        Object locator = call(menu, "getLocator");
        if (locator == null) throw new Ae2ProtocolException("AE2 deposit terminal has no native locator");
        return switch (locator.getClass().getName()) {
            case "appeng.menu.locator.PartLocator" -> new Bound(locator, (BlockPos) call(locator, "pos"), (Direction) call(locator, "side"), null);
            case "appeng.menu.locator.BlockEntityLocator" -> new Bound(locator, (BlockPos) call(locator, "pos"), null, null);
            case "appeng.menu.locator.InventoryItemLocator" -> {
                int slot = ((Number) call(locator, "getPlayerInventorySlot")).intValue();
                if (slot < 0 || slot >= 36 || Ae2TerminalAccess.findWireless(player, slot, slot) == null)
                    throw new Ae2ProtocolException("AE2 deposit wireless access is not a carried recognized terminal");
                yield new Bound(locator, null, null, slot);
            }
            default -> throw new Ae2ProtocolException("AE2 deposit native access type is unsupported: " + locator.getClass().getSimpleName());
        };
    }
    static boolean current(Bound bound, AbstractContainerMenu menu, LocalPlayer player, Ae2ReflectionBridge bridge) {
        if (call(menu, "getLocator") != bound.locator) return false;
        if (bound.itemSlot != null) return Ae2TerminalAccess.findWireless(player, bound.itemSlot, bound.itemSlot) != null;
        if (!player.level().isLoaded(bound.position)) return false;
        Object entity = player.level().getBlockEntity(bound.position);
        return bound.side == null ? call(menu, "getHost") == entity
                : bridge.matchesFixedTerminalMenu(menu, entity, bound.side);
    }
    static boolean sameTerminalIgnoringEnergy(net.minecraft.world.item.ItemStack before, net.minecraft.world.item.ItemStack after) {
        if (before.getCount() != after.getCount() || before.getItem() != after.getItem()) return false;
        // 无线终端在原生存入时会消耗自身电量；只在副本上忽略这一项，名称、绑定、升级等组件仍须完全一致。
        var energy = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(net.minecraft.resources.ResourceLocation.parse("ae2:stored_energy"));
        var left = before.copy(); var right = after.copy();
        if (energy != null) { left.remove(energy); right.remove(energy); }
        return net.minecraft.world.item.ItemStack.isSameItemSameComponents(left, right);
    }
    private static Object call(Object target, String method) {
        try {
            Method read = target.getClass().getMethod(method); read.setAccessible(true); return read.invoke(target);
        } catch (ReflectiveOperationException unavailable) { throw new Ae2ProtocolException("AE2 deposit access observation unavailable: " + method, unavailable); }
    }
}
