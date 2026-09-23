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
import baritone.pathing.movement.CollisionGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;

/**
 * 意外下落时寻找可救援的落点：先看正下方，再看附近两格内能在剩余时间移到的位置，优先用水。
 * 只根据已加载且能解释的碰撞表面计划，不把无法转换到世界坐标的物理结构射线当作普通地板。
 */
public final class EmergencyLanding {
    private EmergencyLanding() {}
    public static boolean triggered(LocalPlayer player) {
        boolean grounded = player.onGround() || player.isInWater() || player.isSwimming() || player.onClimbable();
        if (grounded || WorkProfile.of(player).fearless()
                || player.getDeltaMovement().y >= 0) return false;
        // 首次检测到会造成伤害的下落时，就从下降 tick 开始准备着陆保护；若同步或模组估算过时，快速下降仍是独立的回退方案。
        if (SurvivalDecisions.mlgTriggered(false,
                player.getDeltaMovement().y, true)) return true;
        BlockPos ground = groundBelow(player);
        return ground != null && predictedDamage(player, ground) > 0;
    }
    private static float predictedDamage(LocalPlayer player, BlockPos support) {
        double height = CollisionGeometry.supportHeight(player.level(), support);
        return FallDamageBudget.capture(player).damage(Math.max(0, player.getY() - support.getY() - height),
                FallDamageBudget.Landing.of(player.level().getBlockState(support)), true);
    }
    public static LandingAssistSession find(LocalPlayerContext context) {
        BlockPos ground = groundBelow(context.player());
        if (ground == null) return null;
        return findNear(context,ground.above());
    }
    public static LandingAssistSession findNear(LocalPlayerContext context, BlockPos preferred) {
        var ground = preferred.below();
        var direct = find(context,preferred);
        var player = context.player();
        boolean vertical = player.getDeltaMovement().horizontalDistance() < .05
                && Math.hypot(player.getX()-preferred.getX()-.5,player.getZ()-preferred.getZ()-.5) < .55;
        if (direct != null && direct.plan().kind() == LandingAssistPlan.Kind.WATER
                && (vertical || AirLandingControl.reachable(player,preferred))) return direct;
        LandingAssistSession best = direct;
        double score = direct != null && (vertical || AirLandingControl.reachable(player,preferred))
                ? 100 + player.position().distanceToSqr(Vec3.atBottomCenterOf(preferred)) : Double.POSITIVE_INFINITY;
        // 少量局部候选足以绕过活板门、种植床或机器；每个候选仍须有已加载的原生地面，并且角色完整身体的轨迹可达。
        for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) {
            if (x == 0 && z == 0) continue;
            var feet = nearbyGround(context,ground.offset(x,0,z));
            if (feet == null || !AirLandingControl.reachable(context.player(),feet)) continue;
            var candidate = find(context,feet);
            if (candidate == null) continue;
            double cost = (candidate.plan().kind() == LandingAssistPlan.Kind.WATER ? 0 : 100)
                    + context.player().position().distanceToSqr(Vec3.atBottomCenterOf(feet));
            if (cost < score) { best = candidate; score = cost; }
        }
        return best;
    }
    private static BlockPos nearbyGround(LocalPlayerContext context, BlockPos column) {
        var level = context.level(); double top = Math.min(context.player().getY(),level.getMaxBuildHeight());
        if (!level.isLoaded(column)) return null;
        var hit = level.clip(new ClipContext(new Vec3(column.getX()+.5,top,column.getZ()+.5),
                new Vec3(column.getX()+.5,level.getMinBuildHeight(),column.getZ()+.5),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,context.player()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().getX() == column.getX()
                && hit.getBlockPos().getZ() == column.getZ() && level.isLoaded(hit.getBlockPos())
                && new AABB(hit.getBlockPos()).inflate(.00001).contains(hit.getLocation())
                ? hit.getBlockPos().above() : null;
    }
    /** 持续下落时接管自救，但保留已选支撑点和转向。 */
    // 紧急救援使用落地辅助许可，并允许检查是否能补到材料；候选还要通过身体空间和干草减伤后能否生存的检查。
    public static LandingAssistSession find(LocalPlayerContext context, BlockPos feet) {
        if (!context.level().isLoaded(feet) || !context.level().isLoaded(feet.below())) return null;
        var inventory = LandingAssistPlan.InventorySnapshot.capture(context.player(), TerrainPermit.LANDING_ONLY,
                context.level().dimensionType().ultraWarm());
        var candidates = new ArrayList<LandingAssistPlan>();
        var rejected = new LinkedHashMap<String,String>();
        var player = context.player();
        // 每次新的实际坠落都有独立的有界材料获取机会；先前路线搜索失败不能因玩家处境改变而继续抑制紧急取物。
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
            // 坠落已经发生，因此不能保证任意起跳阶段都存在有效的原生水桶使用 tick。会话会逐 tick 检查真实射线和距离；
            // 对计划中的起跳仍使用更严格的准入条件。
            candidates.add(plan);
        }
        return candidates.isEmpty() ? null : LandingAssistSession.automatic(candidates, true).rejectedCandidates(rejected);
    }
    /** 通过同一原生身体租约，应用共享会话的瞄准和坠落转向。 */
    public static void tick(LocalPlayerContext context, LandingAssistSession session) {
        var player = context.player();
        session.tick(context);
        if(session.movementOverride()!=null) {
            context.body().applyMovement(session.movementOverride(),context.tickRevision()); return;
        }
        boolean sneak = session.wantsSneak(context);
        boolean hold = session.holdingForRecovery(context) || player.onGround();
        context.body().applySteering(cameraYaw -> {
            if (hold)
                return new BodyControlPort.Movement(0, 0, false, sneak, false);
            return AirLandingControl.movement(player,session.plan().feet(),cameraYaw,sneak);
        }, player.getYRot(), context.tickRevision());
    }
    /** 检查身体中心和四角，并保留原本最近原生碰撞体探测逻辑。 */
    // 从身体中心和四个角向下读碰撞，选最先可能接触的地面；不能只看中心射线而漏掉擦到的台阶或边缘。
    private static BlockPos groundBelow(LocalPlayer player) {
        var level = player.level();
        double top = Math.min(player.getY(), level.getMaxBuildHeight());
        double bottom = level.getMinBuildHeight();
        double span = top - bottom;
        if (!Double.isFinite(span) || span <= 0) return null;
        var box = player.getBoundingBox();
        // 即使模组把玩家放到远超建筑高度的区域，也要跳过世界范围外的空气。
        // 竖直射线始终限制在同一已加载柱列，并覆盖其有效建筑高度范围。
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
            // Sable 原生 clip 可能返回地块存储坐标；在配对的世界射线验证通过前，不能将其位置或方块格用作世界着陆点。
            if (!Double.isFinite(drop) || drop < -1.0E-5 || drop > span + 1.0E-5
                    || Math.abs(hit.getLocation().x - from.x) > 1.0E-5
                    || Math.abs(hit.getLocation().z - from.z) > 1.0E-5
                    || !new AABB(hit.getBlockPos()).inflate(1.0E-5).contains(hit.getLocation()))
                return unsupportedSupport();
            if (!level.isLoaded(hit.getBlockPos())) return null;
            if (drop < nearest) { nearest = drop; best = hit.getBlockPos(); }
        }
        return best;
    }
    private static BlockPos unsupportedSupport() {
        LandingAssistPolicy.report(Map.of("phase", "unsupported physical-structure or unverified world-space landing ray",
                "support_state", "unsupported", "submitted", false));
        return null;
    }
}
