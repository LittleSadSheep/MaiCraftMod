// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.tools.work.BuildTool;

/**
 * 167 实机场景的回归：开阔平地单格放火把必须能证明站位与点击，不允许再出现
 * 「可达站位无名全拒」。同时核对站位被拒时回执能逐闸点名（闸名+坐标采样）。
 */
public final class BuildPlacementOpenGroundTorchTest {
    private static final Vec3 ANCHOR = new Vec3(3.5, 1, 3.5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        openGroundAirCell(); grassCellSupport(); plannerCoversTorch(); floatingTargetNamesItsFace();
        heldHandsDoNotChangeGestureProof(); deniedGesturesStillNameTheirGate();
        System.out.println("BuildPlacementOpenGroundTorchTest: open-ground torch access, planner coverage and named rejection gates passed");
    }

    private static void openGroundAirCell() throws Exception {
        try (var h = fixture()) {
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            var search = search(h, target);
            check(search.accepted() && !search.access().edge() && !search.access().gesture().sneak(),
                    "an open flat ground torch beside the body must prove an ordinary standing stance");
            check(search.access().gesture().face() == Direction.UP
                    && search.access().gesture().clicked().equals(target.pos().below()),
                    "a ground torch is placed by clicking the top face of the block under the target cell");
            check(search.gateCounts().isEmpty(), "an accepted search leaves no pending rejection gates behind");
        }
    }

    private static void grassCellSupport() throws Exception {
        try (var h = fixture()) {
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            h.set(target.pos(), Blocks.SHORT_GRASS.defaultBlockState());
            var world = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
            var projected = BuildPlacementGeometry.projectedGestureFrom(h.player, target, world, h.level::isLoaded, ANCHOR, false);
            check(projected != null, "a replaceable plant inside the target cell must not block the native click proof");
            var search = search(h, target);
            check(search.accepted(), "open ground torch placement stays provable when the target cell holds short grass");
        }
    }

    private static void plannerCoversTorch() throws Exception {
        try (var h = fixture()) {
            BuildTaskRecord.Target target = torchTarget(new BlockPos(6, 1, 4));
            var planner = new BuildWorksitePlanner.Search(h.player, List.of(target), Map.of(target.pos().asLong(), target),
                    pos -> true, LongSets.emptySet(), java.util.Set.of(), (t, g) -> true);
            BuildWorksitePlanner.Progress progress = null;
            for (int slice = 0; slice < 200; slice++) {
                progress = planner.advance(64);
                if (progress.complete()) break;
            }
            check(progress != null && progress.complete() && progress.best() != null,
                    "the worksite planner must cover a single open-ground torch instead of reporting no usable worksite");
            check(progress.best().placements().stream().anyMatch(p -> p.target().pos().equals(target.pos())),
                    "the found worksite actually places the torch target");
        }
    }

    private static void floatingTargetNamesItsFace() throws Exception {
        try (var h = fixture()) {
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            // 目标正下方没有支撑（坑口）：火把无处附着。这是目标级事实，必须在扫描任何站位前点名，
            // 不能再让每个可达站位重复推导同一结论后汇成无名 no_click_gesture 全拒（167 批六A 实机病症）。
            h.set(target.pos().below(), Blocks.AIR.defaultBlockState());
            var search = search(h, target);
            check(!search.accepted() && "target_attachment_face_missing".equals(search.reason()),
                    "an unsupportable torch cell is rejected at the target level before visiting any stance");
            check(search.checked() == 0 && search.gateCounts().isEmpty(),
                    "a target-level rejection does not spend stance visits or per-stance gates on it");
            check("air".equals(search.attachmentNeighbors().get("down")),
                    "the rejection receipt names the six neighbours so the caller can pick a grounded cell");
        }
    }

    private static void heldHandsDoNotChangeGestureProof() throws Exception {
        try (var h = fixture()) {
            // 批六A 现场主手是工具组、副手握着 19 支火把：手势证明只依赖几何与原生放置预测，
            // 不读手上物品；把这一事实钉进回归，防止未来往手势层引入主手/副手分叉。
            h.player.getInventory().items.set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
            h.player.getInventory().offhand.set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 19));
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            var search = search(h, target);
            check(search.accepted(),
                    "an off-hand torch stack and a tool in the main hand do not block the open-ground gesture proof");
        }
    }

    private static void deniedGesturesStillNameTheirGate() throws Exception {
        try (var h = fixture()) {
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            // 目标有支撑面但所有点击手法都被真实回执拉黑：逐闸归因必须保留，
            // 每个被拒站位以 no_click_gesture 闸名与坐标采样进入回执。
            var world = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
            var search = new BuildPlacementAccessSearch(h.player, target, world, ANCHOR, LongSets.emptySet(),
                    PhysicalObstacleSnapshot.EMPTY, 512, true, gesture -> false);
            for (int step = 0; step < 500 && !search.advance(16); step++) { }
            check(!search.accepted() && "no_reachable_placement_stance".equals(search.reason()),
                    "a search whose every gesture is denied reports no reachable placement stance");
            Integer noGesture = search.gateCounts().get("no_click_gesture");
            check(noGesture != null && noGesture > 0 && !search.gateSamples().get("no_click_gesture").isEmpty(),
                    "per-stance rejections still name the gate and sample positions");
        }
    }

    private static BuildPlacementAccessSearch search(InteractionWorldTestHarness h, BuildTaskRecord.Target target) {
        var world = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
        var search = new BuildPlacementAccessSearch(h.player, target, world, ANCHOR, LongSets.emptySet(),
                PhysicalObstacleSnapshot.EMPTY, 512, true);
        for (int step = 0; step < 500 && !search.advance(16); step++) { }
        return search;
    }

    private static BuildTaskRecord.Target torchTarget(BlockPos at) {
        // 与 place_block 的单格蓝图同一份解析：真实走 BuildTool 的精确状态目标，不手搓简化目标。
        com.google.gson.JsonObject cell = new com.google.gson.JsonObject();
        cell.addProperty("op", "set");
        cell.addProperty("block_id", "minecraft:torch");
        cell.addProperty("x", at.getX()); cell.addProperty("y", at.getY()); cell.addProperty("z", at.getZ());
        com.google.gson.JsonArray ops = new com.google.gson.JsonArray(); ops.add(cell);
        return BuildTool.resolvedTargets(ops, true).getFirst();
    }

    private static InteractionWorldTestHarness fixture() throws Exception {
        var h = new InteractionWorldTestHarness();
        Field dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        h.position(ANCHOR); h.player.setDeltaMovement(0, -.0784, 0); return h;
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
