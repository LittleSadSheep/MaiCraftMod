// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.integration.create.CreateInteractionSurface;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;

/** 操作面约束使用原生区块射线，不能用可见侧面或被上方方块挡住的顶面替代工件台入口。 */
public final class MachineInteractionSurfaceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(CreateInteractionSurface.requiredFace(ResourceLocation.parse("create:depot")) == Direction.UP
                && CreateInteractionSurface.requiredFace(Blocks.CRAFTING_TABLE.defaultBlockState()) == null,
                "only the native depot contract requires a top click; ordinary menus keep all visible faces");
        deployerRegions();
        visibleSurfaceGetsBoundedAimCorrection();
        try (var h = new InteractionWorldTestHarness()) {
            var at = new BlockPos(8, 1, 5); h.set(at, Blocks.STONE.defaultBlockState());
            var lowEye = new Vec3(8.5, 1.5, 8.5);
            check(FirstPersonInteractionTargeting.visibleBlockHit(h.level, h.player, lowEye, at, 4.5) != null
                    && FirstPersonInteractionTargeting.visibleBlockHit(h.level, h.player, lowEye, at, 4.5, Direction.UP) == null,
                    "seeing the side below the work surface cannot admit this stand for a top-only operation");
            var highEye = new Vec3(8.5, 2.62, 6.5);
            var top = FirstPersonInteractionTargeting.visibleBlockHit(h.level, h.player, highEye, at, 4.5, Direction.UP);
            check(top != null && top.getDirection() == Direction.UP && top.getBlockPos().equals(at),
                    "the adjacent ground-height player can select the exposed native top surface");
            // 顶面封住时，即使台座侧面仍可见也必须排除这个站位；不允许穿过上方机器点击。
            h.set(at.above(), Blocks.STONE.defaultBlockState());
            check(FirstPersonInteractionTargeting.visibleBlockHit(h.level, h.player, highEye, at, 4.5, Direction.UP) == null,
                    "an occluded top cannot be replaced by a visible side");
            // 前端侧面可见时仍允许换物；把朝下机械手装料限制为 DOWN 面会错误排除这种可站立位置。
            h.set(at.above(), Blocks.AIR.defaultBlockState());
            var front = CreateInteractionSurface.forUse(ResourceLocation.parse("create:deployer"), Direction.DOWN,
                    ResourceLocation.parse("minecraft:iron_nugget"));
            var sideFront = front.visibleHit(h.level, h.player, new Vec3(8.5, 1.1, 8.5), at, 4.5);
            check(sideFront != null && sideFront.getDirection() == Direction.SOUTH && front.accepts(sideFront),
                    "front-region side clicks remain native hand swaps");
            h.set(at.south(), Blocks.STONE.defaultBlockState());
            check(front.visibleHit(h.level, h.player, new Vec3(8.5, 1.1, 8.5), at, 4.5) == null,
                    "hand targeting cannot click through the intervening block");
        }
        System.out.println("MachineInteractionSurfaceTest: passed");
    }

    private static void deployerRegions() {
        var deployer = ResourceLocation.parse("create:deployer");
        var ingredient = ResourceLocation.parse("minecraft:iron_nugget");
        var at = new BlockPos(8, 3, 5);
        // 六种朝向使用同一原生轴向距离；前端边界允许换物，机壳中心不允许。
        for (Direction facing : Direction.values()) {
            var rule = CreateInteractionSurface.forUse(deployer, facing, ingredient);
            var normal = Vec3.atLowerCornerOf(facing.getNormal());
            var center = Vec3.atCenterOf(at);
            check(!rule.accepts(new BlockHitResult(center, facing, at, false)), "casing center is outside hand region");
            check(!rule.accepts(new BlockHitResult(center.add(normal.scale(.249)), facing, at, false)), "before native boundary");
            check(rule.accepts(new BlockHitResult(center.add(normal.scale(.25)), facing, at, false)), "native boundary accepted");
        }
        // 扳手调向和相邻机械手放置保持各自原生行为，普通空手才走机械手换物入口。
        check(!CreateInteractionSurface.forUse(deployer, Direction.DOWN, ResourceLocation.parse("create:wrench")).constrained(),
                "wrench is not a hand swap");
        check(!CreateInteractionSurface.forUse(deployer, Direction.DOWN, deployer).constrained(), "placement helper is not a hand swap");
        check(CreateInteractionSurface.forUse(deployer, Direction.DOWN, ResourceLocation.parse("minecraft:air")).constrained(),
                "empty hand also swaps with deployer hand");
        check(!CreateInteractionSurface.forUse(Blocks.CRAFTING_TABLE.defaultBlockState(), null).constrained(),
                "ordinary empty-hand menus stay unconstrained");
    }
    private static void visibleSurfaceGetsBoundedAimCorrection() throws Exception {
        // 已有原生可见顶面时先重取瞄准点，遮挡出现后立即停止；整个纠偏阶段不得发出点击。
        try (var h = new InteractionWorldTestHarness()) {
            var at = new BlockPos(8, 1, 5); h.set(at, Blocks.STONE.defaultBlockState());
            h.position(new Vec3(8.5, 1, 6.5));
            var task = new InteractAtCompanionTask(h.player,
                    new InteractAtTaskRecord("surface-aim", 200, MouseButton.RIGHT, at, 0, null));
            var top = new CreateInteractionSurface.Rule(Direction.UP, null);
            var refine = InteractAtCompanionTask.class.getDeclaredMethod("refineSurfaceAim", CreateInteractionSurface.Rule.class);
            refine.setAccessible(true);
            for (int attempt = 0; attempt < 10; attempt++)
                check((boolean) refine.invoke(task, top), "visible surface permits bounded pre-click aiming");
            check(!(boolean) refine.invoke(task, top), "a persistent native-ray mismatch cannot cause endless aiming");
            var blocked = new InteractAtCompanionTask(h.player,
                    new InteractAtTaskRecord("blocked-surface", 200, MouseButton.RIGHT, at, 0, null));
            h.set(at.above(), Blocks.STONE.defaultBlockState());
            check(!(boolean) refine.invoke(blocked, top), "an actual covering block is not mistaken for a camera delay");
            check(h.blockUses() == 0 && h.itemUses() == 0, "aim correction never replays a native use");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
