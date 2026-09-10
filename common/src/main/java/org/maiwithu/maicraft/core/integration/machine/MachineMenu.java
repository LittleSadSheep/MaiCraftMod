// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;
import org.maiwithu.maicraft.task.TaskFactory;

/**
 * 保存“这个菜单是从哪台机器打开的”，并把某次观察做成只能使用一次的存取依据。
 * 这里集中登记菜单任务、读取槽位、判断槽位背后是什么容器；具体点击由打开、关闭、存取三个执行器处理。
 */
public final class MachineMenu {
    static final int MAX_ENTRIES = 512;
    private static final int MAX_RECEIPTS = 16;
    private static final long RECEIPT_TICKS = 600;
    private static final Map<AbstractContainerMenu, Origin> ORIGINS = new WeakHashMap<>();
    private static final Map<UUID, Inspection> INSPECTIONS = new LinkedHashMap<>();

    private MachineMenu() {}

    public record OpenRequest(String dimension, BlockPos center, int radius,
            String structuralFingerprint, BlockPos machinePosition) {
        public OpenRequest {
            if (machinePosition == null) throw new IllegalArgumentException("machine position is required");
            var region = new MachineSnapshots.Region(dimension, center, radius, structuralFingerprint);
            center = region.center();
            machinePosition = region.requirePosition(machinePosition);
        }
    }

    record Origin(WeakReference<LocalPlayer> player, String dimension,
            BlockPos position, ResourceLocation blockId, long bodyEpoch) {}

    static final class Inspection {
        final WeakReference<LocalPlayer> player;
        final WeakReference<AbstractContainerMenu> menu;
        final Origin origin;
        final long bodyEpoch;
        final long expires;
        final int stateId;
        final List<WeakReference<Slot>> entries;
        final List<ItemStack> contents;

        Inspection(LocalPlayer self, AbstractContainerMenu menu, Origin origin,
                   org.maiwithu.maicraft.client.actor.ClientActorBoundary.ObservationStamp stamp) {
            this.player = new WeakReference<>(self);
            this.menu = new WeakReference<>(menu);
            this.origin = origin;
            this.bodyEpoch = stamp.bodyEpoch();
            this.expires = stamp.tickRevision() + RECEIPT_TICKS;
            this.stateId = menu.getStateId();
            this.entries = menu.slots.stream().map(WeakReference::new).toList();
            this.contents = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
        }
    }

    public static void install() {
        TaskFactory.register(MachineMenuOpenTaskRecord.class, MachineMenuOpenTask::new);
        TaskFactory.register(MachineMenuTransferTaskRecord.class, MachineMenuTransferTask::new);
        TaskFactory.register(MachineMenuCloseTaskRecord.class, MachineMenuCloseTask::new);
    }

    public static MachineMenuOpenTaskRecord openTask(String callId, long deadline, OpenRequest request) {
        install();
        return new MachineMenuOpenTaskRecord(callId, deadline, request);
    }

    public static MachineMenuTransferTaskRecord transferTask(String callId, long deadline,
            String menuReceipt, String operation, int observedEntry, ResourceLocation itemId, int count) {
        install();
        return new MachineMenuTransferTaskRecord(callId, deadline, menuReceipt,
                operation, observedEntry, itemId, count);
    }

    public static MachineMenuCloseTaskRecord closeTask(String callId, long deadline) {
        install();
        return new MachineMenuCloseTaskRecord(callId, deadline);
    }

    static void bindOpened(LocalPlayer player, AbstractContainerMenu menu, OpenRequest request,
            ResourceLocation blockId) {
        ORIGINS.put(menu, new Origin(new WeakReference<>(player), request.dimension(),
                request.machinePosition(), blockId, ClientRuntime.requireContext(player).bodyEpoch()));
    }

