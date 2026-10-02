package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.combat.MeleeStanceRecovery;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.task.build.BuildEdgeMotion;
import org.maiwithu.maicraft.core.task.build.BuildEdgeRecovery;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.check;
import static org.maiwithu.maicraft.client.actor.ActorControlTestHarness.field;

/** 复用原生身体，验证观测不足不被写成低顶或支撑变化，释放请求也不冒充原生姿态确认。 */
public final class BuildMotionEvidenceTest {
    public static void main(String[] args) throws Exception {
        for (String state : List.of("partial", "unknown")) incompleteObservation(state);
        differentPoseObservationsKeepTheirEvidence();
        releaseWithoutContextCannotClaimInputSubmission(false);
        releaseWithoutContextCannotClaimInputSubmission(true);
        System.out.println("BuildMotionEvidenceTest: 物理观测、历史快照和释放请求边界通过");
    }

    private static void incompleteObservation(String state) throws Exception {
        try (var f = new BuildEdgeMotionNativeTest.Fixture()) {
            LocalPlayer player = f.player;
            var recovery = new MeleeStanceRecovery(); recovery.begin(player);
            var motion = (BuildEdgeMotion) field(MeleeStanceRecovery.class, "alignment").get(recovery);
            observe(motion, (level, at) -> new PhysicalObstacleSnapshot(List.of(), 0, 0, state));
            check(!recovery.tick(player, NavGoal.exact(player.blockPosition()), false), "观测不足仍拒绝微调，不改变原生通行判据");
            var evidence = recovery.evidence(); var release = (Map<?, ?>) evidence.get("release");
            check("edge_geometry_observation_unavailable".equals(evidence.get("failure"))
                    && "geometry_observation_unavailable".equals(evidence.get("posture_reason")), "未知物理几何不能归因为顶棚低或支撑已变");
            check(state.equals(((Map<?, ?>) evidence.get("geometry_check")).get("state"))
                    && state.equals(((Map<?, ?>) evidence.get("standing_geometry_check")).get("state")), "保留两次姿态检查的原始观察状态");
            check("before_release".equals(evidence.get("snapshot_phase")) && Boolean.FALSE.equals(evidence.get("released"))
                    && Boolean.TRUE.equals(release.get("controller_retired")) && "submitted".equals(release.get("stop_input_request")),
                    "失败现场明确是释放前快照，后续停止请求有独立记录");
            f.flush(); check(!player.input.shiftKeyDown && player.input.forwardImpulse == 0 && player.input.leftImpulse == 0,
                    "原生输入确实停止，但回执只声称发出了请求，不伪造姿态确认");
            check("not_observed".equals(release.get("native_input_release")), "不把控制器退休冒充原生输入确认");
            var retry = BuildEdgeRecovery.class.getDeclaredMethod("recoverable", String.class); retry.setAccessible(true);
            check((Boolean) retry.invoke(null, evidence.get("failure")), "细化标签后保留既有恢复资格");
            recovery.stop(player); check(evidence.equals(recovery.evidence()), "重复收尾不抹掉原失败观察");
        }
    }

    private static void differentPoseObservationsKeepTheirEvidence() throws Exception {
        try (var f = new BuildEdgeMotionNativeTest.Fixture()) {
            LocalPlayer player = f.player;
            var motion = BuildEdgeMotion.alignAt(player.position(), new LongOpenHashSet(), at -> true);
            var reads = new AtomicInteger();
            observe(motion, (level, at) -> reads.getAndIncrement() == 0
                    ? new PhysicalObstacleSnapshot(List.of(), 0, 0, "partial") : PhysicalObstacleSnapshot.EMPTY);
            check(motion.tick(player) == BuildEdgeMotion.Status.RUNNING && motion.requiresSneak(), "潜行检查成功时保留原有姿态选择流程");
            check("standing_observation_unavailable".equals(motion.postureReason()), "站立未知而潜行可行，仍不足以证明低顶棚");
            motion.release(player);
        }
    }

    private static void releaseWithoutContextCannotClaimInputSubmission(boolean previousRelease) throws Exception {
        try (var f = new BuildEdgeMotionNativeTest.Fixture()) {
            LocalPlayer player = f.player;
            var motion = BuildEdgeMotion.alignAt(player.position(), new LongOpenHashSet(), at -> true);
            motion.tick(player);
            if (previousRelease) {
                motion.release(player);
                check(motion.releaseEvidence().containsKey("request_tick"), "先证明上一轮确实提交了停止请求");
            }
            var contextField = field(ClientActorBoundary.class, "activeContext");
            var actor = ClientRuntime.actor(); Object saved = contextField.get(actor);
            try {
                contextField.set(actor, null); motion.release(player);
                var release = motion.releaseEvidence();
                check(Boolean.TRUE.equals(release.get("controller_retired"))
                        && "not_submitted_no_owned_context".equals(release.get("stop_input_request")) && !release.containsKey("request_tick"),
                        "没有当前控制上下文时只记录控制器停止续输入，不声称原生停止请求已发送");
            } finally { contextField.set(actor, saved); }
        }
    }

    private static void observe(BuildEdgeMotion motion, BiFunction<ClientLevel, Vec3, PhysicalObstacleSnapshot> observation) throws Exception {
        // 只注入只读物理事实；失败、姿态请求和停止输入均由同一个生产控制器执行。
        field(BuildEdgeMotion.class, "physicalObservation").set(motion, observation);
    }
}
