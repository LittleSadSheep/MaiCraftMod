package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.mine.MineCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** 生成器只采指定产出格；贴水不被普通找矿规则排除，旁边同种机架、后续再生格与外来物品都不能替代本次来源。 */
public final class ExactHarvestTest {
    private static final BlockPos SOURCE = new BlockPos(3, 1, 3), FRAME = new BlockPos(2, 1, 3);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 无服务器数据包的夹具需要装入圆石的镐采集标签，结束时恢复原标签，避免把无掉落工具误当成范围筛选错误。
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var previous = Map.copyOf(tags);
        tags.put(BlockTags.MINEABLE_WITH_PICKAXE, List.of(Blocks.COBBLESTONE.builtInRegistryHolder(), Blocks.STONE.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try { run(); reportsActualExpectedDrop(); } finally { BuiltInRegistries.BLOCK.bindTags(previous); }
        System.out.println("ExactHarvestTest: passed");
    }
    private static void run() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            h.set(SOURCE, Blocks.COBBLESTONE.defaultBlockState()); h.set(FRAME, Blocks.COBBLESTONE.defaultBlockState());
            h.set(SOURCE.east(), Blocks.WATER.defaultBlockState());
            var record = compile(h, goal(SOURCE));
            check(record.exactHarvest() && record.searchCenter().equals(SOURCE) && record.searchRadius() == 0,
                    "semantic harvest freezes just the observed source");
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            var ores = MineCompanionTask.class.getDeclaredField("knownOres"); ores.setAccessible(true);
            check(ores.get(task).equals(List.of(SOURCE)) && h.level.searches == 0,
                    "one loaded source beside water bypasses the world index and excludes matching frame blocks");
            var travel = MineCompanionTask.class.getDeclaredMethod("travelContext"); travel.setAccessible(true);
            check(travel.invoke(task) == PlayerNav.ContextProvider.DEFAULT, "approach and pickup may not mine a route through the machine");
            // 接单后替换成箱子，同时从别处收到圆石；这两件事不能骗过来源检查，更不能把箱子当矿挖掉。
            h.set(SOURCE, Blocks.CHEST.defaultBlockState()); h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE));
            check(task.tick(h.player) == TaskState.FAILED && record.getMined() == 0,
                    "changed source fails before an unrelated inventory increment can satisfy harvest");
            check(task.result(TaskState.FAILED).data().get("confirmed_source_breaks").equals(0), "no source break is invented");
            check(AbilityAdapter.adapt(goal(SOURCE), h.player, null) instanceof IntentAction.Decision, "mismatching block entity is rejected before dispatch");
        }
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE)); h.set(SOURCE, Blocks.COBBLESTONE.defaultBlockState());
            var task = new MineCompanionTask(h.player, compile(h, goal(SOURCE))); task.start(h.player);
            // 注入共享挖掘器已确认破坏的回执，再让来源格保持圆石，模拟生成器已经长出下一块；查询必须停止。
            var accept = MineCompanionTask.class.getDeclaredMethod("acceptDigResult", BlockPos.class, BlockDigger.DigResult.class); accept.setAccessible(true);
            accept.invoke(task, SOURCE, BlockDigger.DigResult.BROKE_TARGET);
            var query = MineCompanionTask.class.getDeclaredMethod("runQuery"); query.setAccessible(true); query.invoke(task);
            var ores = MineCompanionTask.class.getDeclaredField("knownOres"); ores.setAccessible(true);
            check(((List<?>) ores.get(task)).isEmpty(), "regenerated source does not authorize a second break");
            int reads = h.level.blockReads;
            check(AbilityAdapter.adapt(goal(new BlockPos(32, 1, 3)), h.player, null) instanceof IntentAction.Decision
                    && h.level.blockReads == reads, "unloaded exact source cannot read or substitute a nearby cell");
        }
    }
    // 中央生成的是石头、普通镐实际掉圆石时，成功摘要必须报告背包产物，不能沿用来源方块名。
    private static void reportsActualExpectedDrop() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE)); h.set(SOURCE, Blocks.STONE.defaultBlockState());
            var sourceGoal = goal(SOURCE);
            var parameters = new JsonObject(); parameters.addProperty("block_id", "minecraft:stone");
            parameters.addProperty("expected_output_item_id", "minecraft:cobblestone"); parameters.addProperty("may_alter_terrain", true);
            var request = new Goal(GeneralAbilityAdapter.HARVEST_BLOCK, "harvest generated stone", sourceGoal.target(),
                    parameters.toString(), "{}", List.of(), List.of());
            var task = new MineCompanionTask(h.player, compile(h, request)); task.start(h.player);
            var accept = MineCompanionTask.class.getDeclaredMethod("acceptDigResult", BlockPos.class, BlockDigger.DigResult.class);
            accept.setAccessible(true); accept.invoke(task, SOURCE, BlockDigger.DigResult.BROKE_TARGET);
            // 先等破坏后的原生掉落同步窗口结束，消息测试不能跳过执行器要求的收尾等待。
            for (int tick = 0; tick < 13; tick++) h.nextTick();
            h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE));
            check(task.tick(h.player) == TaskState.SUCCESS, "the verified source break and expected inventory gain settle harvest");
            var receipt = task.result(TaskState.SUCCESS);
            check(receipt.message().contains("gathered 1/1 [minecraft:cobblestone] from minecraft:stone"),
                    "human-readable receipt distinguishes collected cobblestone from its stone source");
        }
    }

    private static MineBlockTaskRecord compile(InteractionWorldTestHarness h, Goal goal) {
        SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
        return (MineBlockTaskRecord) ((IntentAction.Native) AbilityAdapter.adapt(goal, h.player, null)).record();
    }
    private static Goal goal(BlockPos pos) {
        var p = new JsonObject(); p.addProperty("block_id", "minecraft:cobblestone");
        p.addProperty("expected_output_item_id", "minecraft:cobblestone"); p.addProperty("may_alter_terrain", true);
        return new Goal(GeneralAbilityAdapter.HARVEST_BLOCK, "harvest this source", new Goal.SemanticTarget("coordinates", null,
                new Goal.WorldPosition(pos.getX(), pos.getY(), pos.getZ(), "minecraft:overworld"), null), p.toString(), "{}", List.of(), List.of());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
