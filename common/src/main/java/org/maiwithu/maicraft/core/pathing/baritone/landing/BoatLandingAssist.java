package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** Native stationary-boat landing. A falling boat is never treated as an immunity device:
 * Boat.checkFallDamage can propagate damage to riders through Entity.causeFallDamage.
 * New entities require inventory consumption plus a unique observed new UUID. Boarding alone
 * does not complete the fall: wait for rideTick reset, stable support and native Shift dismount.
 */
public final class BoatLandingAssist {
    public enum State { RUNNING, SETTLED, FAILED }
    private enum Phase { ITEM, PLACE, MOUNT, RIDING, DISMOUNT, RECOVER, DONE }
    private BoatLandingSnapshot.Plan plan;
    private final LandingPreparation preparation;
    private final boolean recoverCreatedBoat;
    private BoatLandingRecovery recovery;
    private Phase phase;
    private UUID boatId;
    private NativeActionReceipt receipt;
    private boolean failed, effects, owned, ready, cancelled;
    private String detail = "preparing a stationary landing boat within native interaction reach";
    private Vec3 aim;
    private long epoch = -1, revision, lastTick = Long.MIN_VALUE, stableTick = Long.MIN_VALUE, boardedTick = Long.MIN_VALUE;
    private int stableTicks;
    private float entryHealth = Float.NaN;
    private float entryAbsorption = Float.NaN;
    private InteractionHand hand = InteractionHand.MAIN_HAND;
    private Set<UUID> beforeBoats = Set.of();
    private int beforeItems;
    private int placementSubmissions, mountSubmissions;

