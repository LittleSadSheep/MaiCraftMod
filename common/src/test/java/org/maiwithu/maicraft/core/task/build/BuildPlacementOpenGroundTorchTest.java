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
        heldHandsDoNotChangeGestureProof(); deniedGesturesStillNameTheirGate(); plantTargetKeepsSupportFacts();
        wallNeighbourProvesAttachGesture(); leavesNeighbourShortCircuitsAsMissingFace(); torchTargetAcceptsWallVariant();
        faceMissingReachesTerminalAttribution();
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
            // 167 三轮实机形态：目标有支撑面仍全拒。六向事实必须随失败回执一起出现，
            // 否则下一轮无法区分“支撑误判”与“真正的点击不可能”。
            String down = search.attachmentNeighbors().get("down");
            check(down != null && !down.equals("air") && !down.startsWith("replaceable:"),
                    "a denied search with a grounded target still names the solid support under the target cell");
        }
    }

    private static void plantTargetKeepsSupportFacts() throws Exception {
        try (var h = fixture()) {
            // 实机草地的常见形态：目标格里长着可替换的短草而非空气。支撑面在脚下存在，
            // 快速归因闸不能把这种目标误判成附着面缺失；六向事实里要逐字点名可替换内容物。
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            h.set(target.pos(), Blocks.SHORT_GRASS.defaultBlockState());
            var world = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of());
            var search = new BuildPlacementAccessSearch(h.player, target, world, ANCHOR, LongSets.emptySet(),
                    PhysicalObstacleSnapshot.EMPTY, 512, true, gesture -> false);
            for (int step = 0; step < 500 && !search.advance(16); step++) { }
            check(!"target_attachment_face_missing".equals(search.reason()),
                    "a replaceable plant in the target cell is not an attachment-face failure while the ground below supports");
            check("replaceable:minecraft:short_grass".equals(search.attachmentNeighbors().get("target")),
                    "the receipt names what actually occupies the target cell");
        }
    }

    private static void wallNeighbourProvesAttachGesture() throws Exception {
        try (var h = fixture()) {
            // 167 批六A 四点矩阵点位3：目标格悬空、唯一邻格是圆石。原版本可贴壁挂火把，
            // 实机却 255 个站位全部 no_click_gesture——贴附目标的点击手势证明不得缺位。
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            h.set(target.pos().below(), Blocks.AIR.defaultBlockState());
            h.set(target.pos().north(), Blocks.COBBLESTONE.defaultBlockState());
            var search = search(h, target);
            check(search.accepted(), "a torch cell whose only neighbour is cobblestone must prove a wall-attach gesture");
            check(search.access().gesture().clicked().equals(target.pos().north())
                            && search.access().gesture().face() == Direction.SOUTH,
                    "the wall-attach gesture clicks the south face of the cobblestone neighbour");
            check(search.gateCounts().getOrDefault("no_click_gesture", 0) == 0,
                    "no stance may be rejected for a missing click gesture when a wall face exists");
        }
    }

    private static void leavesNeighbourShortCircuitsAsMissingFace() throws Exception {
        try (var h = fixture()) {
            // 167 批六A 四点矩阵点位4：树叶不是火把的有效附着面，不能再被计入候选导致
            // 快速归因闸失效、跑满数百站位；非实心邻格要在扫描前点名并入 face_missing。
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            h.set(target.pos().below(), Blocks.DARK_OAK_LEAVES.defaultBlockState());
            h.set(target.pos().west(), Blocks.DARK_OAK_LEAVES.defaultBlockState());
            var search = search(h, target);
            check(!search.accepted() && "target_attachment_face_missing".equals(search.reason()),
                    "leaves are not a valid torch attachment face and must trip the target-level gate");
            check(search.checked() == 0,
                    "an all-non-sturdy torch cell is rejected before visiting any stance");
            check("non_sturdy:minecraft:dark_oak_leaves".equals(search.attachmentNeighbors().get("down"))
                            && "non_sturdy:minecraft:dark_oak_leaves".equals(search.attachmentNeighbors().get("west")),
                    "the receipt names each non-sturdy neighbour so the caller knows what to clear");
        }
    }

    private static void torchTargetAcceptsWallVariant() throws Exception {
        try (var h = fixture()) {
            // 预测按立式、原生落成墙式是同一格完成；验收不识别双形态时，
            // 贴墙火把会卡在「出手成功方块永不出现」（167 二轮实机签名）。
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            var wall = Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, Direction.SOUTH);
            check(target.acceptsPlacedState(wall),
                    "a torch target accepts the wall variant placed by the native item dispatch");
            // 五轮实机新签名：手势与预检都放行，点击后核验与终局验收却仍按图纸期望的
            // 立式火把点名 placement_state_mismatch——核验路径必须同样认得墙面变体。
            check(target.matches(wall),
                    "the post-click verification accepts the wall variant as the placed result");
            check(BuildPlacementGeometry.placementComplete(target, wall),
                    "a wall torch in the target cell completes the placement without another click");
            check(target.constructionMatches(wall),
                    "construction verification accepts the wall variant as this cell being built");
            check(target.matches(Blocks.TORCH.defaultBlockState()),
                    "the ground variant keeps satisfying verification unchanged");
            check(!target.matches(Blocks.STONE.defaultBlockState()),
                    "an unrelated block is still not accepted as the torch target result");
        }
    }

    private static void faceMissingReachesTerminalAttribution() throws Exception {
        try (var h = fixture()) {
            // 167 五轮实机：face_missing 只在进度事件闪现，终局被 construction_worksite_unproven/no_path
            // 笼统覆盖。任务层的终局归因键是通道驱动的 failure 名；这个名字一旦变动或缺席，
            // 终局 cause_code 就会退回无名 no_path——把「驱动以该名失败、证据随行」钉在这里。
            BuildTaskRecord.Target target = torchTarget(new BlockPos(5, 1, 3));
            h.set(target.pos().below(), Blocks.AIR.defaultBlockState());
            var drive = new BuildPlacementAccessDrive(h.player, target,
                    org.maiwithu.maicraft.core.pathing.execute.PlayerNav.ContextProvider.DEFAULT,
                    () -> true, null, () -> BuildPlacementAccessDrive.Status.READY);
            BuildPlacementAccessDrive.Status status = BuildPlacementAccessDrive.Status.RUNNING;
            for (int tick = 0; tick < 400 && status == BuildPlacementAccessDrive.Status.RUNNING; tick++) {
                status = drive.tick();
                h.nextTick();
            }
            check(status == BuildPlacementAccessDrive.Status.UNAVAILABLE,
                    "an unsupportable torch cell ends the access drive without proving any stance");
            check("target_attachment_face_missing".equals(drive.failure()),
                    "the drive failure names the missing attachment face so the task can report it as the terminal cause");
            check(drive.evidence().get("target_attachment_neighbors") != null,
                    "the terminal evidence carries the six-neighbour facts for the receipt");
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
