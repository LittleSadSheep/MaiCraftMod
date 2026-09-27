// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.integration.create.CreateInteractionSurface;

/** 操作面约束使用原生区块射线，不能用可见侧面或被上方方块挡住的顶面替代工件台入口。 */
public final class MachineInteractionSurfaceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(CreateInteractionSurface.requiredFace(ResourceLocation.parse("create:depot")) == Direction.UP
                && CreateInteractionSurface.requiredFace(Blocks.CRAFTING_TABLE.defaultBlockState()) == null,
                "only the native depot contract requires a top click; ordinary menus keep all visible faces");
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
        }
        System.out.println("MachineInteractionSurfaceTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
