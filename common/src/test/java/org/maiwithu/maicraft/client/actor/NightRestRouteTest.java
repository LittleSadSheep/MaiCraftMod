// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.sleep.NightRestRouteProbe;

/** 休息的几何证明需要真正可逆的现有通道；单向高柱下降不能被“床在附近”替代。 */
public final class NightRestRouteTest {
    private static final BlockPos BED = new BlockPos(10, 1, 8), DOOR = new BlockPos(6, 1, 8);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        pillarNeedsReturnSteps(); nativeDoorsKeepTheirProtection();
        System.out.println("NightRestRouteTest: passed");
    }
    private static void pillarNeedsReturnSteps() throws Exception {
        try (var h = fixture()) {
            for (int y = 1; y <= 3; y++) h.set(new BlockPos(3, y, 8), Blocks.COBBLESTONE.defaultBlockState());
            h.position(new Vec3(3.5, 4, 8.5));
            var oneWay = probe(h); finish(oneWay);
            check(!oneWay.connected(), "a three-block drop from an isolated construction pillar has no existing walking return");
            // 真实世界已出现一格一格的阶梯后，同一出发点才有可逆路线；证明过程自身不生成这些台阶。
            h.set(new BlockPos(4, 2, 8), Blocks.COBBLESTONE.defaultBlockState());
            h.set(new BlockPos(5, 1, 8), Blocks.COBBLESTONE.defaultBlockState());
            var stairs = probe(h); finish(stairs);
            check(stairs.connected(), "existing one-block steps provide both outward and return access");
            check(h.player.position().equals(new Vec3(3.5, 4, 8.5)) && h.blockUses() == 0 && h.itemUses() == 0,
                    "route proof neither moves the player nor uses blocks");
        }
    }
    private static void nativeDoorsKeepTheirProtection() throws Exception {
        try (var h = fixture()) {
            h.position(new Vec3(3.5, 1, 8.5));
            for (int z = 0; z < 16; z++) for (int y = 1; y <= 4; y++) h.set(new BlockPos(6, y, z), Blocks.STONE.defaultBlockState());
            var oak = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            h.set(DOOR, oak); h.set(DOOR.above(), oak.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            var openable = probe(h); finish(openable);
            check(openable.connected() && !h.level.getBlockState(DOOR).getValue(DoorBlock.OPEN) && h.blockUses() == 0,
                    "native hand-openable doors are included in a read-only round trip without opening them in the world");
            boolean protectedRoute = NavigationSafetyContext.withProtectedArea(List.of(DOOR), List.of(), () -> {
                var blocked = probe(h); finish(blocked); return blocked.connected();
            });
            check(!protectedRoute, "a protected lower door half also blocks the projected upper-half opening");
            var iron = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            h.set(DOOR, iron); h.set(DOOR.above(), iron.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            var poweredDoor = probe(h); finish(poweredDoor);
            check(!poweredDoor.connected(), "closed doors that cannot be opened by hand are not an assumed return route");
        }
    }
    private static InteractionWorldTestHarness fixture() throws Exception {
        var h = new InteractionWorldTestHarness();
        ActorControlTestHarness.field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        h.player.setDeltaMovement(Vec3.ZERO);
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        h.set(BED, bed.setValue(BedBlock.PART, BedPart.HEAD)); h.set(BED.west(), bed.setValue(BedBlock.PART, BedPart.FOOT));
        return h;
    }
    private static NightRestRouteProbe probe(InteractionWorldTestHarness h) { return new NightRestRouteProbe(h.player, BED, PhysicalObstacleSnapshot.EMPTY); }
    private static void finish(NightRestRouteProbe probe) {
        for (int i = 0; i < 4096; i++) if (probe.advance()) return;
        throw new AssertionError("the bounded round-trip probe did not finish");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
