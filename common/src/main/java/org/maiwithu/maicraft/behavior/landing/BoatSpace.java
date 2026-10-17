// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BucketWater;

/**
 * 船落地要的空间判断：船放在落点哪一点、放下去会不会卡住、准星看不看得到、坐上去身体有没有地方、
 * 周围哪一边下得了船、站得住。落点脚下是岩浆块、仙人掌、浆果丛、细雪、火、营火这类伤人的不放。
 */
final class BoatSpace {

    /** 船的碰撞箱半宽与高。 */
    private static final double HALF_WIDTH = 0.6875;
    static final double HEIGHT = 0.5625;
    /** 原版船的宽度。 */
    private static final double BOAT_WIDTH = 1.375;

    private BoatSpace() {}

    static AABB boatBox(Vec3 at) {
        return new AABB(at.x - HALF_WIDTH, at.y + 0.001, at.z - HALF_WIDTH, at.x + HALF_WIDTH, at.y + HEIGHT,
                at.z + HALF_WIDTH);
    }

    /** 船放在落点那一格的哪一点：落点下面一格碰撞面的中心；那一格没加载、没碰撞面或伤人时为 null。 */
    static Vec3 support(BlockGetter view, Predicate<BlockPos> loaded, BlockPos landing) {
        if (!loaded.test(landing.below())) return null;
        double height = AirSteering.supportHeight(view, landing.below());
        if (!Double.isFinite(height)) return null;
        Vec3 spawn = new Vec3(landing.getX() + 0.5, landing.getY() - 1 + height, landing.getZ() + 0.5);
        return safeFloor(view, landing.below(), height) ? spawn : null;
    }

