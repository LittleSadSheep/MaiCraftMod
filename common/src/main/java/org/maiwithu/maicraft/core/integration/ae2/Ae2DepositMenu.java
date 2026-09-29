package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** 只读核对 AE2 原生 Shift 的目的地规则；不让满网时的后备动作改动合成格、升级槽或过滤设置。 */
final class Ae2DepositMenu {
    private final Method playerSide, destination;
    private final Class<?> fakeSlot;
    private final Class<?> baseMenu, storageMenu;
    Ae2DepositMenu() {
        try {
            Class<?> base = Class.forName("appeng.menu.AEBaseMenu");
            baseMenu = base; storageMenu = Class.forName("appeng.menu.me.common.MEStorageMenu");
            playerSide = base.getMethod("isPlayerSideSlot", Slot.class);
            destination = base.getDeclaredMethod("getQuickMoveDestinationSlots", ItemStack.class, boolean.class);
            destination.setAccessible(true);
            fakeSlot = Class.forName("appeng.menu.slot.FakeSlot");
        } catch (ReflectiveOperationException unavailable) { throw new Ae2ProtocolException("AE2 native deposit slot rules unavailable", unavailable); }
    }
    Map<Integer, Integer> playerSlots(AbstractContainerMenu menu, LocalPlayer player) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i); int index = slot.getContainerSlot();
            if (slot.container != player.getInventory() || index < 0 || index >= 36) continue;
            if (!invoke(playerSide, menu, slot) || result.put(index, i) != null)
                throw new Ae2ProtocolException("AE2 deposit cannot prove unique native player inventory slots");
        }
        if (result.size() != 36) throw new Ae2ProtocolException("AE2 deposit menu does not expose all ordinary player slots");
        return Map.copyOf(result);
    }
    boolean safeFallback(AbstractContainerMenu menu, ItemStack sample) {
        Object destinations = call(destination, menu, sample, true);
        if (!(destinations instanceof List<?> slots) || !slots.isEmpty()) return false;
        for (Slot slot : menu.slots) {
            if (invoke(playerSide, menu, slot)) continue;
            if (fakeSlot.isInstance(slot)) return false;
        }
        return true;
    }
    /** 原生 AE 只在服务器接受入网后扣玩家槽；覆写转移方法的菜单不能沿用这份接收证明。 */
    boolean networkOnlyShift(AbstractContainerMenu menu) {
        if (!storageMenu.isInstance(menu)) return false;
        try {
            if (menu.getClass().getMethod("quickMoveStack", Player.class, int.class).getDeclaringClass() != baseMenu) return false;
            for (Class<?> type = menu.getClass(); type != null; type = type.getSuperclass()) {
                try { return type.getDeclaredMethod("transferStackToMenu", ItemStack.class).getDeclaringClass() == storageMenu; }
                catch (NoSuchMethodException inherited) { /* 只沿继承链找实际实现，不调用转移或插入方法。 */ }
            }
            return false;
        } catch (ReflectiveOperationException unavailable) {
            throw new Ae2ProtocolException("AE2 native network-only shift contract unavailable", unavailable);
        }
    }
    private static boolean invoke(Method method, Object receiver, Object... args) {
        return Boolean.TRUE.equals(call(method, receiver, args));
    }
    private static Object call(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (ReflectiveOperationException unavailable) { throw new Ae2ProtocolException("AE2 native deposit slot read failed", unavailable); }
    }
}