    public BoatLandingAssist(BoatLandingSnapshot.Plan plan) {
        this(plan, true);
    }
    public BoatLandingAssist(BoatLandingSnapshot.Plan plan, boolean recoverCreatedBoat) {
        this.plan = plan; boatId = plan.existingBoat(); aim = plan.spawn().add(0, 0.3, 0);
        this.recoverCreatedBoat = recoverCreatedBoat;
        preparation = plan.item() == null ? null : new LandingPreparation(plan.item());
        phase = boatId == null ? Phase.ITEM : Phase.MOUNT;
    }
    public static BoatLandingSnapshot capture(LocalPlayer player) { return BoatLandingSnapshot.capture(player); }
    /** Opportunistic only: caller must retain its nonfatal/other proven fallback for this airborne plan. */
    public static BoatLandingSnapshot.Plan airbornePlan(LocalPlayerContext ctx, BlockPos landing) {
        if (ctx.player().onGround() || ctx.player().isPassenger()) return null;
        var snapshot = capture(ctx.player());
        Vec3 spawn = BoatLandingGeometry.support(ctx.level(), ctx.level()::isLoaded, landing);
        if (spawn == null || !BoatLandingGeometry.hasExit(ctx.level(), ctx.level()::isLoaded, spawn,
                ctx.player().getBbWidth(), ctx.player().getBbHeight())) return null;
        for (var boat : snapshot.boats()) {
            if (boat.position().distanceToSqr(spawn) < 0.25) {
                return new BoatLandingSnapshot.Plan(snapshot.source(), landing, null, boat.uuid(), boat.position(), true);
            }
        }
        Item item = BoatLandingSnapshot.plainBoat(ctx.player().getMainHandItem().getItem()) ? ctx.player().getMainHandItem().getItem()
                : BoatLandingSnapshot.plainBoat(ctx.player().getOffhandItem().getItem()) ? ctx.player().getOffhandItem().getItem() : null;
        if (item == null || snapshot.eye().distanceTo(spawn) > snapshot.blockReach()
                || !BoatLandingGeometry.placeable(ctx.level(), ctx.level()::isLoaded, snapshot.eye(), spawn)) return null;
        return new BoatLandingSnapshot.Plan(snapshot.source(), landing, item, null, spawn, true);
    }
    public boolean prepare(LocalPlayerContext ctx) { tick(ctx); return ready && !failed; }
    public State tick(LocalPlayerContext ctx) {
        ctx.requireCurrent();
        if (failed) { drain(ctx); return State.FAILED; }
        if (phase == Phase.DONE) return State.SETTLED;
        if (lastTick == ctx.tickRevision()) return State.RUNNING;
        lastTick = ctx.tickRevision();
        if (epoch < 0) { epoch = ctx.bodyEpoch(); revision = ctx.controlRevision();
            entryHealth = ctx.player().getHealth(); entryAbsorption = ctx.player().getAbsorptionAmount(); }
        if (epoch != ctx.bodyEpoch() || revision != ctx.controlRevision() || !ctx.permitsNativeActions()) return fail("boat landing body authority changed");
        if (phase == Phase.RECOVER) {
            if (!recovery.tick(ctx)) { if (recovery.aim() != null) aim = recovery.aim(); look(ctx, aim); return State.RUNNING; }
            if (!ctx.player().onGround() || ctx.player().isPassenger() || !damageFree(entryHealth, entryAbsorption,
                    ctx.player().getHealth(), ctx.player().getAbsorptionAmount())) return fail("safe damage-free ground contact changed during boat recovery");
            phase = Phase.DONE; detail = recovery.detail(); return State.SETTLED;
        }
        if (receipt != null) {
            ctx.actions().poll(ctx, receipt);
            if (!receipt.terminal()) return State.RUNNING;
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) return fail(receipt.detail());
            receipt = null;
            if (phase == Phase.PLACE) { owned = true; phase = Phase.MOUNT; }
            else if (phase == Phase.MOUNT) { phase = Phase.RIDING; boardedTick = lastTick; }
        }
        if (cancelled && !ctx.player().isPassenger()) return fail("boat landing cancelled; no unrelated entity touched");
        if (phase == Phase.ITEM) {
            if (ctx.player().onGround()) {
                if (!preparation.tick(ctx)) {
                    preparation.continueCleanup(ctx);
                    return preparation.failed() && !preparation.cleanupPending() ? fail(preparation.diagnostic()) : State.RUNNING;
                }
                hand = preparation.hand();
            } else {
                if (!preparation.tickEmergency(ctx)) return preparation.failed() ? fail(preparation.diagnostic()) : State.RUNNING;
                hand=preparation.hand();
            }
            phase = Phase.PLACE;
        }
        if (phase == Phase.PLACE) return place(ctx);
        Boat boat = find(ctx);
        if (boat == null) return fail("observed landing boat disappeared");
        aim = boat.getBoundingBox().getCenter();
        if (phase == Phase.MOUNT) {
            if (!supported(ctx,boat) || !boat.getPassengers().isEmpty()) return fail("landing boat is moving, falling or occupied");
            if (!stable(ctx, boat)) return State.RUNNING;
            if (!ctx.player().onGround() && (boat.getBoundingBox().distanceToSqr(ctx.player().getEyePosition())
                    > ctx.player().entityInteractionRange()*ctx.player().entityInteractionRange()
                    || boat.getBoundingBox().clip(ctx.player().getEyePosition(),ctx.player().getEyePosition().add(0,-ctx.player().entityInteractionRange(),0)).isPresent()))
                down(ctx);
            else look(ctx, aim);
            if (!targeted(ctx, boat) || !ctx.mutationAvailable()) return State.RUNNING;
            if (ctx.player().isShiftKeyDown()) return State.RUNNING;
            effects = true;
            mountSubmissions++;
            receipt = ctx.actions().interact(ctx, boat, hand, NativeConfirmation.serverObservedEntity(live -> boatId.equals(vehicleId(live.player()))
                    ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING), 30);
            return State.RUNNING;
        }
        if (phase == Phase.RIDING) {
            if (!boatId.equals(vehicleId(ctx.player()))) return fail("boat mount confirmation diverged");
            if (!stable(ctx, boat) || lastTick <= boardedTick || ctx.player().fallDistance > 0.01F) return State.RUNNING;
            ready = true;
            Vec3 exit = BoatLandingGeometry.exit(ctx.level(), ctx.level()::isLoaded, boat.position(), boat.getBbWidth(),
                    ctx.player().getBbWidth(), ctx.player().getBbHeight(), ctx.player().getYRot());
            if (exit == null) {
                for (int offset=-90; offset<=90; offset+=30) {
                    float yaw = boat.getYRot()+offset; // native Boat.clampRotation limits a passenger to +/-105 degrees
                    if (BoatLandingGeometry.exit(ctx.level(), ctx.level()::isLoaded,
                        boat.position(), boat.getBbWidth(), ctx.player().getBbWidth(), ctx.player().getBbHeight(), yaw) != null) {
                    aim = ctx.player().getEyePosition().add(-Math.sin(Math.toRadians(yaw))*2, 0, Math.cos(Math.toRadians(yaw))*2);
                    look(ctx, aim); return State.RUNNING;
                    }
                }
                detail = "waiting for a safe native standing dismount point"; return State.RUNNING;
            }
            phase = Phase.DISMOUNT; detail = "boat stable, ride tick reset observed; awaiting native Shift dismount";
            if (!ctx.mutationAvailable()) { phase = Phase.RIDING; return State.RUNNING; }
            receipt = ctx.actions().submitControlProtocol(ctx, "boat landing native Shift dismount", () ->
                    ctx.body().applyMovement(new org.maiwithu.maicraft.client.actor.BodyControlPort.Movement(0,0,false,true,false),
                            ctx.tickRevision()), live -> !live.player().isPassenger()
                            ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING, 30);
            return State.RUNNING;
        }
        if (phase == Phase.DISMOUNT && !ctx.player().isPassenger()) {
            if (!ctx.player().onGround() || ctx.player().getDeltaMovement().lengthSqr() > 0.01) return State.RUNNING;
            var feet = PlayerNav.playerFeet(ctx.player());
            if (BoatLandingGeometry.support(ctx.level(), ctx.level()::isLoaded, feet) == null) return fail("dismount has no observed safe support");
            if (!damageFree(entryHealth, entryAbsorption, ctx.player().getHealth(), ctx.player().getAbsorptionAmount()))
                return fail("health or absorption decreased during boat landing; not a confirmed damage-free arrival");
            if (owned && recoverCreatedBoat && !cancelled) { recovery = new BoatLandingRecovery(boatId, plan.item()); phase = Phase.RECOVER; return State.RUNNING; }
            phase = Phase.DONE; detail = "native boat landing and grounded dismount confirmed; boat left intact";
            return State.SETTLED;
        }
        return State.RUNNING;
    }
    private State place(LocalPlayerContext ctx) {
        aim = plan.spawn();
        if(ctx.player().onGround()) look(ctx,aim); else down(ctx);
        if (ctx.player().getEyePosition().distanceTo(plan.spawn())>ctx.player().blockInteractionRange()) return State.RUNNING;
        if (!BoatLandingGeometry.placeable(ctx.level(), ctx.level()::isLoaded, ctx.player().getEyePosition(), plan.spawn())) return fail("boat POV placement corridor changed");
        var hit = ctx.level().clip(new net.minecraft.world.level.ClipContext(ctx.player().getEyePosition(),
                ctx.player().getEyePosition().add(ctx.player().getViewVector(1).scale(ctx.player().blockInteractionRange())),
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.ANY, ctx.player()));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getDirection()!=net.minecraft.core.Direction.UP
                || !hit.getBlockPos().equals(plan.landing().below()) || Math.abs(hit.getLocation().y-plan.spawn().y)>.01
                || hit.getLocation().x<plan.landing().getX() || hit.getLocation().x>plan.landing().getX()+1
                || hit.getLocation().z<plan.landing().getZ() || hit.getLocation().z>plan.landing().getZ()+1) return State.RUNNING;
        if (!BoatLandingGeometry.clear(ctx.level(),ctx.level()::isLoaded,BoatLandingGeometry.boatBox(hit.getLocation()))
                || !BoatLandingGeometry.hasExit(ctx.level(),ctx.level()::isLoaded,hit.getLocation(),ctx.player().getBbWidth(),ctx.player().getBbHeight(),ctx.player().getYRot())) return State.RUNNING;
        plan=new BoatLandingSnapshot.Plan(plan.source(),plan.landing(),plan.item(),plan.existingBoat(),hit.getLocation(),plan.airborne());
        if (!ctx.level().noCollision(null, BoatLandingGeometry.boatBox(plan.spawn())) || !ctx.mutationAvailable()) return State.RUNNING;
        beforeBoats = new HashSet<>();
        for (Boat boat : ctx.level().getEntitiesOfClass(Boat.class, BoatLandingGeometry.boatBox(plan.spawn()).inflate(2))) beforeBoats.add(boat.getUUID());
        beforeItems = count(ctx.player(), plan.item()); effects = true;
        placementSubmissions++;
        receipt = ctx.actions().useItem(ctx, hand, NativeConfirmation.serverObservedEntity(this::observePlacement), 30);
        return State.RUNNING;
    }
    private NativeConfirmation.Verdict observePlacement(LocalPlayerContext ctx) {
        java.util.List<UUID> candidates = new java.util.ArrayList<>();
        for (Boat boat : ctx.level().getEntitiesOfClass(Boat.class, BoatLandingGeometry.boatBox(plan.spawn()).inflate(0.5))) {
            if (boat.getType() == EntityType.BOAT && boat.getPickResult().is(plan.item())) candidates.add(boat.getUUID());
        }
        var evidence = placementEvidence(beforeBoats, candidates, beforeItems, count(ctx.player(), plan.item()), ctx.player().isCreative());
        if (evidence.uuid() != null) boatId = evidence.uuid();
        return evidence.verdict();
    }
    record PlacementEvidence(NativeConfirmation.Verdict verdict, UUID uuid) {}
    static PlacementEvidence placementEvidence(Set<UUID> before, java.util.List<UUID> matches, int oldCount, int newCount, boolean creative) {
        var fresh = matches.stream().filter(id -> !before.contains(id)).distinct().toList();
        if (fresh.size() > 1) return new PlacementEvidence(NativeConfirmation.Verdict.DIVERGED, null);
        if (fresh.isEmpty() || !creative && newCount >= oldCount) return new PlacementEvidence(NativeConfirmation.Verdict.PENDING, null);
        return new PlacementEvidence(NativeConfirmation.Verdict.APPLIED, fresh.getFirst());
    }
    private Boat find(LocalPlayerContext ctx) {
        if (boatId == null) return null;
        for (Boat boat : ctx.level().getEntitiesOfClass(Boat.class,BoatLandingGeometry.boatBox(plan.spawn()).inflate(3)))
            if (boatId.equals(boat.getUUID())) return boat;
        return null;
    }
    private boolean stable(LocalPlayerContext ctx, Boat boat) {
        if (!supported(ctx,boat)) { stableTicks = 0; return false; }
        if (stableTick != ctx.tickRevision()) { stableTick = ctx.tickRevision(); stableTicks++; }
        return !ctx.player().onGround() || stableTicks >= 2;
    }
    private boolean supported(LocalPlayerContext ctx, Boat boat) {
        if (BoatLandingSnapshot.stationary(boat)) return true;
        var floor=BoatLandingGeometry.support(ctx.level(),ctx.level()::isLoaded,plan.landing());
        return boat.isAlive() && !boat.isInWater() && boat.fallDistance<=.01F
                && boat.getDeltaMovement().lengthSqr()<.0004 && floor!=null && boat.position().distanceToSqr(floor)<.0001;
    }
    private static boolean targeted(LocalPlayerContext ctx, Boat boat) {
        var eye=ctx.player().getEyePosition(); var reach=ctx.player().getViewVector(1).scale(ctx.player().entityInteractionRange());
        var hit=net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(ctx.player(),eye,eye.add(reach),
                ctx.player().getBoundingBox().expandTowards(reach).inflate(1), entity -> !entity.isSpectator() && entity.isPickable(),reach.lengthSqr());
        return ctx.player().canInteractWithEntity(boat, 0) && hit!=null && hit.getEntity().getUUID().equals(boat.getUUID())
                && BoatLandingGeometry.visible(ctx.level(), ctx.level()::isLoaded, ctx.player().getEyePosition(), boat.getBoundingBox().getCenter());
    }
    private static int count(LocalPlayer player, Item item) {
        int count = 0;
        for (int i=0;i<player.getInventory().getContainerSize();i++) if (player.getInventory().getItem(i).is(item)) count += player.getInventory().getItem(i).getCount();
        return count;
    }
    private static UUID vehicleId(LocalPlayer player) { return player.getVehicle() == null ? null : player.getVehicle().getUUID(); }
    private static void look(LocalPlayerContext ctx, Vec3 point) {
        Vec3 d = point.subtract(ctx.player().getEyePosition());
        float yaw=Math.hypot(d.x,d.z)<.001 ? ctx.player().getYRot() : (float)(Math.toDegrees(Math.atan2(d.z,d.x))-90);
        float pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));
        if(!ctx.player().onGround() && d.length()<=ctx.player().blockInteractionRange()) ctx.body().requestImmediateLook(yaw,pitch,ctx.tickRevision());
        else ctx.body().requestLook(yaw,pitch,ctx.tickRevision());
    }
    private void down(LocalPlayerContext ctx) {
        if(ctx.player().getEyePosition().y-plan.spawn().y<=ctx.player().blockInteractionRange())
            ctx.body().requestImmediateLook(ctx.player().getYRot(),90,ctx.tickRevision());
        else ctx.body().requestLook(ctx.player().getYRot(),90,ctx.tickRevision());
    }
    private State fail(String reason) { failed = true; detail = reason; return State.FAILED; }
    static boolean damageFree(float initialHealth, float initialAbsorption, float health, float absorption) {
        return health >= initialHealth && absorption >= initialAbsorption;
    }
    private void drain(LocalPlayerContext ctx) {
        if (preparation != null) preparation.continueCleanup(ctx);
        if (receipt == null) return;
        try {
            ctx.actions().poll(ctx, receipt);
            if (receipt.terminal()) { receipt = null; return; }
            if (receipt.kind() == NativeActionReceipt.Kind.USE_ITEM) {
                if (ctx.mutationAvailable()) receipt = ctx.actions().releaseUsingItem(ctx, receipt);
            } else {
                ctx.actions().retireOneShotForTaskBoundary(ctx, receipt, "boat landing fallback took over"); receipt = null;
            }
        } catch (IllegalArgumentException retiredByBoundary) { receipt = null; }
    }
    public boolean ready() { return ready; }
    public boolean failed() { return failed; }
    public boolean effectsStarted() { return effects; }
    public Vec3 aimPoint() { return aim; }
    public boolean wantsSneak() { return phase == Phase.DISMOUNT; }
    public org.maiwithu.maicraft.client.actor.BodyControlPort.Movement movementOverride() {
        return phase == Phase.RECOVER ? recovery.movement() : null;
    }
    public boolean cleanupPending() {
        return receipt != null && !receipt.terminal() || preparation != null && preparation.cleanupPending();
    }
    public void cancel(LocalPlayerContext ctx) {
        cancelled = true;
        if (preparation != null && !preparation.ready() && epoch == ctx.bodyEpoch()
                && revision == ctx.controlRevision() && ctx.permitsNativeActions()) preparation.closeForFailure(ctx);
        if (recovery != null) recovery.cancel(ctx);
        if (ctx.player().isPassenger()) phase = Phase.RIDING;
    }
    public Map<String,Object> diagnostics() {
        var result = new LinkedHashMap<String,Object>();
        result.put("phase", phase.name()); result.put("detail", detail); result.put("created_this_session", owned);
        result.put("placement_submissions",placementSubmissions); result.put("mount_submissions",mountSubmissions);
        result.put("left_in_world", phase == Phase.DONE && (recovery == null || recovery.boatLeft()));
        result.put("item_recovered", recovery != null && recovery.recovered());
        result.put("boat_uuid", boatId == null ? "unconfirmed" : boatId.toString()); result.put("airborne_opportunity", plan.airborne());
        result.put("ready", ready); result.put("effects_started", effects);
        result.put("limits", "native reach, geometry and observed spawn/mount state; no falling-boat immunity assumed");
        return result;
    }
}
