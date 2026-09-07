package org.maiwithu.maicraft.core.pathing.baritone.landing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** Unexpected-fall trigger support; all preparation, placement and recovery stay in the shared session. */
public final class EmergencyLanding {
    private EmergencyLanding() {}
    public static boolean triggered(LocalPlayer player) {
        boolean grounded = player.onGround() || player.isInWater() || player.isSwimming() || player.onClimbable();
        if (grounded || org.maiwithu.maicraft.core.WorkProfile.of(player).fearless()
                || player.getDeltaMovement().y >= 0) return false;
        // Start acquiring protection on the first descending tick when impact would hurt.
        // Fast descent remains an independent fallback if a synced/modded estimate is stale.
        if (org.maiwithu.maicraft.core.task.survival.SurvivalDecisions.mlgTriggered(false,
                player.getDeltaMovement().y, true)) return true;
        BlockPos ground = groundBelow(player);
        return ground != null && predictedDamage(player, ground) > 0;
    }
    private static float predictedDamage(LocalPlayer player, BlockPos support) {
        double height = baritone.pathing.movement.CollisionGeometry.supportHeight(player.level(), support);
        return FallDamageBudget.capture(player).damage(Math.max(0, player.getY() - support.getY() - height),
                FallDamageBudget.Landing.of(player.level().getBlockState(support)), true);
    }
    public static LandingAssistSession find(LocalPlayerContext context) {
        BlockPos ground = groundBelow(context.player());
        if (ground == null) return null;
        return find(context, ground.above());
    }
    /** A running fall keeps its already selected support and steering while adopting self-rescue. */
    public static LandingAssistSession find(LocalPlayerContext context, BlockPos feet) {
        if (!context.level().isLoaded(feet) || !context.level().isLoaded(feet.below())) return null;
        var inventory = LandingAssistPlan.InventorySnapshot.capture(context.player(), TerrainPermit.LANDING_ONLY,
                context.level().dimensionType().ultraWarm());
        var candidates = new java.util.ArrayList<LandingAssistPlan>();
        var player = context.player();
        var window = new WaterLandingWindow(player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY),
                player.blockInteractionRange(), Math.max(1.62, player.getEyeHeight()), Math.max(0, -player.getDeltaMovement().y));
        double drop = Math.max(0, player.getY() - feet.getY() + 1
                - baritone.pathing.movement.CollisionGeometry.supportHeight(context.level(), feet.below()));
        // A new physical fall gets its own bounded supply attempt; a previous route's failed
        // search must not suppress emergency access after the player's situation changes.
        for (var plan : inventory.automaticCandidates(context.level(), feet, EmbeddedBaritonePolicy::protects, true)) {
            if (!LandingAssistGeometry.safe(context.level(), context.level()::isLoaded, plan,
                    player.getBbWidth(), Math.max(1.8, player.getBbHeight()),
                    EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) continue;
            if (plan.kind() == LandingAssistPlan.Kind.HAY
                    && !plan.survives(FallDamageBudget.capture(player), player.getY(), true)) continue;
            if (!plan.existing() && plan.kind() == LandingAssistPlan.Kind.WATER && !window.permits(drop)) continue;
            candidates.add(plan);
        }
        return candidates.isEmpty() ? null : LandingAssistSession.automatic(candidates, true);
    }
    /** Apply the shared session's aim and fall steering through the same native body lease. */
    public static void tick(LocalPlayerContext context, LandingAssistSession session) {
        var player = context.player();
        Vec3 aim = session.aimPoint().subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z)));
        context.body().requestLook(yaw, pitch, context.tickRevision());
        session.tick(context);
        boolean sneak = session.wantsSneak(context);
        boolean hold = session.holdingForRecovery(context) || player.onGround();
        Vec3 delta = Vec3.atBottomCenterOf(session.plan().feet()).subtract(player.position()).subtract(player.getDeltaMovement());
        context.body().applySteering(cameraYaw -> {
            if (hold)
                return new BodyControlPort.Movement(0, 0, false, sneak, false);
            double angle = Math.toRadians(cameraYaw);
            float forward = (float) Math.clamp(-delta.x * Math.sin(angle) + delta.z * Math.cos(angle), -1, 1);
            float strafe = (float) Math.clamp(delta.x * Math.cos(angle) + delta.z * Math.sin(angle), -1, 1);
            return new BodyControlPort.Movement(forward, strafe, false, sneak, false);
        }, player.getYRot(), context.tickRevision());
    }
    /** Center plus four body corners, preserving the original closest native-collider probe. */
    private static BlockPos groundBelow(LocalPlayer player) {
        var box = player.getBoundingBox();
        Vec3[] origins = {player.position(), new Vec3(box.minX, player.getY(), box.minZ),
                new Vec3(box.maxX, player.getY(), box.minZ), new Vec3(box.minX, player.getY(), box.maxZ),
                new Vec3(box.maxX, player.getY(), box.maxZ)};
        BlockPos best = null; double nearest = Double.POSITIVE_INFINITY;
        for (Vec3 from : origins) {
            var hit = player.level().clip(new ClipContext(from, from.add(0, -40, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            double drop = player.getY() - hit.getLocation().y;
            // Sable's native clip may return plot-storage coordinates. Neither its location nor
            // its block cell may be treated as a world-space landing without a matching world ray.
            if (!Double.isFinite(drop) || drop < -1.0E-5 || drop > 40.00001
                    || Math.abs(hit.getLocation().x - from.x) > 1.0E-5
                    || Math.abs(hit.getLocation().z - from.z) > 1.0E-5
                    || !new net.minecraft.world.phys.AABB(hit.getBlockPos()).inflate(1.0E-5).contains(hit.getLocation()))
                return unsupportedSupport();
            if (drop < nearest) { nearest = drop; best = hit.getBlockPos(); }
        }
        return best;
    }
    private static BlockPos unsupportedSupport() {
        LandingAssistPolicy.report(java.util.Map.of("phase", "unsupported physical-structure or unverified world-space landing ray",
                "support_state", "unsupported", "submitted", false));
        return null;
    }
}
