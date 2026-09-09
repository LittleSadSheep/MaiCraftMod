package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/**
 * 可选地回收本次创建的空船：先检查站稳、背包容量和周围安全地面，再攻击船，观察掉落物与库存增加。
 * 条件不满足、掉落物不唯一或超时就把未回收结果报告出来，不把“船消失了”直接当作物品已拿回。
 */
final class BoatLandingRecovery {
    private final UUID boatId;
    private final Item item;
    private final Set<UUID> beforeItems = new HashSet<>();
    private NativeActionReceipt receipt;
    private long started = -1;
    private int expectedItems;
    private boolean done, recovered, boatLeft = true;
    private String detail = "checking owned boat recovery";
    private Vec3 aim;
    private BodyControlPort.Movement movement = BodyControlPort.Movement.STOPPED;

    BoatLandingRecovery(UUID boatId, Item item) { this.boatId = boatId; this.item = item; }
    boolean tick(LocalPlayerContext ctx) {
        ctx.requireCurrent(); movement = BodyControlPort.Movement.STOPPED;
        if (done) return true;
        Boat boat = boat(ctx);
        if (started < 0) {
            if (boat == null || !eligible(ctx, boat)) return finish("boat left intact: safe inventory/pickup conditions unavailable", false, boat != null);
            started = ctx.tickRevision(); expectedItems = count(ctx, item) + 1;
            for (ItemEntity drop : ctx.level().getEntitiesOfClass(ItemEntity.class, boat.getBoundingBox().inflate(3))) beforeItems.add(drop.getUUID());
        }
        if (receipt != null) {
            ctx.actions().poll(ctx, receipt);
            if (!receipt.terminal()) return false;
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) return finish("boat recovery action was not confirmed", false, boat != null);
            receipt = null;
        }
        if (recoveredIntoInventory(boat != null, count(ctx, item), expectedItems)) return finish("created boat recovered into inventory", true, false);
        if (ctx.tickRevision() - started > 100) return finish(boat == null
                ? "boat removed; item recovery remains unconfirmed" : "boat left intact: recovery deadline reached", false, boat != null);
        if (!ctx.player().onGround() || ctx.player().isPassenger()) return finish("recovery stopped because safe ground contact changed", false, boat != null);
        if (boat != null) {
            if (!eligible(ctx, boat)) return finish("boat left intact: recovery conditions changed", false, true);
            aim = boat.getBoundingBox().getCenter();
            if (!ctx.player().canInteractWithEntity(boat, 0)
                    || !(ctx.minecraft().hitResult instanceof EntityHitResult hit) || !hit.getEntity().getUUID().equals(boatId)
                    || !BoatLandingGeometry.visible(ctx.level(), ctx.level()::isLoaded, ctx.player().getEyePosition(), aim)
                    || !ctx.mutationAvailable() || ctx.player().getAttackStrengthScale(0) < 0.9F) return false;
            float damage = boat.getDamage();
            receipt = ctx.actions().attack(ctx, boat, live -> {
                Boat target = boat(live);
                return target == null || target.getDamage() > damage ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
            }, 20);
            return false;
        }
        boatLeft = false;
        ItemEntity candidate = null;
        for (ItemEntity drop : ctx.level().getEntitiesOfClass(ItemEntity.class, ctx.player().getBoundingBox().inflate(3))) {
            if (beforeItems.contains(drop.getUUID()) || !drop.getItem().is(item)) continue;
            if (candidate != null) return finish("boat removed; ambiguous matching drops left in world", false, false);
            candidate = drop;
        }
        if (candidate == null) return false;
        Vec3 destination = new Vec3(candidate.getX(), ctx.player().getY(), candidate.getZ());
        if (!walkable(ctx, destination)) return finish("boat removed; drop cannot be approached on verified dry ground", false, false);
        aim = candidate.position();
        Vec3 delta = destination.subtract(ctx.player().position());
        if (delta.horizontalDistance() > 0.35) {
            double bearing = Math.toDegrees(Math.atan2(delta.z, delta.x))-90;
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees((float)bearing-ctx.player().getYRot())) < 25) {
                movement = new BodyControlPort.Movement(0.35F, 0, false, false, false);
            }
        }
        return false;
    }
    private boolean eligible(LocalPlayerContext ctx, Boat boat) {
        // Creative destroys vehicles without their ordinary item drop; leave the boat intact.
        if (ctx.player().isCreative()) return false;
        if (!boatId.equals(boat.getUUID()) || !boat.getPassengers().isEmpty() || !BoatLandingSnapshot.stationary(boat)
                || !ctx.player().onGround() || ctx.player().isPassenger() || ctx.player().distanceToSqr(boat) > 4
                || !room(ctx, new ItemStack(item))) return false;
        // The vanilla item drop can scatter before its pickup delay expires; prove the nearby
        // pickup area is dry and walkable before dismantling anything.
        // 拆船前当前要求周围九个位置都能从玩家处安全走到，用来降低掉落物落在无法回收位置的风险。
        for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++) {
            if (!walkable(ctx, new Vec3(boat.getX()+x, boat.getY(), boat.getZ()+z))) return false;
        }
        return true;
    }
    private static boolean walkable(LocalPlayerContext ctx, Vec3 to) {
        Vec3 from = ctx.player().position();
        int steps = Math.max(1,(int)Math.ceil(from.distanceTo(to)*5));
        if (steps > 30) return false;
        for (int i=0;i<=steps;i++) {
            Vec3 at = from.lerp(to,(double)i/steps);
            BlockPos feet = BlockPos.containing(at.x, Math.ceil(at.y), at.z);
            Vec3 support = BoatLandingGeometry.support(ctx.level(), ctx.level()::isLoaded, feet);
            if (support == null || Math.abs(support.y-at.y) > 0.1) return false;
            double half = ctx.player().getBbWidth()/2;
            if (!BoatLandingGeometry.clear(ctx.level(), ctx.level()::isLoaded,
                    new AABB(at.x-half,at.y+0.001,at.z-half,at.x+half,at.y+ctx.player().getBbHeight(),at.z+half))) return false;
        }
        return true;
    }
    static boolean capacity(ItemStack slot, ItemStack drop) {
        return slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, drop) && slot.getCount()+drop.getCount() <= slot.getMaxStackSize();
    }
    // 船确实不在了，并且对应物品数量至少增加一件，才把回收记为完成。
    static boolean recoveredIntoInventory(boolean boatPresent, int currentItems, int expectedItems) {
        return !boatPresent && expectedItems > 0 && currentItems >= expectedItems;
    }
    void cancel(LocalPlayerContext ctx) {
        if (receipt != null) {
            ctx.actions().poll(ctx, receipt);
            if (!receipt.terminal()) ctx.actions().retireOneShotForTaskBoundary(ctx, receipt, "optional boat recovery cancelled");
            receipt = null;
        }
        boolean present = boat(ctx) != null;
        finish("optional boat recovery cancelled", recoveredIntoInventory(present,count(ctx,item),expectedItems), present);
    }
    private static boolean room(LocalPlayerContext ctx, ItemStack drop) {
        for (int i=0;i<36;i++) if (capacity(ctx.player().getInventory().getItem(i), drop)) return true;
        return false;
    }
    private static int count(LocalPlayerContext ctx, Item item) {
        int result = 0;
        for (int i=0;i<ctx.player().getInventory().getContainerSize();i++) if (ctx.player().getInventory().getItem(i).is(item)) result += ctx.player().getInventory().getItem(i).getCount();
        return result;
    }
    private Boat boat(LocalPlayerContext ctx) {
        for (Entity entity : ctx.level().entitiesForRendering()) if (boatId.equals(entity.getUUID()) && entity instanceof Boat boat) return boat;
        return null;
    }
    private boolean finish(String reason, boolean recovered, boolean left) { detail=reason; this.recovered=recovered; boatLeft=left; done=true; return true; }
    String detail() { return detail; }
    boolean recovered() { return recovered; }
    boolean boatLeft() { return boatLeft; }
    Vec3 aim() { return aim; }
    BodyControlPort.Movement movement() { return movement; }
}
