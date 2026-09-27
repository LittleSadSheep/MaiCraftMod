// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Player;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.process.MinecraftStonecuttingProcessAdapter;
import org.maiwithu.maicraft.core.integration.machine.process.NativeProcessRegistry;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 石切机原生工序回归：参数校验与钳制、适配器匹配与任务创建、消费命名空间对齐，
 * 以及伴生任务在缺料或站点丢失时的诚实失败路径。菜单内切制以实机验收为准。
 */
public final class StonecuttingProcessTest {
    private static final BlockPos STATION = new BlockPos(2, 1, 2);
    private static final long DEADLINE = 6000;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        parametersRejectInvalidAndOutOfRange();
        adapterMatchesStationAndCreatesTypedRecord();
        registryResolvesProcessWithMatchingNamespace();
        companionFailsHonestlyWithoutMaterial();
        companionFailsHonestlyWithoutStation();
        System.out.println("StonecuttingProcessTest: passed");
    }

    private static void parametersRejectInvalidAndOutOfRange() {
        var adapter = new MinecraftStonecuttingProcessAdapter();
        try {
            adapter.validate(params("minecraft:stone", "minecraft:stone_bricks", 4, "extra"));
            check(false, "foreign parameter keys must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            adapter.validate(params("minecraft:not_an_item", "minecraft:stone_bricks", 1, null));
            check(false, "unregistered input must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            adapter.validate(params("minecraft:stone", null, 1, null));
            check(false, "a missing output_item_id must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 999, null));
            check(false, "an out-of-range count must be rejected, not silently clamped");
        } catch (IllegalArgumentException expected) { }
    }

    private static void adapterMatchesStationAndCreatesTypedRecord() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var adapter = new MinecraftStonecuttingProcessAdapter();
            check(!adapter.matches(h.player, STATION), "an unloaded stonecutter must not match");
            h.set(STATION, Blocks.STONECUTTER.defaultBlockState());
            check(adapter.matches(h.player, STATION), "the placed stonecutter matches at its exact position");
            var record = adapter.createTask("stonecutting-test", DEADLINE, h.player, STATION,
                    params("minecraft:stone", "minecraft:stone_bricks", 4, null));
            check(record instanceof StonecuttingTaskRecord, "the process creates a typed submission record");
            var stonecutting = (StonecuttingTaskRecord) record;
            check(stonecutting.input.toString().equals("minecraft:stone")
                    && stonecutting.output.toString().equals("minecraft:stone_bricks")
                    && stonecutting.count == 4 && stonecutting.station.equals(STATION),
                    "the record freezes input, output, count and station");
            check("stonecutting".equals(stonecutting.submissionNamespace()),
                    "the record carries the registered consumption namespace");
        }
    }

    private static void registryResolvesProcessWithMatchingNamespace() {
        var adapter = NativeProcessRegistry.adapter(MinecraftStonecuttingProcessAdapter.ID);
        check(adapter.id().equals("minecraft:stonecutting"), "the registry resolves the stonecutting mechanism");
        check("stonecutting".equals(NativeProcessRegistry.consumptionNamespace(MinecraftStonecuttingProcessAdapter.ID)),
                "the consumption namespace is registered for the durable barrier");
    }

    private static void companionFailsHonestlyWithoutMaterial() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            prepareDefaultMenus(h);
            h.set(STATION, Blocks.STONECUTTER.defaultBlockState());
            var record = new StonecuttingTaskRecord("stonecutting-test", DEADLINE,
                    StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 4, null)), STATION);
            var task = new StonecuttingCompanionTask(h.player, record);
            task.start(h.player);
            check(task.tick(h.player) == TaskState.FAILED, "missing input material fails before any body action");
            check("stonecutting_input_material_missing".equals(task.result(TaskState.FAILED).data().get("issue_code")),
                    "the failure names the missing material");
        }
    }

    private static void companionFailsHonestlyWithoutStation() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            prepareDefaultMenus(h);
            h.inventory.setItem(0, new ItemStack(Items.STONE, 8));
            var record = new StonecuttingTaskRecord("stonecutting-test", DEADLINE,
                    StonecuttingParameters.parse(params("minecraft:stone", "minecraft:stone_bricks", 4, null)), STATION);
            var task = new StonecuttingCompanionTask(h.player, record);
            task.start(h.player);
            check(task.tick(h.player) == TaskState.FAILED, "a missing station fails before any body action");
            check("stonecutter_station_missing_or_unloaded".equals(task.result(TaskState.FAILED).data().get("issue_code")),
                    "the failure names the missing station");
        }
    }

    /** Unsafe 分配的玩家没有运行构造函数；生产玩家默认 containerMenu == inventoryMenu，任务启动前同样要先就位。 */
    private static void prepareDefaultMenus(InteractionWorldTestHarness h) throws Exception {
        var menu = new InventoryMenu(h.inventory, true, h.player);
        var field = Player.class.getDeclaredField("inventoryMenu");
        field.setAccessible(true);
        field.set(h.player, menu);
        h.player.containerMenu = menu;
    }

    private static JsonObject params(String input, String output, int count, String extraKey) {
        var parameters = new JsonObject();
        if (input != null) parameters.addProperty("item_id", input);
        if (output != null) parameters.addProperty("output_item_id", output);
        parameters.addProperty("count", count);
        if (extraKey != null) parameters.addProperty(extraKey, "unexpected");
        return parameters;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
