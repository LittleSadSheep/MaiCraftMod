package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;

/** Native placement, observed protection, stable touchdown and attributable cleanup for one fall. */
public final class LandingAssistSession {
    public record Change(BlockPos position, BlockState before, BlockState after) {}
    private LandingAssistPlan plan;
    private List<LandingAssistPlan> automaticCandidates;
    private LandingMaterialSupply materialSupply;
    private boolean materialBound;
    private LandingPreparation preparation;
    private NativeActionReceipt receipt;
    private NativeActionReceipt stopRelease;
    private String stopReason;
    private BlockState placed;
    private boolean submitted, failed, complete, cleaning, removed, damageObserved, waterContactObserved;
    private boolean emergency, hayContactObserved, airborneObserved;
    private float healthLost, absorptionLost;
    private int stableTicks;
    private int displacedStableTicks;
    private long lastTick = Long.MIN_VALUE;
    private long cleanupStarted = Long.MIN_VALUE;
    private float previousHealth = Float.NaN;
    private float previousAbsorption = Float.NaN;
    private String detail = "prepare before leaving the supporting edge";
    private final List<Change> changes = new ArrayList<>();

    public LandingAssistSession(LandingAssistPlan plan) {
        this.plan = plan;
        if (!plan.existing()) preparation = new LandingPreparation(plan.kind().item);
    }
    public LandingAssistPlan plan() { return plan; }
    public static LandingAssistSession emergency(LandingAssistPlan plan) {
        var session = new LandingAssistSession(plan);
        session.emergency = true; session.airborneObserved = true;
        return session;
    }
    public static LandingAssistSession automatic(List<LandingAssistPlan> candidates, boolean airborne) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("automatic landing needs observed candidates");
        var session = new LandingAssistSession(candidates.getFirst());
        session.automaticCandidates = List.copyOf(candidates); session.emergency = airborne; session.airborneObserved = airborne;
        return session;
    }
    private boolean materialReady(LocalPlayerContext context) {
        if (automaticCandidates == null || materialBound) return true;
        for (var candidate : automaticCandidates) {
            if (candidate.existing()) { bindMaterial(candidate); return true; }
            if (java.util.stream.IntStream.range(0,context.player().getInventory().getContainerSize()).anyMatch(i ->
                    context.player().getInventory().getItem(i).is(candidate.kind().item)) && materialSupply == null) {
                bindMaterial(candidate); return true;
            }
        }
        if (materialSupply == null) materialSupply = new LandingMaterialSupply(automaticCandidates.stream()
                .map(p -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.kind().item)).distinct().toList());
        var supplied = materialSupply.tick(context,remainingActionTicks(context));
        detail = supplied.detail();
        if (supplied.state() == LandingMaterialSupply.State.AVAILABLE) {
            for (var candidate : automaticCandidates) {
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(candidate.kind().item).equals(supplied.itemId())) {
                    bindMaterial(candidate); return true;
                }
            }
        }
        if (supplied.state() == LandingMaterialSupply.State.UNAVAILABLE) {
            LandingAssistPolicy.automaticSupplyFailed(); fail("automatic landing material unavailable: " + supplied.detail());
        }
        return false;
    }
    private void bindMaterial(LandingAssistPlan selected) {
        plan = selected; preparation = selected.existing() ? null : new LandingPreparation(selected.kind().item);
        materialBound = true;
    }
    private int remainingActionTicks(LocalPlayerContext context) {
        if (context.player().onGround()) return Integer.MAX_VALUE;
        double actionHeight = plan.feet().getY() + Math.max(0,context.player().blockInteractionRange()-context.player().getEyeHeight());
        double y = context.player().getY(), velocity = context.player().getDeltaMovement().y;
        double gravity = context.player().getAttributeValue(Attributes.GRAVITY);
        for (int ticks=0;ticks<200;ticks++) {
            if (y <= actionHeight) return ticks;
            y += velocity; velocity = (velocity-gravity)*.98;
        }
        return 200;
    }
    public boolean prepareAlreadyHeld(LocalPlayerContext context) {
        rememberHealth(context);
        if (!survivesHay(context)) return false;
        if (!LandingAssistGeometry.safe(context.level(), context.level()::isLoaded, plan,
                context.player().getBbWidth(), Math.max(1.8, context.player().getBbHeight()),
                EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) return false;
        return plan.existing() ? LandingAssistPlan.existingSafe(plan.kind(), context.level().getBlockState(plan.cell()))
                : preparation.acceptHeld(context) && LandingAssistPlan.canPlace(plan.kind(), context.level(), plan.cell());
    }
    public boolean prepare(LocalPlayerContext context) {
        if (failed) { continuePreparationCleanup(context); return false; }
        if (Float.isNaN(previousHealth)) rememberHealth(context);
        if (!materialReady(context)) return false;
        if (!survivesHay(context)) { fail("hay would not reduce the observed fall to survivable damage"); return false; }
        if (!context.level().isLoaded(plan.cell()) || !context.level().isLoaded(plan.clicked())) return false;
        if (!LandingAssistGeometry.safe(context.level(), context.level()::isLoaded, plan,
                context.player().getBbWidth(), Math.max(1.8, context.player().getBbHeight()),
                org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) {
            if (preparation != null) preparation.closeForFailure(context);
            fail("loaded landing body clearance or support changed before departure"); return false;
        }
        if (plan.existing()) {
            if (!LandingAssistPlan.existingSafe(plan.kind(), context.level().getBlockState(plan.cell())))
                fail("the observed landing mechanism changed before departure");
            return !failed;
        }
        if (EmbeddedBaritonePolicy.protects(plan.cell())
                || !LandingAssistPlan.canPlace(plan.kind(), context.level(), plan.cell())) {
            preparation.closeForFailure(context);
            fail("landing placement is no longer an unprotected air cell with the required support");
            return false;
        }
        boolean ready = preparation.tick(context);
        if (preparation.failed()) fail(preparation.diagnostic());
        return ready;
    }

    /** Does not clear movement on failure: the fall owner keeps steering until a real landing. */
    public void tick(LocalPlayerContext context) {
        if (complete || lastTick == context.tickRevision()) return;
        lastTick = context.tickRevision();
        airborneObserved |= !context.player().onGround();
        if (healthDecreased(previousHealth, previousAbsorption,
                context.player().getHealth(), context.player().getAbsorptionAmount())) damageObserved = true;
        if (Float.isFinite(previousHealth)) healthLost += Math.max(0, previousHealth - context.player().getHealth());
        if (Float.isFinite(previousAbsorption)) absorptionLost += Math.max(0, previousAbsorption - context.player().getAbsorptionAmount());
        rememberHealth(context);
        // Receipt polling can span the physical impact. Sample native fluid contact even while
        // awaiting placement evidence, so a dry, damage-free touchdown cannot prove a clutch.
        if (plan.kind() == LandingAssistPlan.Kind.WATER && nearLanding(context)
                && context.player().isInWater() && context.player().fallDistance == 0
                && touchesPlannedWater(context))
            waterContactObserved = true;
        if (plan.kind() == LandingAssistPlan.Kind.HAY && context.player().onGround()
                && context.level().getBlockState(plan.cell()).is(Blocks.HAY_BLOCK)
                && Math.abs(context.player().getBoundingBox().minY - plan.cell().getY() - 1) < 0.01
                && context.player().getBoundingBox().intersects(new net.minecraft.world.phys.AABB(plan.cell()).inflate(0.001)))
            hayContactObserved = true;
        if (!context.permitsNativeActions()) { fail("control authority changed during landing assistance"); return; }
        if (stopReason != null) {
            continueStop(context);
            if (cleanupPending()) return;
        }
        if (airborneObserved && context.player().onGround() && !submitted && !plan.existing()) {
            if (materialSupply != null && !materialSupply.result().finished()) materialSupply.finish(context,"ground contact preceded landing protection");
            if (preparation != null) preparation.closeForFailure(context);
            fail("ground contact occurred before native landing protection was submitted");
        }
        if (materialSupply != null && materialSupply.cleanupPending()) {
            materialSupply.finish(context,"settling landing material access");
            if (materialSupply.cleanupPending()) return;
        }
        if (preparation != null && preparation.cleanupPending()) {
            preparation.continueCleanup(context);
            if (preparation.cleanupPending()) return;
        }
        // Ending an off-target fall is independent of proving this plan succeeded. Water flow,
        // knockback or a missed aid can leave the body safely supported elsewhere indefinitely.
        if (airborneObserved && !nearLanding(context) && settledElsewhere(context)) {
            if (materialSupply != null && !materialSupply.result().finished()) {
                materialSupply.finish(context,"fall ended away from the planned landing");
                if (materialSupply.cleanupPending()) return;
            }
            if (receipt != null) {
                settle(context);
                if (receipt != null) return;
            }
            if (++displacedStableTicks >= confirmationDwell(context)) {
                if (preparation != null) preparation.closeForFailure(context);
                fail("fall ended away from the planned landing; protection unverified and any remaining aid retained");
                if (!cleanupPending()) complete = true;
            }
            return;
        }
        displacedStableTicks = 0;
        if (!failed && !materialReady(context)) return;
        if (!failed && preparation != null && !preparation.ready()) {
            boolean ready = emergency || automaticCandidates != null && !context.player().onGround()
                    ? preparation.tickEmergency(context,remainingActionTicks(context)) : preparation.tick(context);
            if (!ready) {
                if (!preparation.failed()) return;
                LandingPreparation held = new LandingPreparation(plan.kind().item);
                if (!context.player().onGround() && held.acceptHeld(context)) preparation = held;
                else { fail(preparation.diagnostic()); return; }
            }
        }
        if (receipt != null) {
            settle(context);
            if (receipt != null) return;
        }
        boolean stable = stable(context);
        if (failed) {
            if (!stable) {
                double remaining = Math.max(0, context.player().getY() - plan.feet().getY());
                if (FallDamageBudget.capture(context.player()).survives(remaining, FallDamageBudget.Landing.ORDINARY, true))
                    detail = "assist failed; retaining steering toward the currently survivable support, without claiming no damage";
                return;
            }
            complete = true;
            return;
        }
        if (stable) {
            if (++stableTicks < confirmationDwell(context)) { detail = "verifying supported touchdown and synchronized health"; return; }
            if (plan.existing() || removed) {
                finish("native protection and supported touchdown verified without observed damage");
                return;
            }
            if (placed != null) { clean(context); return; }
            fail("arrived without confirmed ownership of the intended landing aid");
            return;
        }
        stableTicks = 0;
        if (plan.existing() || submitted || context.player().onGround() || !context.mutationAvailable()) return;
        if (!survivesHay(context)) { fail("hay no longer makes this fall survivable"); return; }
        if (preparation == null || !preparation.ready()) { fail("landing item was not prepared before departure"); return; }
        if (plan.kind() != LandingAssistPlan.Kind.WATER && !context.player().isSecondaryUseActive()) return;
        if (EmbeddedBaritonePolicy.protects(plan.cell()) || !LandingAssistPlan.canPlace(plan.kind(), context.level(), plan.cell())) {
            fail("the placement cell changed while falling; no repeated or destructive use was submitted"); return;
        }
        BlockHitResult hit = trace(context, false);
        if (!matchesPlacement(hit)) return;
        if (plan.kind() == LandingAssistPlan.Kind.WATER
                && !org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall.waterCell(hit,
                        context.level().getBlockState(hit.getBlockPos()), false).equals(plan.cell())) return;
        InteractionHand hand = preparation.hand();
        if (!context.player().getItemInHand(hand).is(plan.kind().item)) { fail("prepared landing item left the selected hand"); return; }
        int count = context.player().getItemInHand(hand).getCount();
        NativeConfirmation confirmation = c -> {
            BlockState current = c.level().getBlockState(plan.cell());
            boolean consumed = c.player().getAbilities().instabuild || (plan.kind() == LandingAssistPlan.Kind.WATER
                    ? c.player().getItemInHand(hand).is(Items.BUCKET)
                    : !c.player().getItemInHand(hand).is(plan.kind().item) || c.player().getItemInHand(hand).getCount() < count);
            return LandingAssistPlan.existingSafe(plan.kind(), current) && consumed
                    ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
        };
        submitted = true; // Any exception after submission starts forbids a repeated clutch.
        detail = "native landing item submitted; awaiting block and inventory evidence";
        try {
            receipt = plan.kind() == LandingAssistPlan.Kind.WATER
                    ? context.actions().useItem(context, hand, confirmation, 12)
                    : context.actions().useBlock(context, hand, hit, confirmation, 12);
        } catch (RuntimeException unavailable) { fail("native landing submission became uncertain: " + unavailable.getMessage()); }
    }

    private void settle(LocalPlayerContext context) {
        try {
            if (cleaning && receipt.kind() == NativeActionReceipt.Kind.BREAK_BLOCK && !receipt.terminal()
                    && context.tickRevision() >= receipt.deadlineTick() - 1 && context.mutationAvailable()) {
                receipt = context.actions().cancelBreaking(context, receipt);
            }
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) {
                if (cleaning && receipt.kind() == NativeActionReceipt.Kind.BREAK_BLOCK && context.mutationAvailable())
                    receipt = context.actions().continueBreaking(context, receipt);
                return;
            }
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                fail("native landing receipt: " + receipt.status() + "; " + receipt.detail());
            } else if (cleaning) {
                changes.add(new Change(plan.cell(), placed, context.level().getBlockState(plan.cell())));
                removed = true;
                stableTicks = 0;
                detail = "own temporary aid removed; waiting for supported feet after removal";
            } else {
                placed = context.level().getBlockState(plan.cell());
                changes.add(new Change(plan.cell(), Blocks.AIR.defaultBlockState(), placed));
                detail = "own landing aid confirmed; waiting for actual fall reset and touchdown";
            }
            receipt = null;
        } catch (RuntimeException unavailable) { receipt = null; fail("landing confirmation unavailable: " + unavailable.getMessage()); }
    }

    private void clean(LocalPlayerContext context) {
        if (cleanupStarted == Long.MIN_VALUE) cleanupStarted = context.tickRevision();
        if (context.tickRevision() - cleanupStarted >= 80) {
            finish("landed; own aid retained after the bounded native recovery aiming window"); return;
        }
        if (!LandingAssistGeometry.safeAfterRemoval(context.level(), context.level()::isLoaded, plan,
                context.player().getBbWidth(), Math.max(1.8, context.player().getBbHeight()),
                EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) {
            finish("landed; own aid retained because safe support for removal is no longer verified"); return;
        }
        if (!LandingAssistPlan.canRecover(submitted && !plan.existing() && placed != null, placed,
                context.level().getBlockState(plan.cell()), EmbeddedBaritonePolicy.protects(plan.cell()))) {
            // Growth, replacement or a new protection claim revokes attribution for removal.
            finish("landed; temporary aid left because its exact state or protection changed");
            return;
        }
        if (!context.mutationAvailable()) return;
        if (plan.kind() == LandingAssistPlan.Kind.WATER) {
            if (!org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall.canRecover(
                    context.level().getBlockState(plan.cell()), placed != null, EmbeddedBaritonePolicy.protects(plan.cell()))) {
                finish("landed; only the confirmed original source water may be recovered"); return;
            }
            InteractionHand hand = preparation.hand();
            if (!context.player().getItemInHand(hand).is(Items.BUCKET)) {
                finish("landed; own water left because its empty bucket is unavailable");
                return;
            }
            BlockHitResult hit = trace(context, true);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(plan.cell())) return;
            cleaning = true;
            receipt = context.actions().useItem(context, hand, c ->
                    c.player().getItemInHand(hand).is(Items.WATER_BUCKET) && c.level().getFluidState(plan.cell()).isEmpty()
                            ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING, 12);
        } else {
            BlockHitResult hit = trace(context, false);
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(plan.cell())) return;
            float progress = placed.getDestroyProgress(context.player(), context.level(), plan.cell());
            if (!(progress > 0) || !Float.isFinite(progress)) {
                finish("landed; own aid left because native removal cannot progress"); return;
            }
            cleaning = true;
            receipt = context.actions().startBreaking(context, hit, (int) Math.clamp(Math.ceil(1D / progress) + 40, 20, 1200));
        }
    }

    public Vec3 aimPoint() { return placed == null ? plan.aimPoint() : Vec3.atCenterOf(plan.cell()); }
    /** Hold the landing cell against generic water bobbing while its source is being recovered. */
    public boolean holdingForRecovery(LocalPlayerContext context) {
        return !complete && plan.kind() == LandingAssistPlan.Kind.WATER && nearLanding(context)
                && (context.player().isInWater() && context.player().fallDistance == 0
                    || submitted && context.player().onGround());
    }
    public boolean wantsSneak(LocalPlayerContext context) {
        if (!submitted && !plan.existing() && plan.kind() != LandingAssistPlan.Kind.WATER
                && !context.player().onGround()) return true;
        if (plan.kind() != LandingAssistPlan.Kind.SLIME) return false;
        // Suppress only the final small rebound. Suppressing a high initial fall restores ordinary damage.
        return context.player().getDeltaMovement().y <= 0 && context.player().getY() - plan.cell().getY() < 3
                && context.player().fallDistance + 1 < context.player().getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
    }
    public boolean complete() { return complete; }
    public boolean failed() { return failed || complete && damageObserved && plan.kind() != LandingAssistPlan.Kind.HAY; }
    public boolean cleanupPending() { return stopReason != null || stopRelease != null
            || preparation != null && preparation.cleanupPending()
            || materialSupply != null && materialSupply.cleanupPending(); }
    private void continuePreparationCleanup(LocalPlayerContext context) {
        if (preparation != null) preparation.continueCleanup(context);
        if (materialSupply != null && materialSupply.cleanupPending()) materialSupply.finish(context,"landing preparation ended");
    }
    /** Retire native receipts at an explicit body/task boundary; never assume a pickup happened. */
    public void stop(LocalPlayerContext context, String reason) {
        if (complete) return;
        stopReason = reason == null ? "landing session interrupted" : reason;
        fail(stopReason);
        continueStop(context);
    }
    private void continueStop(LocalPlayerContext context) {
        if (!context.permitsNativeActions()) return;
        if (preparation != null) preparation.closeForFailure(context);
        if (materialSupply != null) materialSupply.finish(context,stopReason);
        if (receipt != null) {
            receipt = context.actions().poll(context,receipt);
            if (!receipt.terminal()) {
                if (receipt.kind() == NativeActionReceipt.Kind.USE_ITEM) {
                    if (!context.mutationAvailable()) return;
                    stopRelease = context.actions().releaseUsingItem(context,receipt);
                    receipt = null;
                } else receipt = receipt.kind() == NativeActionReceipt.Kind.BREAK_BLOCK
                        ? context.actions().cancelBreakingForTaskBoundary(context,receipt,stopReason)
                        : context.actions().retireOneShotForTaskBoundary(context,receipt,stopReason);
            }
            if (receipt != null) settle(context);
        }
        if (stopRelease != null) {
            stopRelease = context.actions().poll(context,stopRelease);
            if (stopRelease.terminal()) {
                if (stopRelease.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED)
                    fail("landing item release unconfirmed: " + stopRelease.detail());
                stopRelease = null;
            }
        }
        if (receipt == null && stopRelease == null
                && (preparation == null || !preparation.cleanupPending())
                && (materialSupply == null || !materialSupply.cleanupPending())) stopReason = null;
    }
    public List<Change> drainChanges() { var result = List.copyOf(changes); changes.clear(); return result; }
    public Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("strategy", plan.kind().name()); result.put("phase", detail);
        result.put("automatic",automaticCandidates != null);
        if (materialSupply != null) result.put("material_supply",java.util.Map.of("state",materialSupply.result().state().name(),"detail",materialSupply.result().detail()));
        result.put("landing_feet",java.util.Map.of("x",plan.feet().getX(),"y",plan.feet().getY(),"z",plan.feet().getZ()));
        result.put("submitted", submitted); result.put("confirmed_own_placement", placed != null);
        result.put("existing_environment", plan.existing()); result.put("complete", complete);
        result.put("failed", failed()); result.put("damage_observed", damageObserved);
        result.put("removed_own_aid", removed);
        result.put("native_water_contact", waterContactObserved);
        result.put("native_hay_contact", hayContactObserved);
        result.put("health_lost", healthLost); result.put("absorption_lost", absorptionLost);
        result.put("mitigated_with_damage", complete && !failed() && plan.kind() == LandingAssistPlan.Kind.HAY && damageObserved);
        if (receipt != null) result.put("receipt", receipt.status().name());
        return result;
    }
    private boolean stable(LocalPlayerContext c) {
        var player = c.player();
        return nearLanding(c) && (player.onGround() && Math.abs(player.getDeltaMovement().y) < 0.1
                || !removed && player.isInWater() && player.fallDistance == 0 && player.getDeltaMovement().y >= 0);
    }
    private boolean nearLanding(LocalPlayerContext c) {
        return c.player().position().distanceToSqr(Vec3.atBottomCenterOf(plan.feet())) < 4;
    }
    private boolean settledElsewhere(LocalPlayerContext context) {
        var player = context.player();
        double vertical = Math.abs(player.getDeltaMovement().y);
        return player.onGround() && vertical < 0.1
                || (player.isInWater() || player.onClimbable()) && player.fallDistance == 0 && vertical <= 0.2;
    }
    private boolean touchesPlannedWater(LocalPlayerContext c) {
        BlockState source = c.level().getBlockState(plan.cell());
        return LandingAssistPlan.existingSafe(LandingAssistPlan.Kind.WATER, source)
                && c.player().getBoundingBox().intersects(source.getFluidState()
                        .getShape(c.level(), plan.cell()).bounds().move(plan.cell()));
    }
    private boolean matchesPlacement(BlockHitResult hit) {
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(plan.clicked()) && hit.getDirection() == plan.face();
    }
    private BlockHitResult trace(LocalPlayerContext c, boolean pickup) {
        Vec3 eye = c.player().getEyePosition();
        return c.level().clip(new ClipContext(eye, eye.add(c.player().getViewVector(1).scale(c.player().blockInteractionRange())),
                ClipContext.Block.OUTLINE, pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, c.player()));
    }
    public static boolean healthDecreased(float beforeHp, float beforeAbsorption, float hp, float absorption) {
        return Float.isFinite(beforeHp) && hp < beforeHp - 0.001F
                || Float.isFinite(beforeAbsorption) && absorption < beforeAbsorption - 0.001F;
    }
    private void rememberHealth(LocalPlayerContext c) {
        previousHealth = c.player().getHealth(); previousAbsorption = c.player().getAbsorptionAmount();
    }
    private static int confirmationDwell(LocalPlayerContext context) {
        var info = context.connection() == null ? null : context.connection().getPlayerInfo(context.player().getUUID());
        return info == null ? 10 : Math.clamp(4 + (info.getLatency() + 49) / 50, 4, 40);
    }
    private boolean survivesHay(LocalPlayerContext context) {
        return plan.kind() != LandingAssistPlan.Kind.HAY
                || plan.survives(FallDamageBudget.capture(context.player()), context.player().getY(), true);
    }
    private void finish(String outcome) {
        complete = true;
        if (plan.kind() == LandingAssistPlan.Kind.HAY) {
            if (!(previousHealth > 0)) fail("hay landing did not preserve survival");
            else if (!hayContactObserved) fail("landing finished without observed collision support from the hay");
            else detail = damageObserved ? "native hay cushioning and supported survival verified; observed damage recorded" : outcome;
        }
        else if (damageObserved) fail("landing finished but health or absorption decreased; no-damage success is unverified");
        else if (plan.kind() == LandingAssistPlan.Kind.WATER && !waterContactObserved)
            fail("landing finished without observed native water contact and fall reset");
        else detail = outcome;
    }
    private void fail(String reason) { failed = true; detail = reason; }
}
