package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.transport.TransportNavigator;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 离线回放 R21 的近战距离循环，分别核对寻位目标和原生出刀边界。 */
public final class CombatStanceReplayTest {
    private static final double[] LOGGED_DISTANCE_TRACE = {
            3.4, 3.5, 3.6, 3.7, 3.6, 3.5, 3.4,
            3.6, 3.8, 3.9, 4.0, 3.9, 3.7, 3.6, 3.5,
            3.7, 3.8, 3.9, 4.0, 4.1, 4.2, 4.1, 3.9, 3.8, 3.7,
            3.9, 4.0, 4.1, 4.2, 4.1, 3.9, 3.8, 3.7,
            3.9, 4.0, 4.1, 4.2, 4.1, 3.9, 3.8, 3.7,
            3.9, 4.0, 4.1, 4.2, 4.0, 3.8, 3.7, 3.6, 3.5,
            3.8, 3.9, 4.0, 4.1, 4.2, 4.1, 3.9, 3.8, 3.7
    };

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        replayLoggedRangeTrace();
        reproduceCellCenterArrivalOutsideNativeReach();
        verifyNativeStrikeAfterEnteringAReachableStance();
        System.out.println("CombatStanceReplayTest: out-of-range trace and reachable stance passed");
    }

    // R21 的每个距离样本都在剑的射程之外；同时检查 PlayerNav 到 EmbeddedBaritoneNavigator 的实时目标供应器仍交付可达站位。
    private static void replayLoggedRangeTrace() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var foe = f.mob(62, 4.0);
            f.hit(foe, foe);
            var task = new AttackCompanionTask(f.h.player,
                    new AttackTaskRecord("r21-stance-replay", 1000, List.of(62), false));
            task.start(f.h.player);
            ActorControlTestHarness.field(AttackCompanionTask.class, "target").set(task, foe);

            PlayerNav nav = PlayerNav.trackGoal(f.h.player,
                    () -> currentStance(task), 1.2, () -> false);
            TransportNavigator transport = (TransportNavigator) ActorControlTestHarness
                    .field(PlayerNav.class, "navigator").get(nav);
            EmbeddedBaritoneNavigator embedded = (EmbeddedBaritoneNavigator) ActorControlTestHarness
                    .field(TransportNavigator.class, "ground").get(transport);
            @SuppressWarnings("unchecked")
            Supplier<GoalCompiler.Compiled> liveGoal = (Supplier<GoalCompiler.Compiled>) ActorControlTestHarness
                    .field(EmbeddedBaritoneNavigator.class, "compiledSupplier").get(embedded);
            CombatThreatsTest.check(ActorControlTestHarness
                            .field(EmbeddedBaritoneNavigator.class, "trackingGoal").getBoolean(embedded),
                    "tracked PlayerNav must keep passing the live melee stance to embedded navigation");

            BlockPos firstSafeCell = null;
            GoalCompiler.CompiledFingerprint previousFingerprint = null;
            int refreshedGoals = 0;
            for (double distance : LOGGED_DISTANCE_TRACE) {
                // 固定玩家并按日志距离移动目标，复现双方距离反复越过近战门槛但始终没有进入射程的观测。
                placeEntity(foe, new Vec3(
                        f.h.player.getX() + distance, f.h.player.getY(), f.h.player.getZ()));
                var field = MobDefenseDamageTest.survey(task);
                var observed = field.byId(foe.getId());
                CombatThreatsTest.check(observed != null && Math.abs(observed.distance() - distance) < 0.01,
                        "the replay fixture must present the logged center distance to combat planning");
                CombatThreatsTest.check(Math.abs(field.meleeReach() - 3.3) < 0.01,
                        "the vanilla three-block reach plus half a zombie width gives the logged 3.30 outer edge");

                GoalCompiler.Compiled compiled = liveGoal.get();
                NavGoal stance = compiled.goal();
                BlockPos currentFeet = PlayerNav.playerFeet(f.h.player);
                CombatThreatsTest.check(!stance.isAt(currentFeet) && stance.heuristic(currentFeet) > 0.0,
                        "a logged 3.4–4.2 center distance must keep the current feet outside the melee stance band");
                if (previousFingerprint != null && !previousFingerprint.equals(compiled.semanticFingerprint())) {
                    refreshedGoals++;
                }
                previousFingerprint = compiled.semanticFingerprint();

                BlockPos safeCell = nearestSupportedStance(f, stance, currentFeet);
                CombatThreatsTest.check(safeCell != null,
                        "the open flat replay map must contain a supported cell inside the safe melee stance band");
                if (firstSafeCell == null) firstSafeCell = safeCell;

                // 每刻沿用 AttackCompanionTask 的真实攻击判据；超出 3.30 格时不能提交近战动作。
                tickWeapon(task, field);
                CombatThreatsTest.check(MobDefenseDamageTest.field(task, "meleeAction") == null
                                && f.h.mode.attacks == 0,
                        "the weapon layer must not send a swing while the replayed target remains beyond reach");
            }

            CombatThreatsTest.check(refreshedGoals == LOGGED_DISTANCE_TRACE.length - 1,
                    "EmbeddedBaritoneNavigator must receive each precise moving-target stance update");
            CombatThreatsTest.check(firstSafeCell != null,
                    "the replay trace must record a concrete safe cell for the flat-world control case");
            nav.abandon();
            task.result(TaskState.CANCELLED);
        }
    }

    // 玩家真实坐标偏离格心时，验证寻路可接受格心已在环内，而出刀仍按实体真实距离判定。
    private static void reproduceCellCenterArrivalOutsideNativeReach() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            // 日志最低值为 3.4；玩家在本格西侧时，格心会比真实身体靠近目标 0.4 格。
            f.h.position(new Vec3(1.1, 1, 5.5));
            var foe = f.mob(62, 4.5);
            placeEntity(foe, new Vec3(4.5, 1, 5.5));
            f.hit(foe, foe);
            var task = new AttackCompanionTask(f.h.player,
                    new AttackTaskRecord("r21-subcell-boundary", 1000, List.of(62), false));
            task.start(f.h.player);
            ActorControlTestHarness.field(AttackCompanionTask.class, "target").set(task, foe);
            var battlefield = MobDefenseDamageTest.survey(task);
            NavGoal stance = currentStance(task);
            BlockPos currentFeet = PlayerNav.playerFeet(f.h.player);
            double navigationDistance = Vec3.atBottomCenterOf(currentFeet).distanceTo(foe.position());
            double bodyDistance = f.h.player.distanceTo(foe);

            CombatThreatsTest.check(currentFeet.equals(new BlockPos(1, 1, 5)),
                    "the replay must keep the player's real feet in the intended grid cell");
            CombatThreatsTest.check(Math.abs(navigationDistance - 3.0) < 0.01
                            && Math.abs(bodyDistance - 3.4) < 0.01
                            && Math.abs(battlefield.meleeReach() - 3.3) < 0.01,
                    "the observed 3.4 center distance can differ from the same cell's 3.0 grid-center distance");
            CombatThreatsTest.check(stance.isAt(currentFeet),
                    "the real melee stance goal accepts the grid center even while the body is farther away");

            PlayerNav nav = PlayerNav.trackGoal(f.h.player,
                    () -> currentStance(task), 1.2, () -> false);
            EmbeddedBaritoneNavigator embedded = embeddedOf(nav);
            GoalCompiler.Compiled compiled = liveGoalOf(embedded).get();
            ActorControlTestHarness.field(EmbeddedBaritoneNavigator.class, "goal")
                    .set(embedded, compiled.goal());
            var memberCheck = EmbeddedBaritoneNavigator.class.getDeclaredMethod("hasStableSearchMembership");
            memberCheck.setAccessible(true);
            CombatThreatsTest.check((boolean) memberCheck.invoke(embedded),
                    "the embedded navigator's arrival membership accepts the current feet and path start");

            tickWeapon(task, battlefield);
            CombatThreatsTest.check(MobDefenseDamageTest.field(task, "meleeAction") == null
                            && f.h.mode.attacks == 0,
                    "the native weapon layer still refuses the swing at a real 3.4-block center distance");
            nav.abandon();
            task.result(TaskState.CANCELLED);
        }
    }

    // 走到开阔地上被站位目标接受的格子后，使用同一任务的原生交互验证其确实能提交近战点击。
    private static void verifyNativeStrikeAfterEnteringAReachableStance() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var foe = f.mob(62, 3.9);
            f.hit(foe, foe);
            var task = new AttackCompanionTask(f.h.player,
                    new AttackTaskRecord("r21-stance-replay-control", 1000, List.of(62), false));
            task.start(f.h.player);
            ActorControlTestHarness.field(AttackCompanionTask.class, "target").set(task, foe);

            MobDefenseDamageTest.survey(task);
            NavGoal stance = currentStance(task);
            BlockPos safeCell = nearestSupportedStance(f, stance, PlayerNav.playerFeet(f.h.player));
            CombatThreatsTest.check(safeCell != null, "the flat control scene must provide a legal stance cell");
            f.h.position(Vec3.atBottomCenterOf(safeCell));
            CombatThreatsTest.check(stance.isAt(PlayerNav.playerFeet(f.h.player)),
                    "moving onto the chosen cell must satisfy the live safe-distance goal");
            CombatThreatsTest.check(f.h.player.distanceTo(foe) <= 3.3,
                    "a supported cell accepted by the 3.30 outer edge must also enter the estimated attack range");
            faceEntity(f.h.player, foe);

            var field = MobDefenseDamageTest.survey(task);
            tickWeapon(task, field);
            CombatThreatsTest.check(MobDefenseDamageTest.field(task, "meleeAction") != null,
                    "inside the safe stance band, the combat task must queue its native melee interaction");
            f.h.nextTick();
            tickWeapon(task, MobDefenseDamageTest.survey(task));
            CombatThreatsTest.check(f.h.mode.attacks == 1,
                    "the unobstructed, correctly aimed control case must reach the native attack port");
            task.result(TaskState.CANCELLED);
        }
    }

    // 保留目标的实时位置与威胁场，复用 AttackCompanionTask 实际生成的近战环而不另写几何替身。
    private static NavGoal currentStance(AttackCompanionTask task) {
        try {
            return (NavGoal) MobDefenseDamageTest.invoke(task, "standoffGoal");
        } catch (Exception failure) {
            throw new IllegalStateException("读取战斗任务实时站位失败", failure);
        }
    }

    // 仅在测试夹具已铺设石地板的区域寻找目标允许的脚位，避免把悬空网格点当成成功的寻位出口。
    private static BlockPos nearestSupportedStance(CombatThreatsTest.Fixture f, NavGoal goal,
                                                    BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = Math.max(0, origin.getX() - 6); x <= Math.min(15, origin.getX() + 6); x++) {
            for (int z = Math.max(0, origin.getZ() - 6); z <= Math.min(15, origin.getZ() + 6); z++) {
                var feet = new BlockPos(x, 1, z);
                if (!f.h.level.getBlockState(feet.below()).isSolid() || !goal.isAt(feet)) continue;
                double distance = Vec3.atBottomCenterOf(feet).distanceToSqr(f.h.player.position());
                if (distance < bestDistance) {
                    best = feet;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    // 读取 PlayerNav 创建的地面导航器，确保回放检查的是同一条通往 EmbeddedBaritoneNavigator 的路径。
    private static EmbeddedBaritoneNavigator embeddedOf(PlayerNav nav) throws Exception {
        TransportNavigator transport = (TransportNavigator) ActorControlTestHarness
                .field(PlayerNav.class, "navigator").get(nav);
        return (EmbeddedBaritoneNavigator) ActorControlTestHarness
                .field(TransportNavigator.class, "ground").get(transport);
    }

    // 取得嵌入导航器每刻读取的目标供应器，检查它收到的是任务生成的实时战斗站位。
    @SuppressWarnings("unchecked")
    private static Supplier<GoalCompiler.Compiled> liveGoalOf(EmbeddedBaritoneNavigator navigator)
            throws Exception {
        return (Supplier<GoalCompiler.Compiled>) ActorControlTestHarness
                .field(EmbeddedBaritoneNavigator.class, "compiledSupplier").get(navigator);
    }

    // 逐刻调用真实攻击动作，保留冷却、授权、目标宽度和原生动作口组成的完整出刀判据。
    private static void tickWeapon(AttackCompanionTask task, Battlefield field) throws Exception {
        var tickWeapon = AttackCompanionTask.class.getDeclaredMethod("tickWeapon", Battlefield.class);
        tickWeapon.setAccessible(true);
        tickWeapon.invoke(task, field);
    }

    // 测试生物跳过了原版构造器，移动时同步脚格与碰撞箱，避免依赖未初始化的区块缓存字段。
    private static void placeEntity(Entity entity, Vec3 position) throws Exception {
        double halfWidth = entity.getBbWidth() / 2.0;
        ActorControlTestHarness.field(Entity.class, "position").set(entity, position);
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(entity, BlockPos.containing(position));
        ActorControlTestHarness.field(Entity.class, "bb").set(entity,
                new AABB(position.x - halfWidth, position.y, position.z - halfWidth,
                        position.x + halfWidth, position.y + entity.getBbHeight(), position.z + halfWidth));
    }

    // 控制夹具让角色正对实体碰撞箱中心，隔离站位距离与原生视线判定的影响。
    private static void faceEntity(LocalPlayer player, Entity target) {
        Vec3 aim = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance())));
    }
}