    /** 船此刻真的落在一处支撑面上（新生成的船可能偏离格子中心，按它实际的位置查）。 */
    static boolean supportedAt(BlockGetter view, Predicate<BlockPos> loaded, Vec3 at) {
        AABB contact = new AABB(at.x - .6874, at.y - .02, at.z - .6874, at.x + .6874, at.y + .001, at.z + .6874);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(contact.minX - 1, at.y - 2, contact.minZ - 1),
                BlockPos.containing(contact.maxX + 1, at.y, contact.maxZ + 1))) {
            if (!loaded.test(cell)) return false;
            var state = view.getBlockState(cell);
            for (AABB local : state.getCollisionShape(view, cell, CollisionContext.empty()).toAabbs()) {
                AABB box = local.move(cell);
                if (Math.abs(box.maxY - at.y) < .02 && box.intersects(contact) && safeFloor(view, cell, local.maxY)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 支撑面本身不伤人；含水方块的水面低于碰撞面时只看方块本身。
    private static boolean safeFloor(BlockGetter view, BlockPos pos, double height) {
        var state = view.getBlockState(pos);
        if (state.getFluidState().getType() instanceof WaterFluid
                && height > state.getFluidState().getHeight(view, pos) + .0001) {
            state = BucketWater.dryGeometry(state);
        }
        return !hazard(state);
    }

    /** 从眼睛看过去，准星正落在放船那一点所在的顶面上，船放下去也不卡。 */
    static boolean placeable(BlockGetter view, Predicate<BlockPos> loaded, Vec3 eye, Vec3 spawn) {
        if (!clear(view, loaded, boatBox(spawn))) return false;
        Vec3 end = spawn.add(spawn.subtract(eye).normalize().scale(0.01));
        if (!loadedRay(loaded, eye, end)) return false;
        var hit = view.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP
                && hit.getLocation().distanceToSqr(spawn) < 0.001;
    }

    /** 两点之间没有方块挡着、经过的格子都加载了。 */
    static boolean visible(BlockGetter view, Predicate<BlockPos> loaded, Vec3 eye, Vec3 target) {
        return loadedRay(loaded, eye, target) && view.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    /** 这一块空间没有碰撞面、没有伤人的方块，周围都加载了。 */
    static boolean clear(BlockGetter view, Predicate<BlockPos> loaded, AABB body) {
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(body.minX - 1, body.minY - 1, body.minZ - 1),
                BlockPos.containing(body.maxX + 1, body.maxY + 1, body.maxZ + 1))) {
            if (!loaded.test(cell)) return false;
            var state = view.getBlockState(cell);
            if (new AABB(cell).intersects(body) && hazard(state)) return false;
            for (AABB box : state.getCollisionShape(view, cell, CollisionContext.empty()).toAabbs()) {
                if (box.move(cell).intersects(body)) return false;
            }
        }
        return true;
    }

    static boolean hazard(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.NETHER_PORTAL)
                || state.getBlock() instanceof BaseFireBlock || state.getBlock() instanceof CampfireBlock;
    }

    /** 坐在船里、身体朝四个方向任一个能下得了船。 */
    static boolean hasExit(BlockGetter view, Predicate<BlockPos> loaded, Vec3 spawn, double width, double height) {
        for (int yaw = 0; yaw < 360; yaw += 90) {
            if (exit(view, loaded, spawn, BOAT_WIDTH, width, height, yaw) != null) return true;
        }
        return false;
    }

    /** 已有的船：按船头朝向左右九十度内找下船的地方（原版乘客转向被限在船头左右一百零五度）。 */
    static boolean hasExit(BlockGetter view, Predicate<BlockPos> loaded, Vec3 spawn, double width, double height,
                           float boatYaw) {
        for (int offset = -90; offset <= 90; offset += 30) {
            if (exit(view, loaded, spawn, BOAT_WIDTH, width, height, boatYaw + offset) != null) return true;
        }
        return false;
    }

    /** 朝这个方向下船会站到哪一点：要和船底几乎同高、有支撑、身体放得下；不行为 null。 */
    static Vec3 exit(BlockGetter view, Predicate<BlockPos> loaded, Vec3 spawn, double boatWidth, double width,
                     double height, float yaw) {
        double distance = (boatWidth * Mth.SQRT_OF_TWO + width + 1.0E-5F) / 2;
        float x = -Mth.sin(yaw * (float) (Math.PI / 180));
        float z = Mth.cos(yaw * (float) (Math.PI / 180));
        float scale = Math.max(Math.abs(x), Math.abs(z));
        Vec3 at = spawn.add(x * distance / scale, 0, z * distance / scale);
        BlockPos floor = BlockPos.containing(at.x, at.y - 0.01, at.z);
        if (!loaded.test(floor)) return null;
        double top = AirSteering.supportHeight(view, floor);
        if (!Double.isFinite(top) || !safeFloor(view, floor, top) || Math.abs(floor.getY() + top - at.y) > 0.01) {
            return null;
        }
        return clear(view, loaded, new AABB(at.x - width / 2, at.y + 0.001, at.z - width / 2,
                at.x + width / 2, at.y + height, at.z + width / 2)) ? at : null;
    }

    /** 准星此刻正对着这条船、够得着、中间没方块挡着。 */
    static boolean aimedAt(PlayerContext context, Boat boat) {
        LocalPlayer player = context.localPlayer();
        Vec3 eye = player.getEyePosition();
        Vec3 reach = player.getViewVector(1).scale(player.entityInteractionRange());
        var hit = ProjectileUtil.getEntityHitResult(player, eye, eye.add(reach),
                player.getBoundingBox().expandTowards(reach).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable(), reach.lengthSqr());
        return player.canInteractWithEntity(boat, 0) && hit != null && hit.getEntity().getUUID().equals(boat.getUUID())
                && BoatSpace.visible(context.level(), context.level()::isLoaded, eye, hit.getLocation());
    }

    // 视线经过的格子都要加载了：没加载的不能当看得见。
    private static boolean loadedRay(Predicate<BlockPos> loaded, Vec3 from, Vec3 to) {
        int count = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 4));
        if (count > 512) return false;
        for (int i = 0; i <= count; i++) {
            if (!loaded.test(BlockPos.containing(from.lerp(to, (double) i / count)))) return false;
        }
        return true;
    }
}
