// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 持续使用的离线场景：确认开始后才进入按住；按住期间逐刻续期投影；
 * 确认做成、按住到期、归属不再成立三种收尾都先松手再如实下结论；
 * 对着一格方块按住时先瞄准再点，被原版松开就重新瞄准再点，目标格中途空了按目标没了收场；
 * 任务收尾时投影一并交还，不留一直按着的使用键。
 */
class SustainedUseTest {

    @BeforeAll
    static void bootMinecraft() {
        // 目标格的"变没变"要用真实方块状态表达，先把原版注册表备好。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 对格按住的目标格；不在 -Z 轴上，转头替身第一刻不会误判成已经对准。 */
    private static final BlockPos TARGET_CELL = new BlockPos(2, 64, -2);

    private static final InteractionConfirmation NEVER_DONE =
            c -> InteractionConfirmation.Verdict.PENDING;

    private record Rig(ScriptedInteractionSender sender, InteractionTestFakes.FakeContext context,
                       InteractionTestFakes.FakeScene scene, InteractionTestFakes.RecordingProjection projection) {

        static Rig create() {
            ScriptedInteractionSender sender = new ScriptedInteractionSender();
            InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
            return new Rig(sender, context, new InteractionTestFakes.FakeScene(context),
                    new InteractionTestFakes.RecordingProjection());
        }

        SustainedUse action(InteractionConfirmation done, int maxHoldTicks) {
            return new SustainedUse(InteractionHand.MAIN_HAND, done, projection, maxHoldTicks,
                    ignored -> scene);
        }

        SustainedUse aimedAction(InteractionConfirmation done, int maxHoldTicks) {
            return new SustainedUse(InteractionHand.MAIN_HAND, done, projection, maxHoldTicks,
                    ignored -> scene, TARGET_CELL);
        }
    }

    private static BlockHitResult hitAt(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    /** 提交持用，脚本确认"使用已开始"，推进到进入按住阶段。 */
    private static void reachHolding(Rig rig, SustainedUse action) {
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running, "提交那刻在等开始确认");
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running running && running.progressed(),
                "开始确认生效进入按住");
        rig.context.advance();
    }

