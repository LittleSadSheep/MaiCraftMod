// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 探矿编排：mine 子任务公平空手（mined_out）后，授权开着且物品有已知生成带 → 派下降
 * 子任务（目标 Y = 生成带推荐值），下降完成 → 派带掘进授权的探矿采矿；授权关或表外
 * 物品维持切片 1 的行为，不派下降、不猜深度。
 */
public final class AcquisitionProspectingHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        authorizedDescendsToBand();
        declinedWithoutAuthorization();
        unknownBandDeclinesProspecting();
        descendSuccessStartsProspectMine();
        System.out.println("AcquisitionProspectingHandoffTest: passed");
    }

    /** 授权开 + 表内物品：公平空手后下一张子任务单是下降，目标 Y = 生成带推荐值。 */
    private static void authorizedDescendsToBand() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h, List.of("minecraft:diamond"), true);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            var active = activeRecord(task);
            check(active instanceof MoveToTaskRecord,
                    "公平空手 + 授权开 → 下一张单是下降子任务，实际: " + active);
            if (active instanceof MoveToTaskRecord descend) {
                check(descend.y != null && descend.y.intValue() == -59,
                        "下降目标 Y 必须是生成带推荐值 -59，实际: " + descend.y);
                check(descend.mayAlterTerrain, "下降复用既有移动机制掘进，需要开路授权");
                check(descend.exact, "下降是内部工作站位，要求精确到点");
            }
            Object need = get(task, "rootNeed");
            check(intField(need, "prospectingY") == -59, "需求侧记住探矿目标层");
        }
    }

    /** 授权关：公平空手后不派下降，维持切片 1 的来源推进与 MINED_OUT 终态。 */
    private static void declinedWithoutAuthorization() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h, List.of("minecraft:diamond"), false);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            check(!(activeRecord(task) instanceof MoveToTaskRecord),
                    "授权关不得派出下降子任务");
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 16 && state == TaskState.RUNNING; i++) state = task.onTick();
            check(state == TaskState.FAILED, "唯一来源耗尽后任务终态失败");
            check("mined_out".equals(task.result(TaskState.FAILED).data().get("failure_type")),
                    "终态保真为 MINED_OUT，不因探矿缺席改变口径");
        }
    }

    /** 授权开但表外物品：不猜下降深度，如实拒绝探矿后照常推进来源。 */
    private static void unknownBandDeclinesProspecting() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h, List.of("minecraft:stick"), true);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            check(!(activeRecord(task) instanceof MoveToTaskRecord),
                    "表外物品不得派下降子任务");
        }
    }

    /** 下降子任务成功结束后，下一张单是带掘进授权的探矿采矿，且不冻结扫描范围。 */
    private static void descendSuccessStartsProspectMine() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            var task = task(h, List.of("minecraft:coal"), true);
            startStubDescend(task, 96);
            tickActiveChild(task);
            var active = activeRecord(task);
            check(active instanceof MineBlockTaskRecord,
                    "下降完成后派探矿采矿，实际: " + active);
            if (active instanceof MineBlockTaskRecord mine) {
                check(mine.prospecting() && mine.prospectY() == 96,
                        "探矿采矿携带掘进授权与生成带目标层 96");
                check(mine.searchCenter() == null, "探矿掘进不冻结地表扫描范围");
            }
            check(booleanField(get(task, "rootNeed"), "prospectingMineStarted"),
                    "需求侧记录探矿腿已派出");
        }
    }

    // ---- 夹具：语义取物任务 + 手工派发 stub 子任务，驱动完成处理而不动真实世界 ----

    private static SemanticAcquireCompanionTask task(InteractionWorldTestHarness h,
                                                     List<String> itemIds, boolean allowProspecting) {
        var items = itemIds.stream().map(ResourceLocation::parse).toList();
        var record = new SemanticAcquireTaskRecord("prospect-handoff", 100000, items, 4,
                List.of(SemanticAcquireTaskRecord.Source.MINE), false,
                SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8)
                .withProspecting(allowProspecting);
        return new SemanticAcquireCompanionTask(h.player, record);
    }

    /** 派一张真实 mine 记录再换成 stub 失败回执；父层完成处理只读回执，不重挖方块。 */
    private static void startStubMine(SemanticAcquireCompanionTask task, String failureType) throws Exception {
        task.onStart();
        Object need = get(task, "rootNeed");
        startChild(task, need, new MineBlockTaskRecord("stub-mine", 100000,
                Set.of(Blocks.STONE), 4, "stone"));
        set(task, "activeChild", new StubMineChild(failureType));
    }

    /** 派一张真实下降记录再换成 stub 成功回执；完成处理按记录类型识别这是探矿下降腿。 */
    private static void startStubDescend(SemanticAcquireCompanionTask task, int prospectY) throws Exception {
        task.onStart();
        Object need = get(task, "rootNeed");
        set(need, "prospectingDescendStarted", true);
        set(need, "prospectingY", prospectY);
        startChild(task, need, MoveToTaskRecord.strictStance(
                "prospect-descend", 100000, new BlockPos(0, prospectY, 0), true));
        set(task, "activeChild", new StubSuccessChild());
    }

    private static void startChild(SemanticAcquireCompanionTask task, Object need, TaskRecord record)
            throws Exception {
        var start = task.getClass().getDeclaredMethod("startChild", need.getClass(),
                SemanticAcquireTaskRecord.Source.class, TaskRecord.class, String.class);
        start.setAccessible(true);
        start.invoke(task, need, SemanticAcquireTaskRecord.Source.MINE, record, "stub");
    }

    private static void tickActiveChild(SemanticAcquireCompanionTask task) throws Exception {
        var tick = task.getClass().getDeclaredMethod("tickActiveChild");
        tick.setAccessible(true);
        tick.invoke(task);
    }

    private static Object activeRecord(SemanticAcquireCompanionTask task) throws Exception {
        return get(task, "activeRecord");
    }

    /** 模拟 mine 子任务：以给定 failure_type 空手失败，不挖任何方块。 */
    private record StubMineChild(String failureType) implements Task {
        @Override public TaskState tick(LocalPlayer player) { return TaskState.FAILED; }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public String name() { return "模拟 mine 采区耗尽"; }
        @Override public TaskResult result(TaskState state) {
            return TaskResult.fail("gathered 0/4, no more sources in range",
                    Map.of("failure_type", failureType));
        }
    }

    /** 模拟下降子任务：直接成功；记录类型识别靠 startChild 派出的真实 MoveToTaskRecord。 */
    private static final class StubSuccessChild implements Task {
        @Override public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public String name() { return "模拟探矿下降"; }
        @Override public TaskResult result(TaskState state) { return TaskResult.ok("descended", Map.of()); }
    }

    private static Object get(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); f.set(instance, value);
    }

    private static int intField(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(instance);
    }

    private static boolean booleanField(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.getBoolean(instance);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