    /**
     * 读取当前菜单和实际携带物品。只有本流程打开、仍可见、鼠标没有拿物品且来源仍有效的菜单才给存取编号。
     * 编号最多保留十六份，约六百次角色更新后过期；报告不解释配方或控制数据的业务含义。
     */
    public static JsonObject inspect(LocalPlayer self) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.player != self)
            throw new IllegalArgumentException("menu inspection requires the current client-thread player");
        var stamp = ClientRuntime.actor().observationStamp(self).orElse(null);
        AbstractContainerMenu menu = self.containerMenu;
        JsonObject out = new JsonObject();
        out.addProperty("schema_version", 1);
        out.addProperty("menu_class", menu.getClass().getName());
        boolean visible = MenuVisibility.matches(minecraft, menu);
        out.addProperty("gui_visible", visible);
        out.addProperty("menu_state_revision", menu.getStateId());
        out.addProperty("evidence_source", "native_client_menu");
        out.addProperty("machine_production_verified", false);
        out.addProperty("entry_roles_verified", false);
        out.addProperty("native_inventory_transactions_verified", false);
        out.addProperty("data_meanings_verified", false);
        out.addProperty("cursor_empty", menu.getCarried().isEmpty());
        out.addProperty("menu_entry_count", menu.slots.size());
        out.addProperty("truncated", menu.slots.size() > MAX_ENTRIES);
        JsonArray entries = new JsonArray();
        for (int index = 0; index < Math.min(MAX_ENTRIES, menu.slots.size()); index++) {
            Slot slot = menu.slots.get(index);
            JsonObject entry = new JsonObject();
            entry.addProperty("entry_index", index);
            entry.addProperty("entry_class", slot.getClass().getName());
            entry.addProperty("backing_class", slot.container.getClass().getName());
            entry.addProperty("side", slot.container == self.getInventory() ? "player" : "machine");
            entry.addProperty("backing_evidence", backingEvidence(slot));
            entry.addProperty("transfer_supported", transferable(slot));
            entry.addProperty("may_take", slot.mayPickup(self));
            entry.addProperty("active", slot.isActive());
            entry.add("stack", item(slot.getItem()));
            JsonArray screen = new JsonArray(); screen.add(slot.x); screen.add(slot.y);
            entry.add("screen_offset", screen);
            JsonArray acceptable = new JsonArray();
            // These are actual carried candidate stacks, not inferred recipes or arbitrary slot roles.
            java.util.Set<String> acceptedIds = new java.util.LinkedHashSet<>();
            for (int inventory = 0; inventory < 36 && acceptable.size() < 16; inventory++) {
                ItemStack candidate = self.getInventory().getItem(inventory);
                if (candidate.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString();
                // 至少有一叠通过检查才合并该种类；前一叠的组件不合适，不能排除后一叠。
                if (acceptedIds.contains(id) || !slot.mayPlace(candidate)) continue;
                acceptedIds.add(id);
                acceptable.add(id);
            }
            entry.add("accepts_carried_item_ids", acceptable);
            entries.add(entry);
        }
        out.add("menu_entries", entries);
        JsonArray values = new JsonArray();
        if ((Object) menu instanceof MenuDataSlotsAccessor accessor) {
            var data = accessor.maicraft$dataSlots();
            for (int index = 0; index < Math.min(128, data.size()); index++) values.add(data.get(index).get());
            out.addProperty("data_values_truncated", data.size() > 128);
        }
        out.add("data_values", values);
        Origin origin = ORIGINS.get(menu);
        boolean validOrigin = stamp != null && origin != null && origin.player().get() == self
                && origin.bodyEpoch() == stamp.bodyEpoch()
                && origin.dimension().equals(self.level().dimension().location().toString());
        boolean canTransfer = visible && validOrigin && menu != self.inventoryMenu && menu.getCarried().isEmpty()
                && menu.slots.size() <= MAX_ENTRIES && !virtualMenu(menu)
                && self.level().isLoaded(origin.position())
                && BuiltInRegistries.BLOCK.getKey(self.level().getBlockState(origin.position()).getBlock()).equals(origin.blockId())
                && !org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext.protectsMutation(origin.position());
        out.addProperty("transfer_receipt_available", canTransfer);
        if (canTransfer) {
            INSPECTIONS.entrySet().removeIf(entry -> entry.getValue().player.get() == null
                    || entry.getValue().menu.get() == null || entry.getValue().expires < stamp.tickRevision());
            while (INSPECTIONS.size() >= MAX_RECEIPTS) INSPECTIONS.remove(INSPECTIONS.keySet().iterator().next());
            UUID id = UUID.randomUUID();
            INSPECTIONS.put(id, new Inspection(self, menu, origin, stamp));
            out.addProperty("menu_receipt_id", id.toString());
        }
        out.addProperty("transfer_contract", "one receipt permits one exact deposit/withdraw of 1..64 items against one observed entry; no swaps, quick-move, ghost filters or recipe claims");
        if (!visible) out.addProperty("transfer_unavailable_reason", "the machine GUI must be visibly open before inspection can authorize a transfer");
        else if (!validOrigin) out.addProperty("transfer_unavailable_reason", "open this exact machine through the machine menu operation first");
        else if (virtualMenu(menu)) out.addProperty("transfer_unavailable_reason", "virtual storage uses a dedicated integration such as AE2 supply");
        if (visible) org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply.observeOpenWaterInventory(menu)
                .ifPresent(water -> out.add("ae2_water_inventory", water));
        return out;
    }

    // 先移除一次性编号，再核对玩家、菜单、身体版本、有效期、槽位对象和所有内容；失败后也需重新观察。
    static Inspection consume(String token, LocalPlayer player) {
        final UUID id;
        try { id = UUID.fromString(token); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid menu receipt"); }
        Inspection inspection = INSPECTIONS.remove(id);
        if (inspection == null) throw new IllegalArgumentException("menu receipt is unknown, expired or already used");
        var context = ClientRuntime.requireContext(player);
        AbstractContainerMenu menu = player.containerMenu;
        if (inspection.player.get() != player || inspection.menu.get() != menu
                || !MenuVisibility.matches(context.minecraft(), menu)
                || inspection.bodyEpoch != context.bodyEpoch() || inspection.expires < context.tickRevision()
                || menu.getStateId() != inspection.stateId || !menu.getCarried().isEmpty()
                || !sameEntries(inspection, menu)) {
            throw new IllegalArgumentException("the observed menu session changed; inspect it again");
        }
        for (int index = 0; index < menu.slots.size(); index++) {
            if (!same(menu.getSlot(index).getItem(), inspection.contents.get(index))) {
                throw new IllegalArgumentException("menu contents changed since inspection; inspect it again");
            }
        }
        return inspection;
    }

    // 比较的是同一批槽位对象和顺序，仅菜单类型相同还不够。
    static boolean sameEntries(Inspection inspection, AbstractContainerMenu menu) {
        if (inspection.menu.get() != menu || inspection.entries.size() != menu.slots.size()) return false;
        for (int index = 0; index < menu.slots.size(); index++) {
            if (menu.getSlot(index) != inspection.entries.get(index).get()) return false;
        }
        return true;
    }

    static boolean virtualMenu(AbstractContainerMenu menu) {
        return MachineMenuPolicy.dedicatedStorageMenu(menu.getClass().getName());
    }

    static boolean ownedMenu(LocalPlayer player, AbstractContainerMenu menu) {
        Origin origin = ORIGINS.get(menu);
        return origin != null && origin.player().get() == player
                && origin.bodyEpoch() == ClientRuntime.requireContext(player).bodyEpoch()
                && origin.dimension().equals(player.level().dimension().location().toString());
    }

    /** Class evidence supplements slot acceptance rules; generic virtual render entries fail closed. */
    // 先排除结果／虚拟槽，再核对直接容器或已知模组存储接口；无法解释的自定义 getter 不当作已证实的普通库存。
    static String backingEvidence(Slot slot) {
        if (slot instanceof ResultSlot || MachineMenuPolicy.virtualEntryName(slot.getClass().getName())) return "virtual_or_recipe_entry";
        try {
            // 这里写死了开发环境的方法名。Fabric 发布包会映射原版方法名而保留这个字符串，因此普通槽位会查找失败并返回未知。
            Class<?> getter = slot.getClass().getMethod("getItem").getDeclaringClass();
            if (getter == Slot.class && slot.getContainerSlot() >= 0
                    && slot.getContainerSlot() < slot.container.getContainerSize()) return "native_container_backing";
            String owner = getter.getName();
            if (owner.equals("appeng.menu.slot.AppEngSlot")
                    && slot.getClass().getName().equals("appeng.menu.slot.RestrictedInputSlot")) {
                Object backing = slot.getClass().getMethod("getSlotInv").invoke(slot);
                Class<?> api = Class.forName("appeng.api.inventories.InternalInventory", false, slot.getClass().getClassLoader());
                if (api.isInstance(backing) && ((Number) api.getMethod("size").invoke(backing)).intValue() == 1
                        && api.getMethod("getStackInSlot", int.class).invoke(backing, 0) instanceof ItemStack stack
                        && same(stack, slot.getItem())) return "ae2_physical_inventory_slot_backing";
            }
            if (owner.equals("mekanism.common.inventory.container.slot.InventoryContainerSlot")) {
                Object backing = slot.getClass().getMethod("getInventorySlot").invoke(slot);
                Class<?> api = Class.forName("mekanism.api.inventory.IInventorySlot", false, slot.getClass().getClassLoader());
                Method getStack = api.getMethod("getStack");
                if (api.isInstance(backing) && getStack.invoke(backing) instanceof ItemStack stack
                        && same(stack, slot.getItem())) {
                    return "mekanism_inventory_slot_backing";
                }
            }
            if (owner.equals("net.neoforged.neoforge.items.SlotItemHandler")
                    || owner.equals("net.minecraftforge.items.SlotItemHandler")) {
                Object handler = slot.getClass().getMethod("getItemHandler").invoke(slot);
                String interfaceName = owner.startsWith("net.neoforged.")
                        ? "net.neoforged.neoforge.items.IItemHandler" : "net.minecraftforge.items.IItemHandler";
                Class<?> api = Class.forName(interfaceName, false, slot.getClass().getClassLoader());
                if (!api.isInstance(handler)) return "unverified_backing";
                int size = ((Number) api.getMethod("getSlots").invoke(handler)).intValue();
                int index = slot.getContainerSlot();
                if (index >= 0 && index < size && api.getMethod("getStackInSlot", int.class)
                        .invoke(handler, index) instanceof ItemStack stack && same(stack, slot.getItem())) {
                    return "item_handler_backing";
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "unverified_backing";
        }
        return "unverified_backing";
    }

    static boolean transferable(Slot slot) {
        return switch (backingEvidence(slot)) {
            case "native_container_backing", "mekanism_inventory_slot_backing", "item_handler_backing",
                    "ae2_physical_inventory_slot_backing" -> true;
            default -> false;
        };
    }

    static boolean same(ItemStack first, ItemStack second) {
        return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject out = new JsonObject();
        out.addProperty("empty", stack.isEmpty());
        if (!stack.isEmpty()) {
            out.addProperty("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            out.addProperty("count", stack.getCount());
            out.addProperty("display_name", stack.getHoverName().getString());
            out.addProperty("component_fingerprint", Integer.toHexString(stack.getComponents().hashCode()));
        }
        return out;
    }
}
