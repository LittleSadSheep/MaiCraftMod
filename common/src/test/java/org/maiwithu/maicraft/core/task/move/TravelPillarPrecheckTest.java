// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

import it.unimi.dsi.fastutil.longs.LongSets;

/**
 * travel 上超预检与垫柱回收的闸门口径（169）：
 * ① 目标明显高于局部地表时出发前诚实失败并指路，柱列遮断时同样拒绝；
 * ② 到达后脚下自有垫柱被认领回收，拆不动时按残留如实声明；
 * ③ 未授权动土的路径完全不经过闸门，行为不变。
 */
public final class TravelPillarPrecheckTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        openAirPillarRefusedBeforeDeparture();
        obstructedColumnRefusedBeforeDeparture();
        nearSurfaceTargetStaysAllowed();
        unknownSupportStaysAllowed();
        unauthorizedRecordBypassesTheGate();
        recoveryClaimsContiguousOwnColumn();
        recoveryResidueDeclaredWhenDescentUnavailable();
        System.out.println("TravelPillarPrecheckTest: uphill precheck and pillar recovery contracts hold");
    }

    /** ① 目标柱列通畅且远高于地表：出发前判 OPEN_AIR_PILLAR，指路文本带地表高度与替代坐标。 */
    private static void openAirPillarRefusedBeforeDeparture() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var result = TravelUphillPrecheck.evaluate(h.level, new BlockPos(8, 10, 8), 1);
            check(result.verdict() == TravelUphillPrecheck.Verdict.OPEN_AIR_PILLAR,
                    "悬空目标在出发前被判露天垫柱: " + result);
            check(result.surfaceY() == 0 && result.rise() == 9,
                    "预检携带地表高度与所需爬升: " + result);
            String advice = TravelUphillPrecheck.advice(result, new BlockPos(8, 10, 8));
            check(advice.contains("open-air pillar") && advice.contains("8,1,8"),
                    "拒绝指路给出地表高度替代坐标: " + advice);
            // 任务级闸门走同一判定；出发即失败，不建导航。
            var task = record(h, new BlockPos(8, 10, 8), true);
            check(task.uphillPrecheck().verdict() == TravelUphillPrecheck.Verdict.OPEN_AIR_PILLAR,
                    "授权动土的 exact 目标经过同一闸门");
        }
    }

    /** ① 目标头顶上超段有遮断（洞顶/树叶/结构）：出发前判 COLUMN_OBSTRUCTED，指路绕行或落回地表。 */
    private static void obstructedColumnRefusedBeforeDeparture() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(new BlockPos(8, 11, 8), Blocks.STONE.defaultBlockState());
            var result = TravelUphillPrecheck.evaluate(h.level, new BlockPos(8, 10, 8), 1);
            check(result.verdict() == TravelUphillPrecheck.Verdict.COLUMN_OBSTRUCTED,
                    "上超段遮断在出发前被判强行上掘: " + result);
            check(result.obstruction() != null && result.obstruction().getY() == 11,
                    "遮断结论携带遮断格坐标: " + result);
            check(TravelUphillPrecheck.advice(result, new BlockPos(8, 10, 8)).contains("obstructed at 8, 11, 8"),
                    "遮断指路点名遮断格: " + TravelUphillPrecheck.advice(result, new BlockPos(8, 10, 8)));
            // 柱列中段的悬空方块会被当作垫柱基座重新计爬升，仍按露天垫柱拦截。
            h.set(new BlockPos(8, 11, 8), Blocks.AIR.defaultBlockState());
            h.set(new BlockPos(8, 5, 8), Blocks.STONE.defaultBlockState());
            var floating = TravelUphillPrecheck.evaluate(h.level, new BlockPos(8, 10, 8), 1);
            check(floating.verdict() == TravelUphillPrecheck.Verdict.OPEN_AIR_PILLAR
                            && floating.surfaceY() == 5 && floating.rise() == 4,
                    "悬空基座按其顶面重算爬升，依旧拦露天垫柱: " + floating);
        }
    }

    /** 小台阶爬升在限值内：不拦截，规划器自行处理，预检不替调用方重新解释坐标。 */
    private static void nearSurfaceTargetStaysAllowed() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            check(TravelUphillPrecheck.evaluate(h.level, new BlockPos(8, 3, 8), 1).verdict()
                            == TravelUphillPrecheck.Verdict.ALLOW,
                    "限值内的台阶爬升放行");
            check(TravelUphillPrecheck.evaluate(h.level, new BlockPos(8, 10, 8), 10).verdict()
                            == TravelUphillPrecheck.Verdict.ALLOW,
                    "目标不在头顶方向时不扫描不拦截");
        }
    }

    /** 扫描范围内找不到支撑：事实未知，放行维持原有规划行为，不构成不存在的证据。 */
    private static void unknownSupportStaysAllowed() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.set(new BlockPos(8, 0, 8), Blocks.AIR.defaultBlockState());
            check(TravelUphillPrecheck.evaluate(h.level, new BlockPos(8, 10, 8), 1).verdict()
                            == TravelUphillPrecheck.Verdict.ALLOW,
                    "支撑事实未知时放行，不凭缺席拒绝");
        }
    }

    /** ③ 未授权动土的任务单不经过闸门：行为与修复前完全一致。 */
    private static void unauthorizedRecordBypassesTheGate() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = record(h, new BlockPos(8, 10, 8), false);
            check(task.uphillPrecheck().verdict() == TravelUphillPrecheck.Verdict.ALLOW,
                    "未授权动土的路径不经过闸门，行为不变");
        }
    }

    /** ② 回收只认领脚下连续自有柱：遇缺口即停，途中别处的垫块不扩权乱挖。 */
    private static void recoveryClaimsContiguousOwnColumn() {
        var feet = new BlockPos(0, 3, 8);
        var placed = Set.of(
                new BlockPos(0, 2, 8), new BlockPos(0, 1, 8), new BlockPos(0, 0, 8),
                new BlockPos(5, 1, 5));
        var column = PillarRecovery.ownPillarUnder(placed, feet, Integer.MAX_VALUE);
        check(column.size() == 3
                        && column.get(0).equals(new BlockPos(0, 2, 8))
                        && column.get(2).equals(new BlockPos(0, 0, 8)),
                "回收认领脚下连续自有柱、自顶向下: " + column);
        var gapped = PillarRecovery.ownPillarUnder(
                Set.of(new BlockPos(0, 2, 8), new BlockPos(5, 1, 5)), feet, Integer.MAX_VALUE);
        check(gapped.size() == 1 && gapped.getFirst().equals(new BlockPos(0, 2, 8)),
                "柱列缺口处停止认领: " + gapped);
    }

    /**
     * ② 回收尽力语义：下拆驱动在测试夹具里拿不到原生确认而失败时，
     * 回收停手并按 RESIDUE 声明剩余垫块，不吞残留也不虚报回收完成。
     */
    private static void recoveryResidueDeclaredWhenDescentUnavailable() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            for (int y = 1; y <= 3; y++) {
                h.set(new BlockPos(8, y, 8), Blocks.DIRT.defaultBlockState());
            }
            h.position(new Vec3(8.5, 4, 8.5));
            // 下拆证明与对齐驱动读取真实碰撞尺寸与姿态；夹具未走原版构造器，这里补齐静态站立值。
            var dimensionsField = net.minecraft.world.entity.Entity.class.getDeclaredField("dimensions");
            dimensionsField.setAccessible(true);
            dimensionsField.set(h.player, net.minecraft.world.entity.player.Player.STANDING_DIMENSIONS);
            h.player.setPose(net.minecraft.world.entity.Pose.STANDING);
            h.player.refreshDimensions();
            var recovery = new PillarRecovery(h.player,
                    PillarRecovery.ownPillarUnder(Set.of(
                            new BlockPos(8, 3, 8), new BlockPos(8, 2, 8), new BlockPos(8, 1, 8)),
                            new BlockPos(8, 4, 8), Integer.MAX_VALUE),
                    at -> true, LongSets.emptySet(), PlayerNav.ContextProvider.DEFAULT);
            // 认领时刻读取的实地方块状态与柱列一致，供整柱证明核对。
            check(recovery.status() == PillarRecovery.Status.RUNNING, "回收阶段从认领开始");
            PillarRecovery.Status status = PillarRecovery.Status.RUNNING;
            for (int tick = 0; tick < 400 && status == PillarRecovery.Status.RUNNING; tick++) {
                status = recovery.tick();
                h.nextTick();
            }
            check(status == PillarRecovery.Status.RESIDUE,
                    "下拆不可达时回收按残留收场，不无限等待: " + status);
            var evidence = recovery.evidence();
            check(Integer.valueOf(0).equals(evidence.get("recovered_count"))
                            && !((java.util.List<?>) evidence.get("residue")).isEmpty(),
                    "残留证据如实列出不曾拆回的垫块: " + evidence);
            check(h.player.blockPosition().getY() == 4,
                    "回收失败不移动身体，世界不留半拆状态");
            // 收尾注记把残留坐标与去向写进回执文本。
            check(recovery.note().contains("remain"), "残留注记声明剩余垫块: " + recovery.note());
            // 进行中被打断：stop() 后同样落进残留声明，不丢账。
            var interrupted = new PillarRecovery(h.player,
                    PillarRecovery.ownPillarUnder(Set.of(new BlockPos(8, 3, 8)),
                            new BlockPos(8, 4, 8), Integer.MAX_VALUE),
                    at -> true, LongSets.emptySet(), PlayerNav.ContextProvider.DEFAULT);
            interrupted.stop();
            check(interrupted.status() == PillarRecovery.Status.RESIDUE
                            && interrupted.note().contains("remain"),
                    "中断的回收同样按残留声明");
        }
    }

    /** 用真实任务单构造 travel 任务：exact BLOCK 目标，授权开关由用例决定。 */
    private static MoveToCompanionTask record(InteractionWorldTestHarness h, BlockPos target,
                                              boolean mayAlterTerrain) {
        var r = new MoveToTaskRecord("precheck", 1_000_000L,
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                mayAlterTerrain, false, TransportMode.AUTO, false, true, 0, 0);
        return new MoveToCompanionTask(h.player, r);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
