// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** 按精妙注册的本人库存处理器观察背包，主背包、胸甲和饰品槽各保留自己的原生地址。 */
public final class BackpackCarriers {
    private static final String PROVIDER = "net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider";
    private static final String CONSUMER = PROVIDER + "$BackpackInventorySlotConsumer";
    public record Carrier(String handler, String identifier, int slot) {
        public Carrier {
            if (handler == null || handler.isBlank() || handler.length() > 128 || identifier == null || identifier.length() > 256 || slot < 0)
                throw new IllegalArgumentException("invalid native backpack inventory reference");
        }
        public int vanillaSlot() {
            if (!identifier.isEmpty()) return -1;
            if (handler.equals("main") && slot < 36) return slot;
            return handler.equals("offhand") && slot == 0 ? 40 : -1;
        }
        public String key() { return handler + ":" + identifier + ":" + slot; }
        public static Carrier vanilla(int slot) {
            if (slot < 0 || slot >= 36 && slot != 40) throw new IllegalArgumentException("unsupported main inventory or offhand slot");
            return slot == 40 ? new Carrier("offhand", "", 0) : new Carrier("main", "", slot);
        }
    }
    public record Entry(Carrier carrier, ItemStack stack) {
        public Entry { stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    public record Observation(List<Entry> entries, String problem) {
        public Observation { entries = List.copyOf(entries); }
        public boolean complete() { return problem == null; }
    }
    private BackpackCarriers() {}

    public static Observation observe(LocalPlayer player) {
        boolean providerLoaded = false;
        try {
            Class<?> type = Class.forName(PROVIDER); providerLoaded = true;
            Class<?> callback = Class.forName(CONSUMER);
            Object provider = type.getMethod("get").invoke(null);
            var entries = new ArrayList<Entry>(); boolean[] limited = {false};
            Object collector = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getName().equals("accept") && args != null && args.length == 4) {
                    if (entries.size() >= 64) { limited[0] = true; return true; }
                    ItemStack stack = (ItemStack) args[0];
                    if (BackpackMenuAccess.isBackpack(stack)) entries.add(new Entry(new Carrier((String) args[1], (String) args[2], (int) args[3]), stack));
                    return false;
                }
                return switch (method.getName()) { case "toString" -> "MaiCraft backpack observer";
                    case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0];
                    default -> throw new IllegalArgumentException("unsupported backpack inventory callback"); };
            });
            // 已安装旧版返回void，新版返回boolean；这里只收回调中的现成物品，不依赖返回值或创建存储包装器。
            type.getMethod("runOnBackpacks", Player.class, callback).invoke(provider, player, collector);
            return new Observation(entries, limited[0] ? "carried_backpack_observation_limit" : null);
        } catch (ClassNotFoundException absent) {
            return new Observation(vanillaEntries(player), providerLoaded ? "native_backpack_callback_unavailable" : null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // API不可读时仍显示已知主背包物品，但附未知范围；不能把穿戴背包直接报告成不存在。
            return new Observation(vanillaEntries(player), "native_backpack_inventory_unavailable:" + unavailable.getClass().getSimpleName());
        }
    }

    public static ItemStack current(LocalPlayer player, Carrier carrier) {
        int vanilla = carrier.vanillaSlot();
        if (vanilla >= 0) return player.getInventory().getItem(vanilla);
        try {
            Class<?> type = Class.forName(PROVIDER); Object provider = type.getMethod("get").invoke(null);
            var optional = (Optional<?>) type.getMethod("getPlayerInventoryHandler", String.class).invoke(provider, carrier.handler());
            if (optional.isEmpty()) return ItemStack.EMPTY;
            Object handler = optional.get(); Class<?> api = handler.getClass();
            var identifiers = (Set<?>) api.getMethod("getIdentifiers", Player.class).invoke(handler, player);
            if (!identifiers.contains(carrier.identifier())) return ItemStack.EMPTY;
            int count = (int) api.getMethod("getSlotCount", Player.class, String.class).invoke(handler, player, carrier.identifier());
            if (carrier.slot() >= count) return ItemStack.EMPTY;
            return (ItemStack) api.getMethod("getStackInSlot", Player.class, String.class, int.class)
                    .invoke(handler, player, carrier.identifier(), carrier.slot());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw new IllegalStateException("native worn backpack inventory cannot be verified", unavailable);
        }
    }
    private static List<Entry> vanillaEntries(LocalPlayer player) {
        var entries = new ArrayList<Entry>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (BackpackMenuAccess.isBackpack(stack)) entries.add(new Entry(Carrier.vanilla(slot), stack));
        }
        if (BackpackMenuAccess.isBackpack(player.getOffhandItem())) entries.add(new Entry(Carrier.vanilla(40), player.getOffhandItem()));
        return entries;
    }
}