    /** 对格按住：第一刻转头，第二刻转到位后右键，开始确认通过后进入按住。 */
    private static void reachAimedHolding(Rig rig, SustainedUse action) {
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running, "第一刻在转头");
        assertTrue(rig.sender.submissions.isEmpty(), "镜头没到位不能出手");
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running running && running.progressed(),
                "转到位后右键开始按住");
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running running && running.progressed(),
                "开始确认通过进入按住");
        rig.context.advance();
        rig.context.using = true;
    }

    /** 按住被原版松开后重新接上：等动作重新点上（最多三刻）并确认开始。 */
    private static void resumeAfterBreak(Rig rig, SustainedUse action) {
        int clicksBefore = rig.sender.submissions.size();
        for (int t = 0; t < 3 && rig.sender.submissions.size() == clicksBefore; t++) {
            rig.context.advance();
            assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running,
                    "松开后先重新瞄准再点");
        }
        assertEquals(clicksBefore + 1, rig.sender.submissions.size(), "重新瞄准后要重新点上");
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running);
        rig.context.using = true;
        rig.context.advance();
    }

    /** 照"可疑方块刷完"的真实判据写测试的做成条件：那格变成不是空气的别的方块。 */
    private static InteractionConfirmation revealDone(InteractionTestFakes.FakeScene scene) {
        return context -> {
            var live = scene.blocks.get(TARGET_CELL);
            return live != null && !live.isAir()
                    ? InteractionConfirmation.Verdict.APPLIED
                    : InteractionConfirmation.Verdict.PENDING;
        };
    }

    @Test
    void itHoldsTicksAwayAndRenewsTheProjectionEveryTick() {
        Rig rig = Rig.create();
        SustainedUse action = rig.action(NEVER_DONE, 0);
        reachHolding(rig, action);
        int renewsAfterStart = rig.projection.renewCalls;
        // 按住阶段每刻续投影、每刻都是真实进展，确认没做成就不结束。
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Running running && running.progressed());
        assertEquals(renewsAfterStart + 1, rig.projection.renewCalls, "按住的每一刻都要续投影");
        assertEquals(0, rig.projection.releaseCalls, "没到收尾不该松手");
        // 按住期间不在等确认记录，打断规则可以按"正常干活"处理：被打断时投影自然过期，使用停住。
        assertEquals(Interruptibility.WORKING, action.interruptibility());
    }

    @Test
    void aConfirmedFinishReleasesThenReportsApplied() {
        Rig rig = Rig.create();
        // 确认条件按测试开关翻转：翻真那一刻松手，松开确认后再如实记生效。
        class Done implements InteractionConfirmation {
            boolean reached;
            @Override public Verdict observe(PlayerContext context) {
                return reached ? Verdict.APPLIED : Verdict.PENDING;
            }
        }
        Done done = new Done();
        SustainedUse action = rig.action(done, 0);
        reachHolding(rig, action);
        done.reached = true;
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running, "松手请求当刻在等确认");
        assertEquals(1, rig.sender.releaseCalls);
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Done);
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.APPLIED, action.result().verdict());
    }

    @Test
    void holdingPastItsLimitReleasesAndReportsUnconfirmed() {
        Rig rig = Rig.create();
        SustainedUse action = rig.action(NEVER_DONE, 2);
        reachHolding(rig, action);
        // 按住两刻到上限：松手；松开后确认条件始终没达成 → 没能确认，不能算成功。
        action.tick(rig.context.asTickContext());
        rig.context.advance();
        action.tick(rig.context.asTickContext());
        rig.context.advance();
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed, "到期松手但确认没达成不该算成功");
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.UNCONFIRMED, action.result().verdict());
        assertEquals(1, rig.sender.releaseCalls);
    }

    @Test
    void lostOwnershipReleasesAndReportsUnexpected() {
        Rig rig = Rig.create();
        SustainedUse action = rig.action(NEVER_DONE, 0);
        reachHolding(rig, action);
        // 这次使用不再归本动作管：立刻松手并如实报出乎预料。
        rig.sender.ownsUse = false;
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed);
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.UNEXPECTED, action.result().verdict());
        assertEquals(1, rig.projection.releaseCalls);
    }

    @Test
    void aUseThatNeverStartsIsReportedAsRefused() {
        Rig rig = Rig.create();
        SustainedUse action = rig.action(NEVER_DONE, 0);
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running);
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_NOT_APPLIED;
        rig.context.advance();
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed failed
                && "REFUSED_BY_GAME".equals(failed.problem().kind().name()), "使用没开始按没生效上报");
        assertEquals(InteractionVerdict.NOT_APPLIED, action.result().verdict());
        assertEquals(0, rig.sender.releaseCalls, "没开始的使用不需要松手");
    }

    @Test
    void closingDuringTheHoldReleasesTheProjectionAndStopsUsing() {
        Rig rig = Rig.create();
        SustainedUse action = rig.action(NEVER_DONE, 0);
        reachHolding(rig, action);
        action.close();
        assertEquals(1, rig.projection.releaseCalls, "收尾必须交还投影");
        assertEquals(1, rig.sender.releaseCalls, "收尾必须原生松手");
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.UNCONFIRMED, action.result().verdict());
    }

    @Test
    void aimedHoldTurnsFirstAndClicksOnlyTheVerifiedFace() {
        Rig rig = Rig.create();
        rig.scene.ray = hitAt(TARGET_CELL);
        SustainedUse action = rig.aimedAction(NEVER_DONE, 0);
        // 第一刻只转头，不出手。
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running);
        assertEquals(1, rig.context.input.lookRequests.size(), "每刻朝着目标转头");
        assertTrue(rig.sender.submissions.isEmpty(), "镜头没到位不能出手");
        // 第二刻镜头到位、准星点在目标格上：右键这一格开始按住。
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running running && running.progressed());
        assertEquals(1, rig.sender.submissions.size());
        ScriptedInteractionSender.Submission submitted = rig.sender.submissions.getFirst();
        assertEquals(PendingInteraction.Kind.USE_BLOCK, submitted.kind(), "对格按住走右键方块的入口");
        assertEquals(TARGET_CELL, submitted.hit().getBlockPos(), "右键的是核对过的那一格");
    }

    @Test
    void aimedHoldFinishesWhenTheCellTurnsIntoAPlainBlock() {
        Rig rig = Rig.create();
        rig.scene.ray = hitAt(TARGET_CELL);
        SustainedUse action = rig.aimedAction(revealDone(rig.scene), 0);
        reachAimedHolding(rig, action);
        // 刷完：可疑方块整格换成普通沙子 → 松手 → 确认做成。
        rig.scene.blocks.put(TARGET_CELL, Blocks.SAND.defaultBlockState());
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running, "松手请求当刻在等确认");
        assertEquals(1, rig.sender.releaseCalls, "做成后要原生松手");
        rig.sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Done);
        assertEquals(InteractionVerdict.APPLIED, action.result().verdict());
    }

    @Test
    void aBrokenHoldReaimsAndClicksAgain() {
        Rig rig = Rig.create();
        rig.scene.ray = hitAt(TARGET_CELL);
        SustainedUse action = rig.aimedAction(revealDone(rig.scene), 0);
        reachAimedHolding(rig, action);
        int clicksAfterStart = rig.sender.submissions.size();
        // 原版松开（视线移开、使用到期）：还没做成 → 重新瞄准再点，接着按。
        rig.context.using = false;
        rig.context.advance();
        assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running, "松开那刻先重新瞄准");
        resumeAfterBreak(rig, action);
        assertEquals(clicksAfterStart + 1, rig.sender.submissions.size(), "被松开后重新点上");
        assertEquals(0, rig.projection.releaseCalls, "接着按不算收尾，不交还投影");
    }

    @Test
    void repeatedBreaksGiveUpAfterTheRestartBudget() {
        Rig rig = Rig.create();
        rig.scene.ray = hitAt(TARGET_CELL);
        SustainedUse action = rig.aimedAction(revealDone(rig.scene), 0);
        reachAimedHolding(rig, action);
        // 反复被松开：接上四次之后不再续按，如实按没能做成收场。
        for (int round = 0; round < 4; round++) {
            rig.context.using = false;
            rig.context.advance();
            assertTrue(action.tick(rig.context.asTickContext()) instanceof ActionStatus.Running,
                    "第 " + (round + 1) + " 次松开还接着按");
            resumeAfterBreak(rig, action);
        }
        rig.context.using = false;
        rig.context.advance();
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed failed && "STUCK".equals(failed.problem().kind().name()),
                "反复接不上按卡住收场");
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.UNCONFIRMED, action.result().verdict());
        assertEquals(1, rig.projection.releaseCalls, "收场要交还投影");
    }

    @Test
    void aCellThatVanishesMidHoldEndsAsTargetGone() {
        Rig rig = Rig.create();
        rig.scene.ray = hitAt(TARGET_CELL);
        SustainedUse action = rig.aimedAction(revealDone(rig.scene), 0);
        reachAimedHolding(rig, action);
        // 按住中途目标格被人挖空：松手并按目标没了收场，不算刷完。
        rig.scene.blocks.put(TARGET_CELL, Blocks.AIR.defaultBlockState());
        rig.context.advance();
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed failed
                && "TARGET_GONE".equals(failed.problem().kind().name()), "目标格空了按目标没了收场");
        assertEquals(1, rig.projection.releaseCalls, "目标没了要交还投影");
        assertNull(action.result(), "目标没了不算一次交互结论，由任务决定接下来怎么办");
    }

    @Test
    void aCellThatIsAlreadyGoneFailsBeforeClicking() {
        Rig rig = Rig.create();
        rig.scene.blocks.put(TARGET_CELL, Blocks.AIR.defaultBlockState());
        SustainedUse action = rig.aimedAction(revealDone(rig.scene), 0);
        // 目标格开局就是空气（被挖掉、塌走）：不出手，按目标没了收场。
        ActionStatus status = action.tick(rig.context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed failed
                && "TARGET_GONE".equals(failed.problem().kind().name()));
        assertTrue(rig.sender.submissions.isEmpty(), "目标没了不出手");
    }
}
