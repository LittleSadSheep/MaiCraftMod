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
        wetBodyEvacuatesBeforeScanning();
        evacuationBudgetExhaustionFailsHonestly();
        noDryCellNearbyFailsWithScope();
        sourceScanHeartbeatMonotonic();
        sourceScanTimeoutFailsHonestly();
        slowScanSurvivesAndWindowResets();
        approachPlanningWideBoundFailsHonestly();
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

    /**
     * 身体安全（issue 168）：脚位格与身体格有水时，mine 任务第一刻停下扫描并建立撤离导航，
     * progress 携带 body 滞水键；身体回到干地后撤离收尾、扫描恢复。
     */
    private static void wetBodyEvacuatesBeforeScanning() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            initNavRuntime();
            h.set(new BlockPos(0, 1, 3), Blocks.WATER.defaultBlockState());
            h.set(new BlockPos(0, 2, 3), Blocks.WATER.defaultBlockState());
            var task = new org.maiwithu.maicraft.core.task.mine.MineCompanionTask(h.player,
                    new MineBlockTaskRecord("wet-scan", 100000, Set.of(Blocks.STONE), 4, "stone"));
            startTask(task);
            TaskState state = tickTask(task);
            check(state == TaskState.RUNNING, "滞水时任务保持运行并开始撤离，实际 " + state);
            check(get(task, "evacuationNav") != null, "滞水第一刻就应建立撤离导航，不得在水中开始扫描");
            Object body = task.progress().get("body");
            check(String.valueOf(body).contains("滞水"), "progress 应携带 body 滞水键，实际: " + body);
            // 身体被推回干地（实机等价：撤到岸上）：下一刻撤离收尾，任务恢复正常扫描。
            h.position(new net.minecraft.world.phys.Vec3(6.5, 1, 6.5));
            h.nextTick();
            tickTask(task);
            check(get(task, "evacuationNav") == null, "回到干地后撤离导航应收尾");
            check(task.progress().get("body") == null, "干地上 progress 不再携带滞水键");
        }
    }

    /** 撤离预算耗尽仍湿身：诚实失败收手，回执写明滞水刻数与换气反射可能介入过，不无限漂着。 */
    private static void evacuationBudgetExhaustionFailsHonestly() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            initNavRuntime();
            h.set(new BlockPos(0, 1, 3), Blocks.WATER.defaultBlockState());
            h.set(new BlockPos(0, 2, 3), Blocks.WATER.defaultBlockState());
            var task = new org.maiwithu.maicraft.core.task.mine.MineCompanionTask(h.player,
                    new MineBlockTaskRecord("wet-budget", 100000, Set.of(Blocks.STONE), 4, "stone"));
            startTask(task);
            tickTask(task);
            set(task, "evacuationTicks", 200);
            TaskState state = tickTask(task);
            check(state == TaskState.FAILED, "撤离预算耗尽必须诚实失败，实际 " + state);
            var data = task.result(TaskState.FAILED).data();
            check(data.get("body_wet_ticks") instanceof Integer && (Integer) data.get("body_wet_ticks") >= 2,
                    "回执应携带滞水刻数，实际: " + data);
            check(String.valueOf(task.result(TaskState.FAILED).message()).contains("breath reflex"),
                    "失败正文应写明换气反射可能兜过底");
        }
    }

    /** 周围全是水、找不到干地站立格：立即诚实失败并携带搜索范围，声明不构成陆地不存在的证据。 */
    private static void noDryCellNearbyFailsWithScope() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                h.set(new BlockPos(x, 1, z), Blocks.WATER.defaultBlockState());
            var task = new org.maiwithu.maicraft.core.task.mine.MineCompanionTask(h.player,
                    new MineBlockTaskRecord("wet-ocean", 100000, Set.of(Blocks.STONE), 4, "stone"));
            startTask(task);
            TaskState state = tickTask(task);
            check(state == TaskState.FAILED, "找不到干地必须立即诚实失败，实际 " + state);
            String message = task.result(TaskState.FAILED).message();
            check(message.contains("no dry standable cell") && message.contains("16"),
                    "失败应携带干地搜索半径，实际: " + message);
            check(message.contains("not evidence that land does not exist"),
                    "范围型失败必须声明不构成陆地不存在的证据");
        }
    }

    // ---- 夹具：语义取物任务 + 手工派发 stub 子任务，驱动完成处理而不动真实世界 ----

    /**
     * 源扫描心跳（issue 170）：querying_sources 等待窗内 progress 携带标准 phase + 单调
     * 增长的扫描拍数 calc + 已等秒数；宽上限内继续等待，不误杀合法慢扫描。
     */
    private static void sourceScanHeartbeatMonotonic() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            var task = scanTask(h, "scan-heartbeat", 10_000);
            startTask(task);
            var wait = mineMethod(task, "waitWhileSourceScanIncomplete");
            set(task, "lastQueryComplete", false);
            TaskState first = (TaskState) wait.invoke(task);
            check(first == TaskState.RUNNING, "扫描等待窗内任务保持运行，实际 " + first);
            var p1 = task.progress();
            check("querying_sources".equals(p1.get("phase")),
                    "等待窗 phase=querying_sources，实际 " + p1.get("phase"));
            check(p1.get("calc") instanceof Number beats && beats.longValue() >= 1,
                    "等待窗应携带单调扫描拍数 calc，实际 " + p1);
            check(p1.get("planning_seconds") instanceof Number, "等待窗应携带已等秒数，实际 " + p1);
            for (int i = 0; i < 100; i++) h.nextTick();
            TaskState later = (TaskState) wait.invoke(task);
            check(later == TaskState.RUNNING, "宽上限内的慢扫描继续等待，不得误杀，实际 " + later);
            var p2 = task.progress();
            check(((Number) p2.get("calc")).longValue() > ((Number) p1.get("calc")).longValue(),
                    "扫描拍数应单调增长（门卫据此按地板间隔发布心跳），实际 " + p1 + " → " + p2);
            check(((Number) p2.get("planning_seconds")).longValue()
                            >= ((Number) p1.get("planning_seconds")).longValue() + 4,
                    "已等秒数应随真实等待推进，实际 " + p1 + " → " + p2);
        }
    }

    /** 源扫描超宽上限：如实失败（planning_stall），阶段名、已等秒数、扫描口径进回执，不再静默楔死。 */
    private static void sourceScanTimeoutFailsHonestly() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            var task = scanTask(h, "scan-timeout", 100);
            startTask(task);
            var wait = mineMethod(task, "waitWhileSourceScanIncomplete");
            set(task, "lastQueryComplete", false);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 400 && state == TaskState.RUNNING; i++) {
                h.nextTick();
                state = (TaskState) wait.invoke(task);
            }
            check(state == TaskState.FAILED, "超过宽上限必须诚实失败，实际 " + state);
            var result = task.result(TaskState.FAILED);
            var data = result.data();
            check("source_scan_planning_timeout".equals(data.get("failure_code")),
                    "失败应声明 source_scan_planning_timeout，实际: " + data);
            check("planning_stall".equals(data.get("failure_type")),
                    "扫描超限是 planning_stall 而非目标丢失，实际: " + data.get("failure_type"));
            check("querying_sources".equals(data.get("source_scan_phase")),
                    "回执应携带阶段名，实际: " + data.get("source_scan_phase"));
            check(data.get("source_scan_waited_seconds") instanceof Number waited && waited.longValue() >= 5,
                    "回执应携带已等待秒数，实际: " + data.get("source_scan_waited_seconds"));
            check(data.get("source_scan_pulses") instanceof Number pulses && pulses.longValue() > 0,
                    "回执应携带等待期扫描拍数，实际: " + data.get("source_scan_pulses"));
            String message = result.message();
            check(message.contains("querying_sources") && message.contains("not evidence"),
                    "失败正文应点名阶段并声明不构成世界中不存在的证据，实际: " + message);
            check(data.get("search_scope") instanceof Map<?, ?> scope
                            && Boolean.FALSE.equals(scope.get("index_complete")),
                    "回执扫描口径应声明索引未覆盖完整，实际: " + data.get("search_scope"));
        }
    }

    /**
     * 合法慢扫描不误杀，等待窗收口后重开预算不残留：第一窗等待、扫描完成收表；
     * 名单耗尽后的新等待窗重新起表，不会带上旧窗已消耗的时间被立即误判超限。
     */
    private static void slowScanSurvivesAndWindowResets() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            var task = scanTask(h, "scan-slow", 100);
            startTask(task);
            var wait = mineMethod(task, "waitWhileSourceScanIncomplete");
            var maybeQuery = mineMethod(task, "maybeQuery");
            set(task, "lastQueryComplete", false);
            check((TaskState) wait.invoke(task) == TaskState.RUNNING, "第一等待窗正常开始");
            for (int i = 0; i < 60; i++) {
                h.nextTick();
                check((TaskState) wait.invoke(task) == TaskState.RUNNING, "上限内的慢扫描持续等待");
            }
            // 扫描覆盖完成（夹具世界一拍内查完）：守卫收表，progress 不再带扫描心跳键。
            maybeQuery.invoke(task);
            var settled = task.progress();
            check(settled.get("calc") == null && settled.get("planning_seconds") == null,
                    "扫描完成后守卫应收表，实际 " + settled);
            // 新等待窗重新起表：旧窗已烧的 60 刻不得累加，第二窗再等 60 刻仍运行（若残留则累计
            // 120 刻 > 上限 100 刻会提前误杀），直到本窗自身超限才如实失败。
            set(task, "lastQueryComplete", false);
            check((TaskState) wait.invoke(task) == TaskState.RUNNING, "新等待窗重新起表");
            for (int i = 0; i < 60; i++) {
                h.nextTick();
                check((TaskState) wait.invoke(task) == TaskState.RUNNING,
                        "新窗预算独立，不得因旧窗残留提前超限（第 " + i + " 拍）");
            }
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 100 && state == TaskState.RUNNING; i++) {
                h.nextTick();
                state = (TaskState) wait.invoke(task);
            }
            check(state == TaskState.FAILED, "新窗自身超限后仍须如实失败，实际 " + state);
        }
    }

    /** 构造注入小扫描上限的 mine 任务：目标选夹具世界中不存在的钻石矿，扫描永远等不到命中。 */
    private static org.maiwithu.maicraft.core.task.mine.MineCompanionTask scanTask(
            InteractionWorldTestHarness h, String id, long limitTicks) throws Exception {
        var ctor = org.maiwithu.maicraft.core.task.mine.MineCompanionTask.class.getDeclaredConstructor(
                LocalPlayer.class, org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(h.player,
                new MineBlockTaskRecord(id, 100000, Set.of(Blocks.DIAMOND_ORE), 4, "diamond_ore"),
                limitTicks);
    }

    private static java.lang.reflect.Method mineMethod(
            org.maiwithu.maicraft.core.task.mine.MineCompanionTask task, String name) throws Exception {
        var method = org.maiwithu.maicraft.core.task.mine.MineCompanionTask.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static java.lang.reflect.Method mineMethod(
            org.maiwithu.maicraft.core.task.mine.MineCompanionTask task, String name, Class<?>... parameters) throws Exception {
        var method = org.maiwithu.maicraft.core.task.mine.MineCompanionTask.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    /**
     * 接近腿规划在飞的宽上限（117 harvest 接近腿样本：calc_started:1 后二十分钟零事件）：
     * 单次搜索永不返回时接近段如实收场（planning_stall，阶段名与不构成证据声明进回执），
     * 宽上限内的合法慢搜索不误杀，搜索返回即停表。
     */
    private static void approachPlanningWideBoundFailsHonestly() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            initEffects(h);
            var task = scanTask(h, "approach-bound", 1_000_000);
            startTask(task);
            var bound = mineMethod(task, "approachingSourcesBound",
                    boolean.class, long.class, String.class);
            // 搜索不在飞：守卫不收场，也不给任务残留的接近表计时。
            check(bound.invoke(task, false, 1L, "") == null, "搜索不在飞时接近守卫必须放行");
            // 第一次在飞：起表；宽上限内的慢搜索继续等待，不误杀。
            check(bound.invoke(task, true, 1L, "") == null, "接近规划在飞应起表继续运行");
            for (int i = 0; i < 120; i++) h.nextTick();
            check(bound.invoke(task, true, 1L, "planning") == null, "宽上限内的慢搜索继续等待");
            // 在飞越过宽上限：如实失败，阶段名与不构成证据声明进回执。
            for (int i = 0; i < 5000; i++) h.nextTick();
            TaskState state = (TaskState) bound.invoke(task, true, 1L, "planning");
            check(state == TaskState.FAILED, "接近规划超宽上限必须诚实失败，实际 " + state);
            var result = task.result(TaskState.FAILED);
            check("planning_stall".equals(result.data().get("failure_type")),
                    "接近超限是 planning_stall 而非目标丢失，实际: " + result.data().get("failure_type"));
            check(result.message().contains("approaching_sources")
                            && result.message().contains("not evidence"),
                    "失败正文应点名接近阶段并声明不构成不可达证据，实际: " + result.message());
            // 搜索返回后守卫停表：重新在飞要重新起表，旧等待不残留。
            var revive = scanTask(h, "approach-bound-reset", 1_000_000);
            startTask(revive);
            var bound2 = mineMethod(revive, "approachingSourcesBound",
                    boolean.class, long.class, String.class);
            check(bound2.invoke(revive, true, 1L, "") == null, "第二次在飞正常起表");
            for (int i = 0; i < 120; i++) h.nextTick();
            check(bound2.invoke(revive, false, 1L, "") == null, "搜索返回后停表");
            check(bound2.invoke(revive, true, 2L, "") == null, "停表后重新在飞重新起表，旧等待不残留");
            for (int i = 0; i < 120; i++) h.nextTick();
            check(bound2.invoke(revive, true, 2L, "") == null, "新表的等待从头计时，不继承旧窗");
        }
    }

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

    /** 撤离导航走真实寻路栈，测试桩需要给 baritone 一个可写目录才能完成运行时初始化。 */
    private static void initNavRuntime() throws Exception {
        var gameDirectory = net.minecraft.client.Minecraft.class.getDeclaredField("gameDirectory");
        gameDirectory.setAccessible(true);
        gameDirectory.set(net.minecraft.client.Minecraft.getInstance(),
                new java.io.File("prospect-body-safety-fixture"));
    }

    /** 夹具角色未经原版构造器，药水效果表为 null；寻路上下文读取药水放大前需补空表。 */
    private static void initEffects(InteractionWorldTestHarness h) throws Exception {
        var effects = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("activeEffects");
        effects.setAccessible(true);
        effects.set(h.player, new java.util.HashMap<>());
    }

    /** onStart/onTick 是 protected：测试用反射驱动真实 MineCompanionTask，不改其可见性。 */
    private static void startTask(org.maiwithu.maicraft.core.task.mine.MineCompanionTask task) throws Exception {
        var start = org.maiwithu.maicraft.core.task.mine.MineCompanionTask.class.getDeclaredMethod("onStart");
        start.setAccessible(true);
        start.invoke(task);
    }

    private static TaskState tickTask(org.maiwithu.maicraft.core.task.mine.MineCompanionTask task) throws Exception {
        var tick = org.maiwithu.maicraft.core.task.mine.MineCompanionTask.class.getDeclaredMethod("onTick");
        tick.setAccessible(true);
        return (TaskState) tick.invoke(task);
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
