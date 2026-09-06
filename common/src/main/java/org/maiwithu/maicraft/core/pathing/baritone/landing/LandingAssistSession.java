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
    private final LandingAssistPlan plan;
    private LandingPreparation preparation;
    private NativeActionReceipt receipt;
    private BlockState placed;
    private boolean submitted, failed, complete, cleaning, removed, damageObserved;
    private int stableTicks;
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
    public boolean prepareAlreadyHeld(LocalPlayerContext context) {
        rememberHealth(context);
        if (!LandingAssistGeometry.safe(context.level(), context.level()::isLoaded, plan,
                context.player().getBbWidth(), Math.max(1.8, context.player().getBbHeight()),
                EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) return false;
        return plan.existing() ? LandingAssistPlan.existingSafe(plan.kind(), context.level().getBlockState(plan.cell()))
                : preparation.acceptHeld(context) && LandingAssistPlan.canPlace(plan.kind(), context.level(), plan.cell());
    }
    public boolean prepare(LocalPlayerContext context) {
        if (failed) { if (preparation != null) preparation.continueCleanup(context); return false; }
        if (Float.isNaN(previousHealth)) rememberHealth(context);
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
        if (healthDecreased(previousHealth, previousAbsorption,
                context.player().getHealth(), context.player().getAbsorptionAmount())) damageObserved = true;
        rememberHealth(context);
        if (!context.permitsNativeActions()) { fail("control authority changed during landing assistance"); return; }
        if (preparation != null && preparation.cleanupPending()) {
            preparation.continueCleanup(context);
            if (preparation.cleanupPending()) return;
        }
        if (!failed && preparation != null && !preparation.ready()) {
            preparation.tick(context);
            if (!preparation.failed()) return;
            LandingPreparation held = new LandingPreparation(plan.kind().item);
            if (!context.player().onGround() && held.acceptHeld(context)) preparation = held;
            else { fail(preparation.diagnostic()); return; }
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
                complete = true;
                if (damageObserved) fail("landing finished but health or absorption decreased; no-damage success is unverified");
                else detail = "native protection and supported touchdown verified without observed damage";
                return;
            }
            if (placed != null) { clean(context); return; }
            fail("arrived without confirmed ownership of the intended landing aid");
            return;
        }
        stableTicks = 0;
        if (plan.existing() || submitted || context.player().onGround() || !context.mutationAvailable()) return;
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
            complete = true; detail = "landed; own aid retained after the bounded native recovery aiming window"; return;
        }
        if (!LandingAssistGeometry.safeAfterRemoval(context.level(), context.level()::isLoaded, plan,
                context.player().getBbWidth(), Math.max(1.8, context.player().getBbHeight()),
                EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) {
            complete = true; detail = "landed; own aid retained because safe support for removal is no longer verified"; return;
        }
        if (!LandingAssistPlan.canRecover(submitted && !plan.existing() && placed != null, placed,
                context.level().getBlockState(plan.cell()), EmbeddedBaritonePolicy.protects(plan.cell()))) {
            // Growth, replacement or a new protection claim revokes attribution for removal.
            complete = true;
            detail = "landed; temporary aid left because its exact state or protection changed";
            if (damageObserved) fail("health changed during landing; the changed aid was left untouched");
            return;
        }
        if (!context.mutationAvailable()) return;
        if (plan.kind() == LandingAssistPlan.Kind.WATER) {
            if (!org.maiwithu.maicraft.core.pathing.baritone.WaterBucketFall.canRecover(
                    context.level().getBlockState(plan.cell()), placed != null, EmbeddedBaritonePolicy.protects(plan.cell()))) {
                complete = true; detail = "landed; only the confirmed original source water may be recovered"; return;
            }
            InteractionHand hand = preparation.hand();
            if (!context.player().getItemInHand(hand).is(Items.BUCKET)) {
                complete = true; detail = "landed; own water left because its empty bucket is unavailable";
                if (damageObserved) fail("health changed during landing; no damage-free outcome was verified");
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
                complete = true; detail = "landed; own aid left because native removal cannot progress"; return;
            }
            cleaning = true;
            receipt = context.actions().startBreaking(context, hit, (int) Math.clamp(Math.ceil(1D / progress) + 40, 20, 1200));
        }
    }

    public Vec3 aimPoint() { return placed == null ? plan.aimPoint() : Vec3.atCenterOf(plan.cell()); }
    public boolean wantsSneak(LocalPlayerContext context) {
        if (!submitted && !plan.existing() && plan.kind() != LandingAssistPlan.Kind.WATER
                && !context.player().onGround()) return true;
        if (plan.kind() != LandingAssistPlan.Kind.SLIME) return false;
        // Suppress only the final small rebound. Suppressing a high initial fall restores ordinary damage.
        return context.player().getDeltaMovement().y <= 0 && context.player().getY() - plan.cell().getY() < 3
                && context.player().fallDistance + 1 < context.player().getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
    }
    public boolean complete() { return complete; }
    public boolean failed() { return failed || complete && damageObserved; }
    public boolean cleanupPending() { return preparation != null && preparation.cleanupPending(); }
    public List<Change> drainChanges() { var result = List.copyOf(changes); changes.clear(); return result; }
    public Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("strategy", plan.kind().name()); result.put("phase", detail);
        result.put("submitted", submitted); result.put("confirmed_own_placement", placed != null);
        result.put("existing_environment", plan.existing()); result.put("complete", complete);
        result.put("failed", failed()); result.put("damage_observed", damageObserved);
        result.put("removed_own_aid", removed);
        if (receipt != null) result.put("receipt", receipt.status().name());
        return result;
    }
    private boolean stable(LocalPlayerContext c) {
        var player = c.player();
        boolean near = player.position().distanceToSqr(Vec3.atBottomCenterOf(plan.feet())) < 4;
        return near && (player.onGround() && Math.abs(player.getDeltaMovement().y) < 0.1
                || player.isInWater() && player.fallDistance == 0 && player.getDeltaMovement().y >= 0);
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
    private void fail(String reason) { failed = true; detail = reason; }
}
