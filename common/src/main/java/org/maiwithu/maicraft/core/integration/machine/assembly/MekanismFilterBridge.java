// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuVisibility;

/**
 * 集中读取和提交 Mekanism 物流分拣机的过滤设置；必须是这台分拣机的真实菜单且界面可见。
 * 当前只支持“一个指定普通物品”的单条精确过滤，不会清空其他过滤规则来强行满足请求。
 */
public final class MekanismFilterBridge {
    public enum Decision { READY, ADD, EDIT, CONFLICT }
    public record FilterView(boolean itemStackFilter, String itemId, boolean enabled, boolean exactComponents,
            boolean fuzzy, boolean allowDefault, boolean sizeMode, boolean colored) {
        public boolean desired(String requested) {
            return itemStackFilter && itemId.equals(requested) && enabled && exactComponents
                    && !fuzzy && !allowDefault && !sizeMode && !colored;
        }
    }
    public record Snapshot(boolean autoEject, List<FilterView> filters, List<Object> originals, MekanismFilterSync.Snapshot sync) {
        public Snapshot { filters = List.copyOf(filters); originals = List.copyOf(originals); }
    }
    private MekanismFilterBridge() {}

    // 没有过滤规则就新增；只有一条同物品规则时可调整；多条规则或其他种类一律报告冲突。
    public static Decision decide(List<FilterView> filters, String requested) {
        if (filters.isEmpty()) return Decision.ADD;
        if (filters.size() != 1) return Decision.CONFLICT;
        FilterView filter = filters.getFirst();
        if (!filter.itemStackFilter() || !filter.itemId().equals(requested)) return Decision.CONFLICT;
        return filter.desired(requested) ? Decision.READY : Decision.EDIT;
    }

    public static Object requireMenu(LocalPlayer player, BlockPos position) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            AbstractContainerMenu menu = player.containerMenu;
            if (!player.level().isLoaded(position) || !MenuVisibility.matches(minecraft, menu)
                    || !Class.forName("mekanism.client.gui.GuiLogisticalSorter").isInstance(minecraft.screen)
                    || !Class.forName("mekanism.common.inventory.container.tile.MekanismTileContainer").isInstance(menu)) {
                throw new IllegalArgumentException("the native Logistical Sorter menu must be visibly open");
            }
            Object tile = menu.getClass().getMethod("getTileEntity").invoke(menu);
            if (!Class.forName("mekanism.common.tile.TileEntityLogisticalSorter").isInstance(tile)
                    || tile != player.level().getBlockEntity(position)) throw new IllegalArgumentException("sorter menu belongs to another target");
            return tile;
        } catch (ReflectiveOperationException | LinkageError unavailable) { throw new IllegalArgumentException("installed Sorter menu API unavailable", unavailable); }
    }

    // 复制现有规则并读取自动弹出开关，同时带上服务器同步编号；不能把客户端尚未同步的默认值当成真实设置。
    public static Snapshot inspect(LocalPlayer player, BlockPos position) {
        Object tile = requireMenu(player, position);
        try {
            Object manager = MachineCommissioning.call(tile, "getFilterManager");
            List<FilterView> views = new ArrayList<>(); List<Object> originals = new ArrayList<>();
            for (Object filter : (Iterable<?>) MachineCommissioning.call(manager, "getFilters")) {
                Object frozen = MachineCommissioning.call(filter, "clone"); originals.add(frozen);
                boolean itemFilter = filter.getClass().getName().equals("mekanism.common.content.transporter.SorterItemStackFilter");
                ItemStack stack = itemFilter ? (ItemStack) MachineCommissioning.call(filter, "getItemStack") : ItemStack.EMPTY;
                String item = stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                views.add(new FilterView(itemFilter, item, (Boolean) MachineCommissioning.call(filter, "isEnabled"),
                        !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem())),
                        itemFilter && filter.getClass().getField("fuzzyMode").getBoolean(filter),
                        filter.getClass().getField("allowDefault").getBoolean(filter),
                        filter.getClass().getField("sizeMode").getBoolean(filter), filter.getClass().getField("color").get(filter) != null));
            }
            return new Snapshot((Boolean) MachineCommissioning.call(tile, "getAutoEject"), views, originals,
                    MekanismFilterSync.snapshot(player.containerMenu));
        } catch (ReflectiveOperationException | LinkageError unavailable) { throw new IllegalArgumentException("installed Sorter filter API unavailable", unavailable); }
    }

    // 原生按钮会反转开关，因此发包前必须确认它仍然开着，不能把已经关闭的开关又打开。
    public static void disableAutoEject(LocalPlayer player, BlockPos position) {
        Object tile = requireMenu(player, position);
        try {
            if (!(Boolean) MachineCommissioning.call(tile, "getAutoEject")) throw new IllegalStateException("auto eject changed before its bounded toggle");
            Class<?> interaction = Class.forName("mekanism.common.network.to_server.PacketGuiInteract$GuiInteraction");
            Object action = interaction.getField("AUTO_EJECT_BUTTON").get(null);
            Object packet = Class.forName("mekanism.common.network.to_server.PacketGuiInteract")
                    .getConstructor(interaction, net.minecraft.world.level.block.entity.BlockEntity.class).newInstance(action, tile);
            send(packet);
        } catch (ReflectiveOperationException | LinkageError unavailable) { throw new IllegalArgumentException("native Sorter auto-eject action unavailable", unavailable); }
    }

    // 再核对规则未变且自动弹出已关闭，然后使用原生新增／编辑过滤请求；不直接改客户端过滤列表。
    public static void saveItemFilter(LocalPlayer player, BlockPos position, ResourceLocation itemId, Snapshot before) {
        requireMenu(player, position);
        Snapshot live = inspect(player, position);
        if (!live.originals().equals(before.originals()) || live.autoEject()) throw new IllegalStateException("sorter changed before saving its filter");
        Decision decision = decide(live.filters(), itemId.toString());
        if (decision != Decision.ADD && decision != Decision.EDIT) throw new IllegalStateException("sorter filter does not need this mutation");
        try {
            Class<?> filterClass = Class.forName("mekanism.common.content.transporter.SorterItemStackFilter");
            Object desired = filterClass.getConstructor().newInstance();
            filterClass.getMethod("setItemStack", ItemStack.class).invoke(desired, new ItemStack(BuiltInRegistries.ITEM.get(itemId)));
            // Defaults are enabled, strict components, unrestricted amount, no color and no default-flow bypass.
            Class<?> api = Class.forName("mekanism.common.content.filter.IFilter");
            Object packet = decision == Decision.ADD
                    ? Class.forName("mekanism.common.network.to_server.filter.PacketNewFilter")
                        .getConstructor(BlockPos.class, api).newInstance(position, desired)
                    : Class.forName("mekanism.common.network.to_server.filter.PacketEditFilter")
                        .getConstructor(BlockPos.class, api, api).newInstance(position, before.originals().getFirst(), desired);
            send(packet);
        } catch (ReflectiveOperationException | LinkageError unavailable) { throw new IllegalArgumentException("native Sorter filter action unavailable", unavailable); }
    }

    private static void send(Object packet) throws ReflectiveOperationException {
        Class.forName("mekanism.common.network.PacketUtils").getMethod("sendToServer", CustomPacketPayload.class).invoke(null, packet);
    }
}
