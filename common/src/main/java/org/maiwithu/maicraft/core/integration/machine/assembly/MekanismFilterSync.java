// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 记住当前分拣机菜单是否收到了服务器的过滤列表和自动弹出状态，防止把本地默认值当作已同步的设置。
 */
public final class MekanismFilterSync {
    public record Snapshot(long filtersRevision, long autoEjectRevision) {
        public boolean ready() { return filtersRevision > 0 && autoEjectRevision > 0; }
    }
    private static final Map<AbstractContainerMenu, Snapshot> RECEIVED = new WeakHashMap<>();
    private static long sequence;
    private MekanismFilterSync() {}

    public static Snapshot snapshot(AbstractContainerMenu menu) {
        return RECEIVED.getOrDefault(menu, new Snapshot(0, 0));
    }

    /**
     * 在模组处理完服务器菜单属性后记录编号；这里只记“更新已经收到”，不替模组写入属性。
     */
    public static void received(AbstractContainerMenu menu, short property, boolean filterList) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.player == null || minecraft.player.containerMenu != menu) return;
        try {
            Class<?> type = Class.forName("mekanism.common.inventory.container.MekanismContainer");
            if (!type.isInstance(menu)) return;
            Object tile = menu.getClass().getMethod("getTileEntity").invoke(menu);
            if (!Class.forName("mekanism.common.tile.TileEntityLogisticalSorter").isInstance(tile)) return;
            var field = type.getDeclaredField("trackedData");
            if (!field.trySetAccessible()) return;
            Object raw = field.get(menu);
            if (!(raw instanceof List<?> data) || data.size() < 5) return;
            // 当前依赖 Mekanism 跟踪属性列表末尾五项的类型和顺序；版本改了排列时会停止认定同步成功。
            int last = data.size() - 1;
            // TileEntityLogisticalSorter.addContainerTrackers appends auto/roundRobin/single/color/filters.
            if (!named(data.get(last), "list.SyncableFilterList") || !named(data.get(last - 1), "SyncableInt")
                    || !named(data.get(last - 2), "SyncableBoolean") || !named(data.get(last - 3), "SyncableBoolean")
                    || !named(data.get(last - 4), "SyncableBoolean")) return;
            Snapshot previous = snapshot(menu);
            if (filterList && property == last) RECEIVED.put(menu, new Snapshot(++sequence, previous.autoEjectRevision()));
            else if (!filterList && property == last - 4) RECEIVED.put(menu, new Snapshot(previous.filtersRevision(), ++sequence));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Optional API drift means no evidence; the task reports its bounded synchronization failure.
        }
    }
    private static boolean named(Object value, String suffix) {
        return value != null && value.getClass().getName().equals("mekanism.common.inventory.container.sync." + suffix);
    }
}
