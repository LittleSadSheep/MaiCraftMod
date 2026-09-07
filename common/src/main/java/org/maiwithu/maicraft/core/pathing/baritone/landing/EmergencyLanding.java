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
        var rejected = new java.util.LinkedHashMap<String,String>();
        var player = context.player();
        // A new physical fall gets its own bounded supply attempt; a previous route's failed
        // search must not suppress emergency access after the player's situation changes.
        var offered = inventory.automaticCandidates(context.level(), feet, EmbeddedBaritonePolicy::protects, true);
        if (offered.stream().noneMatch(plan -> plan.kind() == LandingAssistPlan.Kind.WATER))
            rejected.put("WATER",inventory.ultraWarm() ? "water evaporates in this dimension" : "native water placement or support is unavailable");
        for (var plan : offered) {
            if (!LandingAssistGeometry.safe(context.level(), context.level()::isLoaded, plan,
                    player.getBbWidth(), Math.max(1.8, player.getBbHeight()),
                    EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells())) {
                rejected.put(plan.kind().name(),"body clearance or native support does not permit this landing"); continue;
            }
            if (plan.kind() == LandingAssistPlan.Kind.HAY
                    && !plan.survives(FallDamageBudget.capture(player), player.getY(), true)) {
                rejected.put("HAY","remaining damage would be fatal"); continue;
            }
            // The fall has already happened: a useful native bucket-use tick need not be
            // guaranteed for every possible departure phase. The session tests the actual
            // ray and reach each tick; planned departures retain their stricter admission.
            candidates.add(plan);
        }
        return candidates.isEmpty() ? null : LandingAssistSession.automatic(candidates, true).rejectedCandidates(rejected);
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
        var level = player.level();
        double top = Math.min(player.getY(), level.getMaxBuildHeight());
        double bottom = level.getMinBuildHeight();
        double span = top - bottom;
        if (!Double.isFinite(span) || span <= 0) return null;
        var box = player.getBoundingBox();
        // Skip world-exterior air even when a mod places the player far above build height.
        // Vertical rays stay in one loaded column and cover its effective build range.
        Vec3[] origins = {new Vec3(player.getX(), top, player.getZ()), new Vec3(box.minX, top, box.minZ),
                new Vec3(box.maxX, top, box.minZ), new Vec3(box.minX, top, box.maxZ),
                new Vec3(box.maxX, top, box.maxZ)};
        BlockPos best = null; double nearest = Double.POSITIVE_INFINITY;
        for (Vec3 from : origins) {
            if (!level.isLoaded(BlockPos.containing(from.x, Math.min(top, level.getMaxBuildHeight() - 1), from.z)))
                return null;
            var hit = level.clip(new ClipContext(from, new Vec3(from.x, bottom, from.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK) continue;
            double drop = from.y - hit.getLocation().y;
            // Sable's native clip may return plot-storage coordinates. Neither its location nor
            // its block cell may be treated as a world-space landing without a matching world ray.
            if (!Double.isFinite(drop) || drop < -1.0E-5 || drop > span + 1.0E-5
                    || Math.abs(hit.getLocation().x - from.x) > 1.0E-5
                    || Math.abs(hit.getLocation().z - from.z) > 1.0E-5
                    || !new net.minecraft.world.phys.AABB(hit.getBlockPos()).inflate(1.0E-5).contains(hit.getLocation()))
                return unsupportedSupport();
            if (!level.isLoaded(hit.getBlockPos())) return null;
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
