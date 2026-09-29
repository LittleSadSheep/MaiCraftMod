// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/** 只读取本人已打开并同步的精妙背包主存储；升级槽、幽灵槽和未同步菜单都不冒充可用物资。 */
public final class BackpackMenuAccess {
    private static final String ITEM = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem";
    private static final String MENU = "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";
    public record Snapshot(AbstractContainerMenu menu, String storageId, ItemStack backpack, List<Integer> storageSlots,
                           Map<Integer, Integer> playerSlots, Map<ResourceLocation, Long> stored,
                           Map<ResourceLocation, Long> extractable, Set<Integer> infiniteSlots, long observedTick) {
        public Snapshot {
            backpack = backpack.copy(); storageSlots = List.copyOf(storageSlots); playerSlots = Map.copyOf(playerSlots);
            stored = Map.copyOf(stored); extractable = Map.copyOf(extractable); infiniteSlots = Set.copyOf(infiniteSlots);
        }
    }
    public record Read(String status, String detail, Snapshot snapshot) {}
    private BackpackMenuAccess() {}

    public static boolean isBackpack(ItemStack stack) { return !stack.isEmpty() && inherits(stack.getItem().getClass(), ITEM); }
    public static boolean supports(AbstractContainerMenu menu) { return inherits(menu.getClass(), MENU); }

    public static Read read(LocalPlayer player) {
        // 世界或身体已经切换时，旧菜单不能继续给新任务证明还有哪些物品。
        if (player == null || player != Minecraft.getInstance().player || player.clientLevel != Minecraft.getInstance().level)
            return new Read("runtime_unavailable", "the local backpack owner is unavailable", null);
        var menu = player.containerMenu;
        if (!supports(menu)) return new Read("not_open", "no carried Sophisticated Backpacks menu is open", null);
        if (!MenuVisibility.matches(Minecraft.getInstance(), menu) || !StockEvidence.isContainerSynchronized(player, menu))
            return new Read("awaiting_sync", "the visible backpack has not received native inventory synchronization", null);
        try {
            if (!Boolean.TRUE.equals(call(menu, "isUpgradeColumnCountSynced")))
                return new Read("awaiting_sync", "backpack column layout is not synchronized", null);
            Object context = call(menu, "getBackpackContext");
            if (!"ITEM_BACKPACK".equals(String.valueOf(call(context, "getType"))))
                return new Read("scope_mismatch", "only the local player's carried top-level backpack is included", null);
            Object wrapper = call(menu, "getStorageWrapper");
            Object rawStack = call(wrapper, "getBackpack");
            if (!(rawStack instanceof ItemStack stack) || !isBackpack(stack) || !menu.stillValid(player))
                return new Read("ownership_unverified", "the native menu no longer identifies a valid carried backpack", null);
            // 旧版 getContentsUuid 会迁移并写回旧组件；观察只读现成组件，不能顺手替玩家改背包数据。
            // 尚未确认新版链接端点的规范主机时，只保留本菜单身份，不能把物理端点冒充独立仓库累加。
            String identity = wrapper.getClass().getSimpleName().equals("BackpackWrapper")
                    ? storedIdentity(stack, menu) : transientIdentity(menu);
            int size = ((Number) call(menu, "getNumberOfStorageInventorySlots")).intValue();
            Method storage = menu.getClass().getMethod("isStorageInventorySlot", int.class);
            Method inaccessible = menu.getClass().getMethod("isInaccessibleSlot", int.class);
            Method infinite = menu.getClass().getMethod("isInfiniteSlot", int.class);
            return new Read("observed", "synchronized native backpack storage slots", capture(player, menu, identity, stack, size,
                    slot -> bool(menu, storage, slot), slot -> bool(menu, inaccessible, slot), slot -> bool(menu, infinite, slot)));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return new Read("unavailable", "native backpack observation could not be verified: " + unavailable.getClass().getSimpleName(), null);
        }
    }

    static String storedIdentity(ItemStack stack, AbstractContainerMenu menu) {
        String persistent = contentsIdentity(stack);
        // 新空包或旧格式未提供现成标识时，身份仅限这次菜单对象；下次打开必须重新观察。
        return persistent == null ? transientIdentity(menu) : persistent;
    }
    /** 读取现成内容标识供随身库存去重，不调用模组会迁移旧数据的 getter。 */
    public static String contentsIdentity(ItemStack stack) {
        for (var component : stack.getComponents()) {
            if ("sophisticatedcore:storage_uuid".equals(String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type())))
                    && component.value() instanceof UUID uuid) return "sophisticated_backpack:" + uuid;
        }
        return null;
    }
    private static String transientIdentity(AbstractContainerMenu menu) {
        return "backpack_menu:" + menu.containerId + ":" + Integer.toUnsignedString(System.identityHashCode(menu));
    }

    static Snapshot capture(LocalPlayer player, AbstractContainerMenu menu, String identity, ItemStack backpack, int expected,
            IntPredicate storage, IntPredicate inaccessible, IntPredicate infinite) {
        if (expected < 0 || expected > 4096) throw new IllegalArgumentException("unsupported backpack storage size");
        var slots = new ArrayList<Integer>(); var unlimited = new LinkedHashSet<Integer>();
        var stored = new LinkedHashMap<ResourceLocation, Long>(); var extractable = new LinkedHashMap<ResourceLocation, Long>();
        for (int index = 0; index < menu.slots.size(); index++) {
            if (!storage.test(index)) continue;
            var slot = menu.getSlot(index); slots.add(index);
            if (infinite.test(index)) unlimited.add(index);
            // 数量来自真实槽内物品；记忆过滤器的图标和升级物品都不会加入这份主存储计数。
            ItemStack stack = slot.getItem(); if (stack.isEmpty()) continue;
            ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
            stored.merge(item, (long) stack.getCount(), Math::addExact);
            if (!inaccessible.test(index) && slot.mayPickup(player)) extractable.merge(item, (long) stack.getCount(), Math::addExact);
        }
        if (slots.size() != expected) throw new IllegalArgumentException("backpack storage layout differs from its native count");
        var main = new LinkedHashMap<Integer, Integer>();
        for (int slot = 0; slot < 36; slot++) {
            var found = menu.findSlot(player.getInventory(), slot);
            if (found.isPresent() && found.getAsInt() >= 0 && found.getAsInt() < menu.slots.size()
                    && !storage.test(found.getAsInt())) main.put(slot, found.getAsInt());
        }
        return new Snapshot(menu, identity, backpack, slots, main, stored, extractable, unlimited, player.level().getGameTime());
    }

    private static boolean inherits(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) if (current.getName().equals(name)) return true;
        return false;
    }
    private static Object call(Object owner, String method) throws ReflectiveOperationException {
        return owner.getClass().getMethod(method).invoke(owner);
    }
    private static boolean bool(Object owner, Method method, int slot) {
        try { return Boolean.TRUE.equals(method.invoke(owner, slot)); }
        catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("native backpack slot role unavailable", unavailable); }
    }
}
