// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Proxy;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuPort;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 复用真实背包、菜单槽和演员夹具验证附魔准备与取消边界；不把本地造出的附魔品伪称为服务端闭环成功。 */
public final class EnchantWorkflowGuardTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        exactPreparation(); unavailableMaterials(); sourceChanges(); foreignMenusAndHandoff(); terminalChildCleanup(); resultDisplayAndProgress();
        System.out.println("EnchantWorkflowGuardTest: inventory selection, foreign-menu guards and terminal cleanup passed");
    }

    private static void exactPreparation() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            ItemStack books = new ItemStack(Items.BOOK, 8);
            books.set(DataComponents.CUSTOM_NAME, Component.literal("准备附魔的一叠书"));
            world.inventory.setItem(0, books); world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI));
            world.inventory.setItem(2, new ItemStack(Items.LAPIS_LAZULI, 2));
            var inventory = EnchantInventory.prepare(world.player, record(Items.BOOK));
            var menu = new EnchantmentMenu(57, world.inventory); world.player.containerMenu = menu;
            var itemMove = inventory.loadInput(menu).getFirst(); var lapisMoves = inventory.loadLapis(menu);
            // 原菜单槽的来源仍是背包第零格，只取一本；两叠青金石分别精确取一份和两份，不扩大到整堆。
            check(itemMove.count() == 1 && itemMove.to() == 0 && menu.getSlot(itemMove.from()).getContainerSlot() == 0,
                    "a stacked book produces an exact one-item menu move");
            check(inventory.input.getCount() == 1 && inventory.input.get(DataComponents.CUSTOM_NAME).equals(books.get(DataComponents.CUSTOM_NAME)),
                    "the selected book keeps all source components");
            check(lapisMoves.size() == 2 && lapisMoves.stream().mapToInt(move -> move.count()).sum() == 3
                    && lapisMoves.stream().allMatch(move -> move.to() == 1), "compatible lapis stacks supply exactly one tier");
            check(books.getCount() == 8 && menu.getSlot(0).getItem().isEmpty() && menu.getCarried().isEmpty(),
                    "preparation and move planning never mutate real slots or the cursor");
            world.player.getAbilities().instabuild = true;
            check(EnchantInventory.prepare(world.player, record(Items.BOOK)).lapisNeeded == 0,
                    "creative material preparation does not invent a lapis debit");
        }
    }

    private static void unavailableMaterials() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            for (int i = 0; i < 36; i++) world.inventory.setItem(i, new ItemStack(Items.STONE, 64));
            world.inventory.setItem(0, new ItemStack(Items.BOOK, 8)); world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
            rejects(() -> EnchantInventory.prepare(world.player, record(Items.BOOK)), "space");
            // 单件装备会腾出自己的格，因此满背包也可原位取回；书仍堆叠在来源格时则必须先有额外空位。
            world.inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            check(EnchantInventory.prepare(world.player, record(Items.DIAMOND_SWORD)).input.getCount() == 1,
                    "a single tool reserves the slot it will vacate");
            world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 2));
            rejects(() -> EnchantInventory.prepare(world.player, record(Items.DIAMOND_SWORD)), "lapis");
            world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
            rejects(() -> EnchantInventory.prepare(world.player, record(Items.STONE)), "item_missing");
        }
    }

    private static void sourceChanges() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD)); world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
            var inventory = EnchantInventory.prepare(world.player, record(Items.DIAMOND_SWORD));
            var menu = new EnchantmentMenu(57, world.inventory); world.player.containerMenu = menu;
            world.inventory.getItem(0).setDamageValue(12);
            rejects(() -> inventory.loadInput(menu), "source_changed");
            check(menu.getSlot(0).getItem().isEmpty(), "a changed source must be rejected before pickup");
        }
    }

    private static void foreignMenusAndHandoff() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) try (var world = new InteractionWorldTestHarness()) {
            world.inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD)); world.inventory.setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
            var record = record(Items.DIAMOND_SWORD); var inventory = EnchantInventory.prepare(world.player, record);
            var menu = new EnchantmentMenu(57, world.inventory); world.player.containerMenu = menu;
            var flow = new EnchantMenuFlow(world.player, record, menu, inventory,
                    () -> { throw new AssertionError("a guard failure must never reserve consumption"); });
            if (scenario == 0) menu.getSlot(0).set(new ItemStack(Items.DIAMOND));
            if (scenario == 1) menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
            if (scenario == 2) world.player.containerMenu = new EnchantmentMenu(menu.containerId, world.inventory);
            // 相同编号的替换菜单、首次装料前的外来材料和手动接管都在任何搬运或消费之前停止。
            check(flow.tick(context(scenario != 3)) == TaskState.FAILED, "foreign contents, replaced menus and handoff stop the workflow");
            flow.cleanup();
            check(!Boolean.TRUE.equals(flow.data().get("button_attempted")) && !Boolean.TRUE.equals(flow.data().get("gui_closed")),
                    "a rejected guard neither consumes nor claims to have closed the player's menu");
            if (scenario == 0) check(menu.getSlot(0).getItem().is(Items.DIAMOND), "foreign input remains untouched");
            if (scenario == 1) check(menu.getSlot(1).getItem().getCount() == 3, "matching but unowned lapis remains untouched");
        }
    }

    private static void terminalChildCleanup() throws Exception {
        for (TaskState state : new TaskState[]{TaskState.CANCELLED, TaskState.TIMEOUT}) try (var world = new InteractionWorldTestHarness()) {
            var task = new EnchantCompanionTask(world.player, record(Items.DIAMOND_SWORD));
            var child = new CleanupProbe();
            var field = EnchantCompanionTask.class.getDeclaredField("activeChild"); field.setAccessible(true); field.set(task, child);
            task.result(state);
            // 父任务被取消或超时都必须结束子任务回执；只调用 stop 松键而跳过 result 会漏掉 GUI 清理。
            check(child.stops == 1 && child.results == 1, "every terminal parent path finalizes its child exactly once");
        }
    }

    private static LocalPlayerContext context(boolean permitted) {
        MenuPort port = (MenuPort) Proxy.newProxyInstance(MenuPort.class.getClassLoader(), new Class<?>[]{MenuPort.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("ensureVisible")) return true;
                    throw new AssertionError("guard unexpectedly mutated the menu: " + method.getName());
                });
        return (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "permitsNativeActions" -> permitted;
                    case "menus" -> port;
                    default -> throw new AssertionError("unexpected guard context call: " + method.getName());
                });
    }

    private static void resultDisplayAndProgress() throws Exception {
        // 这里只验证现有组件的展示和只读进度，不模拟服务端确认，也不把测试快照宣布为附魔成功。
        var registry = new MappedRegistry<Enchantment>(Registries.ENCHANTMENT, Lifecycle.stable());
        var definition = Enchantment.definition(HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                1, 5, Enchantment.constantCost(1), Enchantment.constantCost(30), 1, EquipmentSlotGroup.MAINHAND);
        var unbreaking = Registry.registerForHolder(registry, Enchantments.UNBREAKING,
                new Enchantment(Component.literal("耐久"), definition, HolderSet.direct(), DataComponentMap.EMPTY));
        var sharpness = Registry.registerForHolder(registry, Enchantments.SHARPNESS,
                new Enchantment(Component.literal("锋利"), definition, HolderSet.direct(), DataComponentMap.EMPTY));
        registry.freeze();
        for (boolean book : new boolean[]{false, true}) try (var world = new InteractionWorldTestHarness()) {
            world.player.getAbilities().instabuild = true;
            var source = book ? Items.BOOK : Items.DIAMOND_SWORD; world.inventory.setItem(0, new ItemStack(source));
            var record = record(source); var inventory = EnchantInventory.prepare(world.player, record);
            var menu = new EnchantmentMenu(57, world.inventory); world.player.containerMenu = menu;
            var stack = new ItemStack(book ? Items.ENCHANTED_BOOK : source); stack.enchant(unbreaking, 3); stack.enchant(sharpness, 4);
            menu.getSlot(0).set(stack); inventory.freezeResult(stack);
            var expected = Map.of("minecraft:unbreaking", 3, "minecraft:sharpness", 4);
            check(inventory.resultEnchantments().equals(expected), "ordinary and stored components expose every namespaced enchantment");
            stack.enchant(unbreaking, 5);
            check(inventory.resultEnchantments().equals(expected), "later menu changes cannot rewrite the frozen result display");
            var flow = new EnchantMenuFlow(world.player, record, menu, inventory,
                    () -> { throw new AssertionError("progress must not reserve consumption"); });
            var task = new EnchantCompanionTask(world.player, record);
            var field = EnchantCompanionTask.class.getDeclaredField("flow"); field.setAccessible(true); field.set(task, flow);
            var progress = task.progress();
            check(progress.equals(task.progress()) && progress.get("phase").equals("load_input")
                            && Boolean.FALSE.equals(progress.get("item_return_verified")),
                    "in-flight progress exposes phase and return state without advancing work");
            check(!progress.containsKey("result_enchantments") && !progress.containsKey("seed") && !progress.containsKey("slot")
                            && world.blockUses() == 0 && world.itemUses() == 0,
                    "an unconfirmed snapshot is not exposed as a result and progress never performs game actions");
        }
    }

    private static EnchantTaskRecord record(net.minecraft.world.item.Item item) {
        return new EnchantTaskRecord("enchant-guards", 2000, BuiltInRegistries.ITEM.getKey(item), new BlockPos(3, 1, 3), 3, 3, 3);
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); throw new AssertionError("expected rejection: " + reason); }
        catch (IllegalArgumentException | IllegalStateException expected) { check(expected.getMessage().contains(reason), "the rejection explains the missing prerequisite"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class CleanupProbe implements Task {
        int stops, results;
        @Override public TaskState tick(net.minecraft.client.player.LocalPlayer player) { throw new AssertionError("cleanup cannot resume child work"); }
        @Override public void stop(net.minecraft.client.player.LocalPlayer player, StopReason why) { stops++; }
        @Override public TaskResult result(TaskState state) { results++; return TaskResult.cancelled("probe cleaned"); }
        @Override public String name() { return "enchant cleanup probe"; }
    }
}
