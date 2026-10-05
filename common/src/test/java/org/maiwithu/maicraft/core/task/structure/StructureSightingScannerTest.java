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
 * 路点间移动触发的换址重开不丢已建的索引条目，热索引下新轮仍能完成判定。
 */
public final class StructureSightingScannerTest {
    private static final BlockPos BELL = new BlockPos(8, 1, 5);
    private static final BlockPos COMPOSTER = new BlockPos(9, 1, 5);
    private static final BlockPos BARREL = new BlockPos(7, 1, 5);
    private static final BlockPos LECTERN = new BlockPos(8, 1, 6);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        roundCompletesInDwell();
        relocationKeepsIndex();
        System.out.println("StructureSightingScannerTest: passed");
    }

    private static InteractionWorldTestHarness villageWorld() throws Exception {
        var world = new InteractionWorldTestHarness();
        world.set(BELL, Blocks.BELL.defaultBlockState());
        world.set(COMPOSTER, Blocks.COMPOSTER.defaultBlockState());
        world.set(BARREL, Blocks.BARREL.defaultBlockState());
        world.set(LECTERN, Blocks.LECTERN.defaultBlockState());
        return world;
    }

    private static StructureSightingScanner scanner() {
        var resolved = StructureEvidenceProfiles.resolve("minecraft:village");
        if (resolved == null) throw new AssertionError("the built-in village profile must resolve");
        return new StructureSightingScanner(List.of(resolved), 32);
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
            check(sighting.anchor().equals(BELL), "the bell, the only first-group anchor, is the reported anchor");
            check(sighting.totalBlocks() >= 4, "the signature counts at least bell plus three activity blocks");
            check(sighting.groupCounts().getOrDefault("village_activity", 0) >= 3,
                    "the activity group reaches its minimum before the cluster is confirmed");
            scanner.release();
        }
    }

    /** 换址重开只换轮心不弃索引：已建段条目仍在，新轮在少数几刻内完成判定。 */
    private static void relocationKeepsIndex() throws Exception {
        try (var world = villageWorld()) {
            var scanner = scanner();
            // 先推进几刻让锚点索引吃进已建条目，再模拟路点间移动触发换址重开。
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
            check(sighting.anchor().equals(BELL), "the relocation only moves the round center, the village is still found");
            scanner.release();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
