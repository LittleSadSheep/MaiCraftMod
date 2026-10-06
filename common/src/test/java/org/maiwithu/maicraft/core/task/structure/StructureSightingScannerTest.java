// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.structure;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/**
 * 联合锚点扫描全链路：索引感知画像方块后，一轮四相在多刻驻留内走完并交付 sighting；
 * 观察点间换位不弃在途轮——覆盖半径内继续旧轮，热索引下新址仍能完成判定。
 * 村庄场景按真实几何建模：屋外可透视证据（土径、露天堆肥桶）跨越旧聚集半径分布，
 * 屋内设施被墙体遮挡不得计入；只有土径而没有生活方块不构成村庄。
 */
public final class StructureSightingScannerTest {
    private static final BlockPos BELL = new BlockPos(8, 1, 5);
    private static final BlockPos COMPOSTER = new BlockPos(9, 1, 5);
    private static final BlockPos BARREL = new BlockPos(7, 1, 5);
    private static final BlockPos LECTERN = new BlockPos(8, 1, 6);
    private static final BlockPos STONECUTTER = new BlockPos(8, 1, 4);
    private static final BlockPos LOOM = new BlockPos(9, 1, 6);
    private static final BlockPos BARREL_2 = new BlockPos(7, 1, 6);
    private static final BlockPos COMPOSTER_2 = new BlockPos(9, 1, 4);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        profileCoversOutdoorVillageEvidence();
        roundCompletesInDwell();
        relocationKeepsIndex();
        spreadVillageConfirmsFromOutdoorEvidence();
        pathsWithoutActivityDoNotConfirm();
        indoorFurnitureDoesNotCount();
        denseCommonBlocksDoNotStarveSparseEvidence();
        System.out.println("StructureSightingScannerTest: passed");
    }

    private static InteractionWorldTestHarness villageWorld() throws Exception {
        var world = new InteractionWorldTestHarness();
        world.set(BELL, Blocks.BELL.defaultBlockState());
        world.set(COMPOSTER, Blocks.COMPOSTER.defaultBlockState());
        world.set(BARREL, Blocks.BARREL.defaultBlockState());
        world.set(LECTERN, Blocks.LECTERN.defaultBlockState());
        world.set(STONECUTTER, Blocks.STONECUTTER.defaultBlockState());
        world.set(LOOM, Blocks.LOOM.defaultBlockState());
        world.set(BARREL_2, Blocks.BARREL.defaultBlockState());
        world.set(COMPOSTER_2, Blocks.COMPOSTER.defaultBlockState());
        return world;
    }

    private static StructureSightingScanner scanner() {
        var resolved = StructureEvidenceProfiles.resolve("minecraft:village");
        if (resolved == null) throw new AssertionError("the built-in village profile must resolve");
        return new StructureSightingScanner(List.of(resolved), 32);
    }

    /** 模拟真实村庄的土径延伸与露天设施：证据块散布多座建筑之间，屋内床被墙体遮住。 */
    private static InteractionWorldTestHarness spreadVillageWorld() throws Exception {
        var world = new InteractionWorldTestHarness();
        world.set(BELL, Blocks.BELL.defaultBlockState());
        // 土径长廊：多座锚点散布，土径是屋外最主要的可透视村庄证据。
        for (int x = 10; x <= 15; x++) {
            world.set(new BlockPos(x, 1, 5), Blocks.DIRT_PATH.defaultBlockState());
        }
        // 露天堆肥桶分居两处；地面视角下它们是土径之外仅有的可透视生活方块。
        world.set(new BlockPos(14, 1, 9), Blocks.COMPOSTER.defaultBlockState());
        world.set(new BlockPos(2, 1, 14), Blocks.COMPOSTER.defaultBlockState());
        // 室内床：石壳封死，视线不可达，任何情况下都不得计入。
        world.set(new BlockPos(12, 2, 13), Blocks.WHITE_BED.defaultBlockState());
        world.set(new BlockPos(13, 2, 13), Blocks.WHITE_BED.defaultBlockState());
        for (int x = 11; x <= 14; x++) {
            for (int z = 12; z <= 14; z++) {
                for (int y = 1; y <= 3; y++) {
                    if ((x == 12 || x == 13) && z == 13 && y == 2) continue;
                    world.set(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        return world;
    }

    /** 画像本身要收下屋外证据：土径进第一组（锚点）、聚集半径取村庄尺度、总量下限按土径数量上调。 */
    private static void profileCoversOutdoorVillageEvidence() {
        var resolved = StructureEvidenceProfiles.resolve("minecraft:village");
        if (resolved == null) throw new AssertionError("the built-in village profile must resolve");
        check(resolved.profile().clusterRadius() == 64,
                "the village cluster radius must cover a whole village, not one courtyard");
        check(resolved.profile().minimumTotal() >= 8,
                "the village total minimum must be carried by the abundant dirt paths, not a bare bell");
        var center = resolved.groups().get(0);
        if (!center.blocks().contains(Blocks.BELL) || !center.blocks().contains(Blocks.DIRT_PATH)) {
            throw new AssertionError("the village center group must anchor on both the bell and dirt paths");
        }
        if (resolved.groups().get(1).minimum() > 2) {
            throw new AssertionError("the activity minimum must be reachable from outdoor evidence alone");
        }
    }

    /** 观察驻留内的完整一轮：四相走完、聚类判定成立、sighting 携带分组计数，结算后轮次空闲。 */
    private static void roundCompletesInDwell() throws Exception {
        try (var world = villageWorld()) {
            StructureSightingScanner.Sighting sighting = null;
            var scanner = scanner();
            boolean sawRoundInProgress = false;
            for (int tick = 0; tick < 200 && sighting == null; tick++) {
                world.nextTick();
                sighting = scanner.tick(world.player).stream().findFirst().orElse(null);
                sawRoundInProgress |= scanner.roundInProgress();
            }
            check(sawRoundInProgress, "a started round must report itself as in progress while scanning");
            check(!scanner.roundInProgress(), "a settled round must let the observation dwell end");
            check(sighting != null, "a visible village signature cluster must produce a sighting");
            assert sighting != null;
            check("minecraft:village".equals(sighting.canonicalId()), "the sighting uses the canonical id");
            check(sighting.anchor().equals(BELL), "the bell, the nearest first-group anchor, is the reported anchor");
            check(sighting.totalBlocks() >= 8, "the signature counts the bell plus the required activity evidence");
            check(sighting.groupCounts().getOrDefault("village_activity", 0) >= 2,
                    "the activity group reaches its minimum before the cluster is confirmed");
            scanner.release();
        }
    }

    /** 覆盖半径内换观察位不弃在途轮：已建索引条目仍在，换位后少数几刻内完成判定。 */
    private static void relocationKeepsIndex() throws Exception {
        try (var world = villageWorld()) {
            var scanner = scanner();
            // 先推进几刻让锚点索引吃进已建条目，再模拟观察点间移动：新位置仍在轮次覆盖半径内。
            for (int tick = 0; tick < 3; tick++) {
                world.nextTick();
                scanner.tick(world.player);
            }
            world.position(new Vec3(14.5, 1, 14.5));
            StructureSightingScanner.Sighting sighting = null;
            int settleTicks = 0;
            for (int tick = 0; tick < 200 && sighting == null; tick++) {
                world.nextTick();
                sighting = scanner.tick(world.player).stream().findFirst().orElse(null);
                if (sighting == null) settleTicks++;
            }
            check(sighting != null, "a relocated round must still finish clustering over the warm index");
            check(settleTicks < 200, "the relocated round must settle on the accumulated index, not rebuild forever");
            assert sighting != null;
            check(sighting.totalBlocks() >= 8, "the relocated round must still count the full signature cluster");
            scanner.release();
        }
    }

    /** 村庄真实几何：土径与露天设施延展开来仍要成账——旧聚集半径会漏掉这批证据，正是实机零记录的形状。 */
    private static void spreadVillageConfirmsFromOutdoorEvidence() throws Exception {
        try (var world = spreadVillageWorld()) {
            var scanner = scanner();
            StructureSightingScanner.Sighting sighting = null;
            for (int tick = 0; tick < 300 && sighting == null; tick++) {
                world.nextTick();
                sighting = scanner.tick(world.player).stream().findFirst().orElse(null);
            }
            check(sighting != null, "an outdoor village signature spread across buildings must still confirm");
            assert sighting != null;
            check(sighting.anchor().equals(BELL), "the bell is the nearest first-group anchor of the spread village");
            check(sighting.groupCounts().getOrDefault("village_center", 0) >= 1,
                    "the center group must count the bell or the dirt paths");
            check(sighting.groupCounts().getOrDefault("village_activity", 0) >= 2,
                    "the outdoor composters must satisfy the activity minimum");
            scanner.release();
        }
    }

    /** 只有土径没有可见生活方块不构成村庄：土径是路引，不能单独冒充聚落证据。 */
    private static void pathsWithoutActivityDoNotConfirm() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.set(BELL, Blocks.BELL.defaultBlockState());
            for (int x = 10; x <= 15; x++) {
                world.set(new BlockPos(x, 1, 5), Blocks.DIRT_PATH.defaultBlockState());
            }
            var scanner = scanner();
            boolean anySighting = false;
            for (int tick = 0; tick < 300; tick++) {
                world.nextTick();
                anySighting |= !scanner.tick(world.player).isEmpty();
            }
            check(!anySighting, "dirt paths without any visible activity block must not confirm a village");
            scanner.release();
        }
    }

    /** 室内生活方块被墙体遮住时不得计入：村庄证据只收从角色眼睛真正可见的方块。 */
    private static void indoorFurnitureDoesNotCount() throws Exception {
        try (var world = spreadVillageWorld()) {
            // 拿掉两座露天堆肥桶后，剩下的生活方块只有石壳里的床——不应再满足活动组下限。
            world.set(new BlockPos(14, 1, 9), Blocks.AIR.defaultBlockState());
            world.set(new BlockPos(2, 1, 14), Blocks.AIR.defaultBlockState());
            var scanner = scanner();
            StructureSightingScanner.Sighting sighting = null;
            for (int tick = 0; tick < 300 && sighting == null; tick++) {
                world.nextTick();
                sighting = scanner.tick(world.player).stream().findFirst().orElse(null);
            }
            check(sighting == null, "wall-hidden furniture must not be counted as visible evidence");
            scanner.release();
        }
    }

    /**
     * 实机三连败的最小复现：玩家身边一大片常见方块（房屋木板）把联合 MASS 查询的
     * 最近窗口全部占满，远处的露天生活方块（堆肥桶）被挤出查询结果，
     * activity 组永远凑不齐——村庄贴脸也不落账。修复后稠密常见方块不得饿死稀疏证据。
     */
    private static void denseCommonBlocksDoNotStarveSparseEvidence() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.set(BELL, Blocks.BELL.defaultBlockState());
            for (int x = 10; x <= 15; x++) {
                world.set(new BlockPos(x, 1, 5), Blocks.DIRT_PATH.defaultBlockState());
            }
            // 露天生活方块放在远处（距玩家约 17 格），近处铺 200+ 块更近的木板占满最近窗口。
            BlockPos composterFarA = new BlockPos(15, 1, 14);
            BlockPos composterFarB = new BlockPos(13, 1, 15);
            world.set(composterFarA, Blocks.COMPOSTER.defaultBlockState());
            world.set(composterFarB, Blocks.COMPOSTER.defaultBlockState());
            int planks = 0;
            // 木板铺在脚下 y0 作地板：与证据方块不同层，挤占查询窗口但不遮挡视线——
            // 本测试隔离的是「最近窗口被稠密方块占满」这一断相，不掺入可见性遮挡因素。
            for (int x = 0; x <= 15; x++) {
                for (int z = 0; z <= 11; z++) {
                    world.set(new BlockPos(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
                    planks++;
                }
            }
            for (int x = 2; x <= 9; x++) {
                for (int z = 12; z <= 15; z++) {
                    world.set(new BlockPos(x, 0, z), Blocks.OAK_PLANKS.defaultBlockState());
                    planks++;
                }
            }
            if (planks < 200) throw new AssertionError("repro needs more planks than the query window");
            var scanner = scanner();
            StructureSightingScanner.Sighting sighting = null;
            for (int tick = 0; tick < 300 && sighting == null; tick++) {
                world.nextTick();
                sighting = scanner.tick(world.player).stream().findFirst().orElse(null);
            }
            check(sighting != null,
                    "dense common blocks near the body must not crowd sparse village evidence out of the scan");
            assert sighting != null;
            check(sighting.groupCounts().getOrDefault("village_activity", 0) >= 2,
                    "the outdoor composters must be counted even when planks fill the nearby window");
            scanner.release();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
