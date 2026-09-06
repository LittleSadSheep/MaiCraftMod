package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.pathing.movement.CollisionGeometry;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Geometry mirrors BoatItem's actual POV hit and a vanilla boat's 1.375 x 0.5625 dimensions. */
final class BoatLandingGeometry {
    static AABB boatBox(Vec3 at) { return new AABB(at.x-0.6875, at.y+0.001, at.z-0.6875, at.x+0.6875, at.y+0.5625, at.z+0.6875); }
    static Vec3 support(BlockGetter view, Predicate<BlockPos> loaded, BlockPos landing) {
        if (!loaded.test(landing.below())) return null;
        double height = CollisionGeometry.supportHeight(view, landing.below());
        if (!Double.isFinite(height)) return null;
        Vec3 spawn = new Vec3(landing.getX()+0.5, landing.getY()-1+height, landing.getZ()+0.5);
        return hazard(view.getBlockState(landing.below())) ? null : spawn;
    }
    static boolean placeable(BlockGetter view, Predicate<BlockPos> loaded, Vec3 eye, Vec3 spawn) {
        if (!clear(view, loaded, boatBox(spawn))) return false;
        Vec3 end = spawn.add(spawn.subtract(eye).normalize().scale(0.01));
        if (!loadedRay(loaded, eye, end)) return false;
        var hit = view.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP && hit.getLocation().distanceToSqr(spawn) < 0.001;
    }
    static boolean visible(BlockGetter view, Predicate<BlockPos> loaded, Vec3 eye, Vec3 target) {
        return loadedRay(loaded, eye, target) && view.clip(new ClipContext(eye, target,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }
    static boolean clear(BlockGetter view, Predicate<BlockPos> loaded, AABB body) {
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(body.minX-1, body.minY-1, body.minZ-1),
                BlockPos.containing(body.maxX+1, body.maxY+1, body.maxZ+1))) {
            if (!loaded.test(cell)) return false;
            var state = view.getBlockState(cell);
            if (new AABB(cell).intersects(body) && hazard(state)) return false;
            for (AABB box : state.getCollisionShape(view, cell, CollisionContext.empty()).toAabbs()) {
                if (box.move(cell).intersects(body)) return false;
            }
        }
        return true;
    }
    static boolean hazard(net.minecraft.world.level.block.state.BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.NETHER_PORTAL)
                || state.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock;
    }
    static Vec3 dismountOffset(double boatWidth, double riderWidth, float yaw) {
        double distance = (boatWidth * Mth.SQRT_OF_TWO + riderWidth + 1.0E-5F) / 2;
        float x = -Mth.sin(yaw * (float)(Math.PI/180)), z = Mth.cos(yaw * (float)(Math.PI/180));
        float scale = Math.max(Math.abs(x), Math.abs(z));
        return new Vec3(x * distance / scale, 0, z * distance / scale);
    }
    static boolean hasExit(BlockGetter view, Predicate<BlockPos> loaded, Vec3 spawn, double width, double height) {
        for (int yaw=0;yaw<360;yaw+=90) if (exit(view, loaded, spawn, 1.375, width, height, yaw) != null) return true;
        return false;
    }
    static boolean hasExit(BlockGetter view, Predicate<BlockPos> loaded, Vec3 spawn, double width, double height, float boatYaw) {
        for (int offset=-90;offset<=90;offset+=30) if (exit(view, loaded, spawn, 1.375, width, height, boatYaw+offset) != null) return true;
        return false;
    }
    static Vec3 exit(BlockGetter view, Predicate<BlockPos> loaded, Vec3 spawn, double boatWidth,
                     double width, double height, float yaw) {
        Vec3 at = spawn.add(dismountOffset(boatWidth, width, yaw));
        BlockPos floor = BlockPos.containing(at.x, at.y-0.01, at.z);
        if (!loaded.test(floor) || hazard(view.getBlockState(floor))) return null;
        double top = CollisionGeometry.supportHeight(view, floor);
        if (!Double.isFinite(top) || Math.abs(floor.getY()+top-at.y) > 0.01) return null;
        return clear(view, loaded, new AABB(at.x-width/2, at.y+0.001, at.z-width/2,
                at.x+width/2, at.y+height, at.z+width/2)) ? at : null;
    }
    /** Conservative discrete native gravity estimate; unknown latency/physics never creates a window. */
    static boolean timeForActions(double feetAboveBoat, double velocityY, double gravity, int pingMillis, int actions) {
        if (!Double.isFinite(feetAboveBoat) || !Double.isFinite(velocityY) || !Double.isFinite(gravity)
                || gravity <= 0 || velocityY > 0 || pingMillis < 0 || actions < 1) return false;
        int required = actions * (2 + (int)Math.ceil(pingMillis/50.0)) + 3;
        double height = feetAboveBoat, speed = velocityY;
        for (int tick=0; tick<=required; tick++) {
            height += speed;
            if (height <= 0.1) return false;
            speed = (speed-gravity)*0.98;
        }
        return true;
    }
    private static boolean loadedRay(Predicate<BlockPos> loaded, Vec3 from, Vec3 to) {
        int count = Math.max(1, (int)Math.ceil(from.distanceTo(to)*4));
        if (count > 512) return false;
        for (int i=0;i<=count;i++) if (!loaded.test(BlockPos.containing(from.lerp(to,(double)i/count)))) return false;
        return true;
    }
}
