// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.lighting.RoutineTorchPlacement;

/** 暗洞补光须有真实可达的灯座；已有机器、积水与受保护空间都不能被日常照明覆盖。 */
public final class RoutineTorchPlacementTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check(RoutineTorchPlacement.needsLight(0, 0), "dark cave requires light");
        check(RoutineTorchPlacement.needsLight(12, 4), "dark view still requires light");
        check(!RoutineTorchPlacement.needsLight(8, 12), "already visible area needs no torch");
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(4.5, 1, 4.5));
            var ground = RoutineTorchPlacement.find(h.player, Set.of());
            check(ground != null && ground.desiredState().is(Blocks.TORCH), "flat cave uses visible ground");
            check(RoutineTorchPlacement.stillUsable(h.player, ground, Set.of()), "ground candidate is rechecked");
            check(!RoutineTorchPlacement.stillUsable(h.player, ground, Set.of(ground.pos())), "protected cell is excluded");
            check(!NavigationSafetyContext.withProtectedArea(Set.of(ground.pos()), Set.of(),
                    () -> RoutineTorchPlacement.stillUsable(h.player, ground, Set.of())), "outer protection survives routine planning");
            h.set(ground.pos(), Blocks.WATER.defaultBlockState());
            check(!RoutineTorchPlacement.stillUsable(h.player, ground, Set.of()), "water cannot be displaced by a torch");
            h.set(ground.pos(), Blocks.CHEST.defaultBlockState());
            check(!RoutineTorchPlacement.stillUsable(h.player, ground, Set.of()), "existing blocks are never replaced");
            h.set(ground.pos(), Blocks.AIR.defaultBlockState());
            h.set(ground.pos().below(), Blocks.CHEST.defaultBlockState());
            check(!RoutineTorchPlacement.stillUsable(h.player, ground, Set.of()), "inventory blocks are not torch supports");
            h.set(ground.pos().below(), Blocks.STONE.defaultBlockState());
            // 视线已经朝向近处岩壁时就地挂灯；不能为了墙面优先级强迫正在前进的角色回头。
            h.set(new BlockPos(6, 2, 4), Blocks.STONE.defaultBlockState());
            h.player.setYRot(-90); h.player.setXRot(0);
            var wall = RoutineTorchPlacement.find(h.player, Set.of());
            check(wall != null && wall.desiredState().is(Blocks.WALL_TORCH), "rock wall already in view is preferred");
            h.set(new BlockPos(5, 2, 4), Blocks.STONE.defaultBlockState());
            check(!RoutineTorchPlacement.stillUsable(h.player, wall, Set.of()), "new obstruction invalidates the old proposal");
            check(!RoutineTorchPlacement.usable(h.player, h.player.blockPosition(), Blocks.TORCH.defaultBlockState(),
                    h.player.blockPosition().below(), Direction.UP, Set.of()), "own body cell remains clear");
            check(!RoutineTorchPlacement.usable(h.player, new BlockPos(16, 1, 4), Blocks.TORCH.defaultBlockState(),
                    new BlockPos(16, 0, 4), Direction.UP, Set.of()), "unloaded terrain is not inspected or modified");
        }
        System.out.println("RoutineTorchPlacementTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
