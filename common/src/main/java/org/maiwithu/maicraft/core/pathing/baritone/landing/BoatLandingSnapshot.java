package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/**
 * 在规划时保存玩家的交互距离、体型、随身普通船和附近静止空船，供落地上船方案检查；目前最多记录三十二条船。
 */
public record BoatLandingSnapshot(BlockPos source, Vec3 eye, double blockReach, double entityReach,
                                  double width, double height, Item boatItem, List<BoatFact> boats) {
    public record BoatFact(UUID uuid, Vec3 position, AABB box, float yaw) {}
    public record Plan(BlockPos source, BlockPos landing, Item item, UUID existingBoat,
                       Vec3 spawn, boolean airborne) {
        public Plan { source = source.immutable(); landing = landing.immutable(); }
    }
    public BoatLandingSnapshot { source = source.immutable(); boats = List.copyOf(boats); }
    public static BoatLandingSnapshot empty() { return new BoatLandingSnapshot(BlockPos.ZERO, Vec3.ZERO, 0, 0, 0.6, 1.8, null, List.of()); }
    /** Planned departure needs time for native spawn and mount feedback; an ongoing fall still tries its best. */
    // 按下落过程估算能否先放船、下一次更新上船、再留一次更新确认；每一步都要在撞上船体前留得出时间。
    public boolean airborneWindow(double drop, double gravity, double downwardSpeed) {
        if (!Double.isFinite(drop) || !Double.isFinite(gravity) || !Double.isFinite(downwardSpeed)
                || drop<=0 || gravity<=0 || downwardSpeed<0) return false;
        double y=drop, speed=downwardSpeed, eyeHeight=Math.max(1.62,eye.y-source.getY());
        int placed=-1,mounted=-1;
        for(int tick=0;tick<200;tick++) {
            if(y<=.5625) return false;
            if(mounted>=0 && tick>mounted) return true;
            if(placed>=0 && tick>placed && y+eyeHeight-.5625<=entityReach) mounted=tick;
            if(placed<0 && y+eyeHeight<=blockReach) placed=tick;
            y-=speed; speed=(speed+gravity)*.98;
        }
        return false;
    }
    public static BoatLandingSnapshot capture(LocalPlayer player) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("boat facts require client thread");
        List<BoatFact> boats = new ArrayList<>();
        for (Boat boat : player.level().getEntitiesOfClass(Boat.class, player.getBoundingBox().inflate(32))) {
            if (boats.size() >= 32) break;
            if (boat.getType() == EntityType.BOAT && boat.getClass() == Boat.class && stationary(boat)
                    && boat.getPassengers().isEmpty()) boats.add(new BoatFact(boat.getUUID(), boat.position(), boat.getBoundingBox(), boat.getYRot()));
        }
        Item item = null;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            Item candidate = player.getInventory().getItem(i).getItem();
            if (plainBoat(candidate)) { item = candidate; break; }
        }
        return new BoatLandingSnapshot(PlayerNav.playerFeet(player), player.getEyePosition(),
                player.blockInteractionRange(), player.entityInteractionRange(), player.getBbWidth(), player.getBbHeight(), item, boats);
    }
    // 先尝试目标旁已有的可交互空船，再考虑手里的船；还必须预先找到安全下船空间。
    public Plan plan(BlockGetter view, BlockPos from, BlockPos landing, Predicate<BlockPos> loaded) {
        if (!source.equals(from) || boatItem == null && boats.isEmpty()) return null;
        Vec3 spawn = BoatLandingGeometry.support(view, loaded, landing);
        if (spawn == null || !BoatLandingGeometry.hasExit(view, loaded, spawn, width, height)
                || !BoatLandingGeometry.clear(view, loaded, new AABB(spawn.x-width/2, spawn.y+0.563, spawn.z-width/2,
                spawn.x+width/2, spawn.y+0.563+height, spawn.z+width/2))) return null;
        for (BoatFact boat : boats) {
            if (boat.position().distanceToSqr(spawn) > 0.25 || boat.box().distanceToSqr(eye) >= entityReach * entityReach) continue;
            if (BoatLandingGeometry.visible(view, loaded, eye, boat.box().getCenter())
                    && BoatLandingGeometry.hasExit(view, loaded, boat.position(), width, height, boat.yaw())) {
                return new Plan(from, landing, null, boat.uuid(), boat.position(), false);
            }
        }
        if (boatItem == null || eye.distanceTo(spawn) > blockReach
                || BoatLandingGeometry.boatBox(spawn).distanceToSqr(eye) >= entityReach * entityReach) return null;
        float yaw = (float)(Math.toDegrees(Math.atan2(spawn.z-eye.z,spawn.x-eye.x))-90);
        if (!BoatLandingGeometry.placeable(view, loaded, eye, spawn)
                || !BoatLandingGeometry.hasExit(view, loaded, spawn, width, height, yaw)) return null;
        return new Plan(from, landing, boatItem, null, spawn, false);
    }
    static boolean stationary(Boat boat) {
        return boat.isAlive() && boat.onGround() && boat.fallDistance <= 0.01F
                && boat.getDeltaMovement().lengthSqr() < 0.0004 && !boat.isInWater();
    }
    public static boolean plainBoat(Item item) {
        return item == Items.OAK_BOAT || item == Items.SPRUCE_BOAT || item == Items.BIRCH_BOAT
                || item == Items.JUNGLE_BOAT || item == Items.ACACIA_BOAT || item == Items.DARK_OAK_BOAT
                || item == Items.MANGROVE_BOAT || item == Items.CHERRY_BOAT || item == Items.BAMBOO_RAFT;
    }
}
