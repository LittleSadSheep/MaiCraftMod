package org.maiwithu.maicraft.core.task.mine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 定点采收的概率掉落语义：源格确认破坏而期望产物未到时，破坏成立即事实成功，
 * 回执以 probabilistic_drop_missed 区分「已破坏/概率未中」与「方块未破坏仍 failed」两种形态；
 * 普通范围采矿的 32 格产出预算失败口径保持不变。
 */
public final class HarvestProbabilisticReceiptTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        probabilisticMissSucceeds();
        unbrokenSourceStillFails();
        rangeMiningBudgetFailureUnchanged();
        System.out.println("HarvestProbabilisticReceiptTest: passed");
    }

    /** 概率掉落源格（短草→种子一类）破坏成功但本次未中：任务 success，回执含概率未中口径与 gathered=0。 */
    private static void probabilisticMissSucceeds() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true); effects.set(h.player, new HashMap<>());
            BlockPos at = new BlockPos(2, 1, 2);
            h.set(at, Blocks.SHORT_GRASS.defaultBlockState());
            var record = new MineBlockTaskRecord("probabilistic-miss", 1000, Set.of(Blocks.SHORT_GRASS), 1,
                    "source", Set.of(Items.WHEAT_SEEDS), false, false)
                    .onlyAt(at, Blocks.SHORT_GRASS.defaultBlockState());
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            accept(task, at, Blocks.SHORT_GRASS.defaultBlockState());
            for (int i = 0; i < 13; i++) h.nextTick();
            TaskState state = task.tick(h.player);
            check(state == TaskState.SUCCESS, "a confirmed break of a probabilistic source is a factual success even with no drop");
            Map<String, Object> data = task.result(state).data();
            check(Boolean.TRUE.equals(data.get("probabilistic_drop_missed")), "receipt marks the probabilistic miss explicitly");
            check(data.get("gathered").equals(0) && !data.containsKey("failure_code"),
                    "gathered stays honest at 0 and no failure code is emitted");
            check(data.get("confirmed_source_breaks").equals(1), "the single confirmed break is retained as evidence");
            check(!data.containsKey("expected_mining_output_not_observed"), "the old all-or-nothing failure code is gone");
            String message = task.result(state).message();
            check(message.contains("probabilistic"), "success message tells the caller to resubmit for another probabilistic attempt");
        }
    }

    /** 方块未被破坏（源格在确认破坏前消失）：任务仍 failed，不冒充概率未中。 */
    private static void unbrokenSourceStillFails() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true); effects.set(h.player, new HashMap<>());
            BlockPos at = new BlockPos(2, 1, 2);
            h.set(at, Blocks.SHORT_GRASS.defaultBlockState());
            var record = new MineBlockTaskRecord("probabilistic-lost", 1000, Set.of(Blocks.SHORT_GRASS), 1,
                    "source", Set.of(Items.WHEAT_SEEDS), false, false)
                    .onlyAt(at, Blocks.SHORT_GRASS.defaultBlockState());
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            h.set(at, Blocks.AIR.defaultBlockState());
            TaskState state = task.tick(h.player);
            check(state == TaskState.FAILED, "a source that vanished before any confirmed break still fails");
            check(!task.result(state).data().containsKey("probabilistic_drop_missed"),
                    "the probabilistic-miss receipt is reserved for confirmed breaks");
        }
    }

    /** 范围采矿的既有预算失败口径不变：多格确认破坏仍无期望产物照旧 failed。 */
    private static void rangeMiningBudgetFailureUnchanged() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true); effects.set(h.player, new HashMap<>());
            var record = new MineBlockTaskRecord("range-budget", 1000, Set.of(Blocks.DIRT), 1, "source", Set.of(Items.STONE))
                    .withinRadius(h.player.blockPosition(), 16);
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            for (int i = 0; i < 32; i++) {
                BlockPos at = new BlockPos(2 + i % 8, 1, 2 + i / 8);
                accept(task, at, Blocks.DIRT.defaultBlockState());
            }
            h.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
            for (int i = 0; i < 13; i++) h.nextTick();
            TaskState state = task.tick(h.player);
            check(state == TaskState.FAILED, "ranged mining keeps the bounded no-output failure");
            check("expected_mining_output_not_observed".equals(task.result(state).data().get("failure_code")),
                    "the ranged failure code is unchanged");
        }
    }

    private static void accept(MineCompanionTask task, BlockPos at, net.minecraft.world.level.block.state.BlockState before) throws Exception {
        Field target = MineCompanionTask.class.getDeclaredField("harvestTarget"); target.setAccessible(true); target.set(task, at);
        Field state = MineCompanionTask.class.getDeclaredField("harvestBefore"); state.setAccessible(true); state.set(task, before);
        Method accept = MineCompanionTask.class.getDeclaredMethod("acceptDigResult", BlockPos.class, BlockDigger.DigResult.class);
        accept.setAccessible(true);
        accept.invoke(task, at, BlockDigger.DigResult.BROKE_TARGET);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
