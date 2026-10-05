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
 * 探矿编排：mine 子任务公平空手（mined_out）后，授权开着且物品有已知生成带 → 派探矿
 * 子任务（目标层按生成带就近选取：带内取当前层，带上方取带顶），下降完成 → 派带掘进
 * 授权的探矿采矿；授权关或表外物品维持切片 1 的行为，不派下降、不猜深度。
 */
public final class AcquisitionProspectingHandoffTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        authorizedDescendsToBand();
        blockIdItemDescendsToBand();
        declinedWithoutAuthorization();
        unknownBandDeclinesProspecting();
        descendSuccessStartsProspectMine();
        outsideBandUsesNearestBandEdge();
        uphillBandRefused();
        realFairScanDispatchesProspecting();
        System.out.println("AcquisitionProspectingHandoffTest: passed");
    }

    /** 授权开 + 表内物品：公平空手后下一张子任务单是探矿采矿；当前位置已在生成带内时目标层取当前层。 */
    private static void authorizedDescendsToBand() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            var task = task(h, List.of("minecraft:diamond"), true);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            var active = activeRecord(task);
            check(active instanceof MineBlockTaskRecord,
                    "公平空手 + 授权开 → 统一探矿任务负责下降与到层后的水平通道，实际: " + active);
            if (active instanceof MineBlockTaskRecord mine) {
                check(mine.prospecting() && mine.prospectY() == 1,
                        "y=1 在钻石带 [-64,16] 内：目标层就近取当前层 1，不再降到峰值层 -59");
                check(mine.searchCenter() == null, "探矿不能被旧地表扫描中心限制");
            }
            Object need = get(task, "rootNeed");
            check(intField(need, "prospectingY") == 1, "需求侧记住探矿目标层");
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

    /**
     * 矿方块 ID 同样表达探矿意图：item_ids=["minecraft:iron_ore"] 曾因只查产物表 key
     * 被如实拒为 prospecting_band_unknown，现按目标方块族反查到铁带并照常派下降。
     */
    private static void blockIdItemDescendsToBand() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            var task = task(h, List.of("minecraft:iron_ore"), true);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            var active = activeRecord(task);
            check(active instanceof MineBlockTaskRecord,
                    "矿方块 ID + 授权开 → 照常派出统一探矿任务，实际: " + active);
            if (active instanceof MineBlockTaskRecord mine) {
                check(mine.prospecting() && mine.prospectY() == 1,
                        "y=1 在铁带内：探矿腿目标层就近取当前层，不得回退为 band_unknown 拒绝");
            }
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

    /** 下降子任务成功结束后，下一张单是带掘进授权的探矿采矿，目标层沿用就近决策的结果且不冻结扫描范围。 */
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
                        "探矿采矿携带掘进授权，目标层沿用决策就近选出的 96");
                check(mine.searchCenter() == null, "探矿掘进不冻结地表扫描范围");
            }
            check(booleanField(get(task, "rootNeed"), "prospectingMineStarted"),
                    "需求侧记录探矿腿已派出");
        }
    }

    // ---- 夹具：语义取物任务 + 手工派发 stub 子任务，驱动完成处理而不动真实世界 ----

    /**
     * 真实流程回放：不替换任何子任务，让真实 mine 子任务在空世界里完成公平扫描并空手
     * 失败（mined_out），随后父层必须派出探矿任务。地表场景（脚位在生成带内）是实机
     * 提交的主要形态，stub 回执覆盖不到真实 mine 子任务回执进结算的整条链路。
     */
    private static void realFairScanDispatchesProspecting() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var effects = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("activeEffects");
            effects.setAccessible(true);
            effects.set(h.player, new java.util.HashMap<>());
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            org.maiwithu.maicraft.intent.IntentRuntime.get().observedSourceMemory().clear();
            // 无服务器数据包的夹具先补 raw_iron 的直挖源与镐采掘标签（ProspectingTest 同款），
            // 并按生产注册表补 mine 子任务执行器（其他场景直接实例化子任务，覆盖不到这条派发链）；
            // 两类注册都在 finally 恢复，夹具间不靠注册残留巧合隔离。
            var tags = new java.util.HashMap<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
                    java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>>();
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
            var previousTags = java.util.Map.copyOf(tags);
            tags.put(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE,
                    List.of(Blocks.IRON_ORE.builtInRegistryHolder(), Blocks.DEEPSLATE_IRON_ORE.builtInRegistryHolder()));
            tags.put(net.minecraft.tags.BlockTags.INCORRECT_FOR_WOODEN_TOOL, List.of());
            tags.put(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    ResourceLocation.parse("minecraft:iron_ores")),
                    List.of(Blocks.IRON_ORE.builtInRegistryHolder(), Blocks.DEEPSLATE_IRON_ORE.builtInRegistryHolder()));
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.bindTags(tags);
            var runnersField = org.maiwithu.maicraft.task.TaskFactory.class.getDeclaredField("RUNNERS");
            runnersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var runners = (java.util.Map<Class<? extends org.maiwithu.maicraft.task.TaskRecord>,
                    org.maiwithu.maicraft.task.TaskFactory.Runner<? extends org.maiwithu.maicraft.task.TaskRecord>>) runnersField.get(null);
            var previousRunner = runners.get(org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord.class);
            org.maiwithu.maicraft.task.TaskFactory.register(org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord.class,
                    (p, r) -> new org.maiwithu.maicraft.core.task.mine.MineCompanionTask(p, r));
            try {
                runRealFlow(h);
            } finally {
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.bindTags(previousTags);
                if (previousRunner != null) {
                    runners.put(org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord.class, previousRunner);
                }
            }
        }
    }

    private static void runRealFlow(InteractionWorldTestHarness h) throws Exception {
        var items = List.of(ResourceLocation.parse("minecraft:raw_iron"));
        var record = new SemanticAcquireTaskRecord("prospect-real-flow", 100000, items, 3,
                List.of(SemanticAcquireTaskRecord.Source.MINE), false,
                SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8)
                .withProspecting(true).withLoadedMiningView(true);
        var task = new SemanticAcquireCompanionTask(h.player, record);
        task.onStart();
        TaskState state = TaskState.RUNNING;
        boolean dispatched = false;
        for (int i = 0; i < 600 && state == TaskState.RUNNING && !dispatched; i++) {
            state = task.onTick();
            h.nextTick();
            if (activeRecord(task) instanceof MineBlockTaskRecord mine && mine.prospecting()) {
                dispatched = true;
                check(mine.prospectY() == 1, "脚位 y=1 在铁带内："
                        + "目标层就近取当前层 prospectY=1，实际 prospectY=" + mine.prospectY());
            }
        }
        check(dispatched, "真实 mine 子任务公平空手 + 授权开 → 必须派出探矿任务，终态=" + state
                + "，活动记录=" + activeRecord(task)
                + "，回执=" + task.result(TaskState.FAILED).data());
    }

    /** 当前位置在带外上方：目标层就近取带顶，不再走到峰值暴露层。 */
    private static void outsideBandUsesNearestBandEdge() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            // 夹具方块只有 y0..15；y=40 在其上为空气，仅用于决策，不驱动真实挖掘。
            h.position(new net.minecraft.world.phys.Vec3(.5, 40, 3.5));
            var task = task(h, List.of("minecraft:diamond"), true);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            var active = activeRecord(task);
            check(active instanceof MineBlockTaskRecord mine
                            && mine.prospecting() && mine.prospectY() == 16,
                    "y=40 在钻石带 [-64,16] 上方：目标层就近取带顶 16，实际: " + active);
        }
    }

    /** 当前位置在带外下方（低于带底）：就近目标层在上方，拒绝露天垫柱爬升，不派探矿子任务。 */
    private static void uphillBandRefused() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            h.position(new net.minecraft.world.phys.Vec3(.5, -10, 3.5));
            var task = task(h, List.of("minecraft:coal"), true);
            startStubMine(task, "mined_out");
            tickActiveChild(task);
            check(!(activeRecord(task) instanceof MineBlockTaskRecord),
                    "y=-10 在煤带外且带底 0 在上方：拒绝向上重定位，不派探矿子任务，实际: "
                            + activeRecord(task));
        }
    }

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
