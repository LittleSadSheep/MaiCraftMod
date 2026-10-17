// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 船落地这时候能怎么用：身上带着哪种普通船、附近有哪些停着的空船、够得着方块与实体的距离、身体多大。
 * 算路时在出发前取一份冻结下来（最多记三十二条船），搜索线程上只用它判断。
 *
 * @param source 取样时脚下那一格：从这里出发的一跳才能按此刻的眼睛位置判断够不够得着
 * @param item   身上带着的普通船；没带为 null
 */
public record BoatOptions(BlockPos source, Vec3 eye, double blockReach, double entityReach, double width,
                          double height, Item item, List<Seen> boats) {

    /** 附近一条停着、没人坐的空船。 */
    public record Seen(UUID id, Vec3 position, AABB box, float yaw) {}

    /** 最多记这么多条附近的船。 */
    private static final int MAX_BOATS = 32;

    public BoatOptions {
        source = source.immutable();
        boats = List.copyOf(boats);
    }

    /** 只在客户端线程取：身上第一种普通船、附近三十二格内停稳的空船。 */
    public static BoatOptions capture(LocalPlayer player) {
        List<Seen> boats = new ArrayList<>();
        for (Boat boat : player.level().getEntitiesOfClass(Boat.class, player.getBoundingBox().inflate(32))) {
            if (boats.size() >= MAX_BOATS) break;
            if (plain(boat) && stationary(boat) && boat.getPassengers().isEmpty()) {
                boats.add(new Seen(boat.getUUID(), boat.position(), boat.getBoundingBox(), boat.getYRot()));
            }
        }
        return new BoatOptions(player.blockPosition(), player.getEyePosition(), player.blockInteractionRange(),
                player.entityInteractionRange(), player.getBbWidth(), player.getBbHeight(), carried(player), boats);
    }

    /** 身上（背包与副手）带着的第一种普通船。 */
    public static Item carried(LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            Item candidate = player.getInventory().getItem(slot).getItem();
            if (plainBoat(candidate)) return candidate;
        }
        return null;
    }

    /**
     * 站在出发点就能办成：落点那条现成的空船伸手够得着、看得见、下得了船，就坐它；
     * 否则身上的船够得着放到落点、放下去不卡、下得了船，就放下再坐进去——人直接坐进下面的船里，不用掉下去。
     */
    public BoatPlan fromEdge(BlockGetter view, BlockPos from, BlockPos landing,
                             Predicate<BlockPos> loaded) {
        if (!source.equals(from) || item == null && boats.isEmpty()) return null;
        Vec3 spawn = BoatSpace.support(view, loaded, landing);
        if (spawn == null || !BoatSpace.hasExit(view, loaded, spawn, width, height)
                || !BoatSpace.clear(view, loaded, seatedSpace(spawn))) return null;
        for (Seen boat : boats) {
            if (boat.position().distanceToSqr(spawn) > 0.25 || boat.box().distanceToSqr(eye) >= entityReach * entityReach) {
                continue;
            }
            if (BoatSpace.visible(view, loaded, eye, boat.box().getCenter())
                    && BoatSpace.hasExit(view, loaded, boat.position(), width, height, boat.yaw())) {
                return new BoatPlan(landing, boat.position(), null, boat.id(), false);
            }
        }
        if (item == null || eye.distanceTo(spawn) > blockReach
                || BoatSpace.boatBox(spawn).distanceToSqr(eye) >= entityReach * entityReach) return null;
        float yaw = (float) (Math.toDegrees(Math.atan2(spawn.z - eye.z, spawn.x - eye.x)) - 90);
        if (!BoatSpace.placeable(view, loaded, eye, spawn) || !BoatSpace.hasExit(view, loaded, spawn, width, height, yaw)) {
            return null;
        }
        return new BoatPlan(landing, spawn, item, null, false);
    }

    /**
     * 已经在掉时用船接：落点那里停着一条现成的空船、下得了船就坐它；否则身上带着船、落点放得下、坐得下、下得了船。
     */
    public BoatPlan catchFalling(BlockGetter view, BlockPos landing, Predicate<BlockPos> loaded) {
        Vec3 spawn = BoatSpace.support(view, loaded, landing);
        if (spawn == null || !BoatSpace.hasExit(view, loaded, spawn, width, height)) return null;
        for (Seen boat : boats) {
            if (boat.position().distanceToSqr(spawn) < 0.25) {
                return new BoatPlan(landing, boat.position(), null, boat.id(), true);
            }
        }
        return midair(view, landing, loaded);
    }

    /** 边掉边放：身上带着船、落点放得下船、坐上去身体放得下、下得了船。 */
    public BoatPlan midair(BlockGetter view, BlockPos landing, Predicate<BlockPos> loaded) {
        if (item == null) return null;
        Vec3 spawn = BoatSpace.support(view, loaded, landing);
        if (spawn == null || !BoatSpace.clear(view, loaded, BoatSpace.boatBox(spawn))
                || !BoatSpace.clear(view, loaded, seatedSpace(spawn))
                || !BoatSpace.hasExit(view, loaded, spawn, width, height)) return null;
        return new BoatPlan(landing, spawn, item, null, true);
    }

    /**
     * 边掉边放来不来得及：够得着时放船，下一刻起离船顶还在实体够得着的距离里时坐上去，再留一刻等确认，
     * 这些都要在撞上船之前。掉得太快就不收。
     */
    public boolean airborneWindow(double drop, double gravity, double downwardSpeed) {
        if (!Double.isFinite(drop) || !Double.isFinite(gravity) || !Double.isFinite(downwardSpeed)
                || drop <= 0 || gravity <= 0 || downwardSpeed < 0) return false;
        double y = drop;
        double speed = downwardSpeed;
        double eyeHeight = Math.max(1.62, eye.y - source.getY());
        int placed = -1;
        int mounted = -1;
        for (int tick = 0; tick < 200; tick++) {
            if (y <= BoatSpace.HEIGHT) return false;
            if (mounted >= 0 && tick > mounted) return true;
            if (placed >= 0 && tick > placed && y + eyeHeight - BoatSpace.HEIGHT <= entityReach) mounted = tick;
            if (placed < 0 && y + eyeHeight <= blockReach) placed = tick;
            y -= speed;
            speed = (speed + gravity) * .98;
        }
        return false;
    }

    // 坐在船里时身体所在的那一块。
    private AABB seatedSpace(Vec3 spawn) {
        return new AABB(spawn.x - width / 2, spawn.y + .563, spawn.z - width / 2,
                spawn.x + width / 2, spawn.y + .563 + height, spawn.z + width / 2);
    }

    /** 停稳了：活着、在地上、没在掉、几乎不动、不在水里。 */
    static boolean stationary(Boat boat) {
        return boat.isAlive() && boat.onGround() && boat.fallDistance <= 0.01F
                && boat.getDeltaMovement().lengthSqr() < 0.0004 && !boat.isInWater();
    }

    /** 普通船（不是运输船、不是模组的船）。 */
    static boolean plain(Boat boat) {
        return boat.getType() == EntityType.BOAT && boat.getClass() == Boat.class;
    }

    public static boolean plainBoat(Item item) {
        return item == Items.OAK_BOAT || item == Items.SPRUCE_BOAT || item == Items.BIRCH_BOAT
                || item == Items.JUNGLE_BOAT || item == Items.ACACIA_BOAT || item == Items.DARK_OAK_BOAT
                || item == Items.MANGROVE_BOAT || item == Items.CHERRY_BOAT || item == Items.BAMBOO_RAFT;
    }
}
