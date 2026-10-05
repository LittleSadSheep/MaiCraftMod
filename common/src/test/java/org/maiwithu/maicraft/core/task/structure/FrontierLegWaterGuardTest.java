// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.structure;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.explore.WaterCrossingProbe;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 勘察腿涉水守卫（175）的三个场景：
 * ① 航点选址把「落点列是纯水面」与「路线穿水」同口径拒斥，浅滩短渡到对岸干地不误伤（130 豁免）；
 * ② 行进中连续滞水且不再逼近目标时当场弃腿，失败明细与回执如实记录弃腿原因并升级处置；
 * ③ 守卫阈值判定对「仍在逼近目标」「未达窗口」的滞水放行，正常短促涉水不被打断。
 */
public final class FrontierLegWaterGuardTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 测试 JVM 不加载原版数据包，流体标签手动绑定（同 ClientSurfaceHeightTest 口径）。
        net.minecraft.core.registries.BuiltInRegistries.FLUID.bindTags(Map.of(
                FluidTags.WATER, List.of(
                        BuiltInRegistriesWrap.wrap(net.minecraft.world.level.material.Fluids.WATER),
                        BuiltInRegistriesWrap.wrap(net.minecraft.world.level.material.Fluids.FLOWING_WATER)),
                FluidTags.LAVA, List.of(
                        BuiltInRegistriesWrap.wrap(net.minecraft.world.level.material.Fluids.LAVA),
                        BuiltInRegistriesWrap.wrap(net.minecraft.world.level.material.Fluids.FLOWING_LAVA))));
        guardDecisionThresholds();
        try (var w = new InteractionWorldTestHarness()) {
            waterLandingAndRouteProbe(w);
            stalledWaterLegIsAbandoned(w);
        }
        System.out.println("FrontierLegWaterGuardTest: passed");
    }

    private static final class BuiltInRegistriesWrap {
        private BuiltInRegistriesWrap() {}
        static net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid> wrap(
                net.minecraft.world.level.material.Fluid fluid) {
            return net.minecraft.core.registries.BuiltInRegistries.FLUID
                    .wrapAsHolder(fluid);
        }
    }

    /** 场景③：硬上限无条件弃腿；滞水窗口只在不再逼近时触发；窗口内放行。 */
    private static void guardDecisionThresholds() {
        check(WaterCrossingProbe.waterLegExpired(600, 0),
                "reaching the absolute in-water cap abandons the leg even while approaching");
        check(!WaterCrossingProbe.waterLegExpired(599, 0),
                "a water crossing that keeps approaching is never abandoned before the cap");
        check(WaterCrossingProbe.waterLegExpired(100, 100),
                "stagnant water beyond the stall window abandons the leg");
        check(!WaterCrossingProbe.waterLegExpired(30, 99),
                "short wading inside the stall window keeps the leg alive");
    }

    /** 场景①：落点列判水与穿水路线共用一份列缓存；浅滩短渡不判穿水。 */
    private static void waterLandingAndRouteProbe(InteractionWorldTestHarness w) {
        // 地面 y=1 铺石头；深水带 x=5..13（y=2、3 两格水）；浅水带 x=14（只有 y=3 一格水）。
        for (int x = 0; x <= 15; x++) for (int z = 0; z <= 15; z++)
            w.set(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
        for (int x = 5; x <= 13; x++) for (int z = 0; z <= 15; z++) {
            w.set(new BlockPos(x, 2, z), Blocks.WATER.defaultBlockState());
            w.set(new BlockPos(x, 3, z), Blocks.WATER.defaultBlockState());
        }
        for (int z = 0; z <= 15; z++) w.set(new BlockPos(14, 3, z), Blocks.WATER.defaultBlockState());
        var probe = new WaterCrossingProbe(w.level);
        check(probe.landsOnWater(7, 5), "an ocean-surface column is a water landing");
        check(probe.landsOnWater(14, 5), "a shallow water column is still a water landing");
        check(!probe.landsOnWater(3, 5), "a stone column is a dry landing");
        // 采样步长 8：两个采样段全为深水判穿水；跨一格浅水带到对岸干地不判穿水。
        check(probe.crossesWater(new BlockPos(2, 2, 5), new BlockPos(13, 2, 5)),
                "a route whose loaded samples are all deep water crosses water");
        check(!probe.crossesWater(new BlockPos(12, 2, 5), new BlockPos(15, 2, 5)),
                "a short wade across a shallow strip to dry land stays exempt");
        check(PhysicalStructureSearchCompanionTask.candidateBlockedByWater(true, false),
                "a waypoint landing on open water is rejected");
        check(PhysicalStructureSearchCompanionTask.candidateBlockedByWater(false, true),
                "a waypoint behind a water crossing is deprioritized");
        check(!PhysicalStructureSearchCompanionTask.candidateBlockedByWater(false, false),
                "a dry waypoint behind dry terrain is accepted");
    }

    /**
     * 场景②：航点腿行进中身体滞水且不再逼近目标——守卫触发并当场弃腿：失败明细记录弃腿
     * 原因，计入弃腿次数与前沿失败并升级处置，而不是任由身体在水里漂到租约烧完。
     */
    private static void stalledWaterLegIsAbandoned(InteractionWorldTestHarness w) throws Exception {
        var task = newTask(w);
        set(task, "origin", w.player.blockPosition().immutable());
        attachSector(task, w);
        set(task, "stage", stageEnum("MOVE_FRONTIER"));
        // 目标列 (8,?,8) 是干地石面；移动子任务保持未启动的真实实例，只验证父任务侧的守卫与弃腿处置。
        w.set(new BlockPos(8, 1, 8), Blocks.STONE.defaultBlockState());
        long now = w.level.getGameTime();
        var record = new MoveToTaskRecord(
                "test-leg", now + 4000, 8.0, null, 8.0, null, false, false);
        set(task, "moveChild", new MoveToCompanionTask(w.player, record));
        set(task, "moveRecord", record);
        set(task, "activeMoveTarget", new BlockPos(8, 1, 8));
        Method guard = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("tickWaterLegGuard");
        guard.setAccessible(true);
        Method abandon = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("abandonLegInWater", boolean.class, boolean.class, boolean.class);
        abandon.setAccessible(true);

        // 对照刻：身体离水，守卫放行，不做弃腿。
        check(!(Boolean) guard.invoke(task), "a dry body keeps the leg alive");
        check((int) get(task, "waterAbandonments") == 0, "no abandonment while dry");

        // 滞水刻：身体入水，连续滞水已到窗口且距离不再拉近——守卫触发，弃腿并升级处置。
        setInWater(w);
        set(task, "legWaterTicks", 99);
        set(task, "legWaterBestDistance", 0.0);
        set(task, "lastWaterApproachTick", now - 100);
        check((Boolean) guard.invoke(task), "stagnant water beyond the window fires the guard");
        TaskState state = (TaskState) abandon.invoke(task, false, false, false);
        check(state == TaskState.RUNNING, "abandonment escalates instead of failing the search");
        check((int) get(task, "waterAbandonments") == 1, "the abandonment is counted once");
        check((int) get(task, "frontierFailed") == 1, "an abandoned frontier leg counts as failed");
        check(get(task, "moveChild") == null, "the stalled leg's child is stopped and released");
        check(!"MOVE_FRONTIER".equals(stage(task)),
                "the stalled leg does not stay in its travel stage");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) get(task, "legFailures");
        check(failures != null && !failures.isEmpty()
                        && "frontier_leg_abandoned_in_water".equals(
                                failures.get(failures.size() - 1).get("kind")),
                "the failure ledger names water abandonment as the reason");
        Method resultData = PhysicalStructureSearchCompanionTask.class
                .getDeclaredMethod("resultData");
        resultData.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resultData.invoke(task);
        check(Integer.valueOf(1).equals(((Number) data.get("leg_water_abandonments")).intValue()),
                "the receipt reports the water abandonment count");
    }

    private static void setInWater(InteractionWorldTestHarness w) throws Exception {
        // Entity.isInWater() 直读 wasTouchingWater 标志，测试里直接置位（fluidHeight 同步留痕）。
        field(w.player.getClass(), "wasTouchingWater").set(w.player, true);
        @SuppressWarnings("unchecked")
        Object2DoubleMap<Object> fluidHeight =
                (Object2DoubleMap<Object>) field(w.player.getClass(), "fluidHeight").get(w.player);
        fluidHeight.put(FluidTags.WATER, 0.9);
    }

    private static PhysicalStructureSearchCompanionTask newTask(InteractionWorldTestHarness w) {
        var record = new PhysicalStructureSearchTaskRecord(
                "water-" + UUID.randomUUID(), w.level.getGameTime() + 20_000L,
                "minecraft:village", 256, false, false);
        return new PhysicalStructureSearchCompanionTask(w.player, record);
    }

    private static void attachSector(Object task, InteractionWorldTestHarness w) throws Exception {
        var record = (PhysicalStructureSearchTaskRecord) get(task, "r");
        set(task, "sector", record.sector.at(
                w.player.getX(), w.player.getZ(), w.player.getYRot()));
    }

    private static Object stageEnum(String name) throws Exception {
        Class<?> type = Class.forName(
                PhysicalStructureSearchCompanionTask.class.getName() + "$Stage");
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        throw new IllegalStateException("no stage " + name);
    }

    private static String stage(Object task) throws Exception {
        return String.valueOf(get(task, "stage"));
    }

    private static Object get(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        field(owner.getClass(), name).set(owner, value);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
