// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;

/**
 * 瞄准交互动作的离线场景：镜头没转到位不出手；命中了别的换瞄准点再试；
 * 提交时冻结现场；确认的四种结论各自如实上报；任务收尾时未确认的提交按不确定交还。
 */
class AimAndInteractTest {

    private static final BlockPos TARGET = new BlockPos(2, 64, 2);

    private static BlockHitResult hitAt(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static AimAndInteract blockAction(InteractionTestFakes.FakeScene scene, InteractionConfirmation confirmation) {
        return new AimAndInteract(new InteractionTarget.BlockTarget(TARGET), confirmation, context -> scene);
    }

    /** 推进到动作提交为止（转头替身让第二刻就到位）；返回提交那刻的状态。 */
    private static ActionStatus runUntilSubmitted(AimAndInteract action, InteractionTestFakes.FakeContext context) {
        ActionStatus status = action.tick(context.asTickContext());
        context.advance();
        status = action.tick(context.asTickContext());
        context.advance();
        return status;
    }

    @Test
    void itDoesNotSubmitBeforeTheCameraActuallySettles() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.APPLIED);
        // 第一刻只发出转头请求：发出转头不算转到位，不能出手。
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Running);
        assertEquals(1, context.input.lookRequests.size(), "每刻都该朝着目标转头");
        assertTrue(sender.submissions.isEmpty(), "镜头没到位不能出手");
    }

    @Test
    void aSettledAimThatHitsTheTargetSubmitsAndConfirms() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        ActionStatus submitted = runUntilSubmitted(action, context);
        assertTrue(submitted instanceof ActionStatus.Running running && running.progressed(), "提交本刻算真实进展");
        assertEquals(1, sender.submissions.size());
        assertEquals(TARGET, sender.submissions.getFirst().hit().getBlockPos(), "提交的是核对过的那次命中");
        assertEquals(20, sender.submissions.getFirst().timeoutTicks());

        // 确认条件还每刻在等：留在原地等，不动第二次。
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Running);
        assertNull(action.result());

        // 确认生效：动作完成，结论带上现场。
        sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
        context.advance();
        assertTrue(action.tick(context.asTickContext()) instanceof ActionStatus.Done);
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.APPLIED, action.result().verdict());
        assertTrue(action.result().scene().contains("射线命中方块"), "现场要说明提交时射线命中了谁");
        assertEquals(Interruptibility.WORKING, action.interruptibility());
    }

    @Test
    void aHitOnSomethingElseRetriesThenReportsUnreachable() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        // 射线永远命中别的格子：中心加六个面共七个瞄准点都点不到目标。
        scene.ray = hitAt(new BlockPos(3, 64, 2));
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.APPLIED);
        ActionStatus status = null;
        for (int i = 0; i < 40; i++) {
            status = action.tick(context.asTickContext());
            context.advance();
            if (status instanceof ActionStatus.Failed) break;
        }
        assertTrue(status instanceof ActionStatus.Failed, "瞄准点用尽后必须按到不了收尾");
        assertTrue(failedMessage(status).contains("需要换站位"), "换位是站位的事，问题要指向换站位");
        assertTrue(failedMessage(status).contains("方块 3"), "现场要写明射线命中了谁");
        assertEquals("UNREACHABLE", failedKind(status));
        assertTrue(sender.submissions.isEmpty(), "没核对过命中就绝不出手");
    }

    @Test
    void verdictsAreReportedFaithfully() {
        assertVerdict(PendingInteraction.Status.CONFIRMED_NOT_APPLIED, InteractionVerdict.NOT_APPLIED);
        assertVerdict(PendingInteraction.Status.DIVERGED, InteractionVerdict.UNEXPECTED);
        assertVerdict(PendingInteraction.Status.UNCERTAIN, InteractionVerdict.UNCONFIRMED);
    }

    private static void assertVerdict(PendingInteraction.Status scripted, InteractionVerdict expected) {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        runUntilSubmitted(action, context);
        sender.nextStatus = scripted;
        ActionStatus status = action.tick(context.asTickContext());
        assertTrue(status instanceof ActionStatus.Failed, scripted + " 不该算成功");
        assertNotNull(action.result());
        assertEquals(expected, action.result().verdict());
        if (expected == InteractionVerdict.NOT_APPLIED) {
            assertEquals("REFUSED_BY_GAME", failedKind(status), "没生效按被游戏拒绝上报");
        }
        if (expected == InteractionVerdict.UNCONFIRMED) {
            assertEquals(Interruptibility.WORKING, action.interruptibility(), "已经收尾的动作不再挡打断");
        }
    }

    @Test
    void closingBeforeConfirmationHandsBackTheUncertainSubmission() {
        ScriptedInteractionSender sender = new ScriptedInteractionSender();
        InteractionTestFakes.FakeContext context = new InteractionTestFakes.FakeContext().withSender(sender);
        InteractionTestFakes.FakeScene scene = new InteractionTestFakes.FakeScene(context);
        scene.ray = hitAt(TARGET);
        AimAndInteract action = blockAction(scene, c -> InteractionConfirmation.Verdict.PENDING);
        runUntilSubmitted(action, context);
        // 提交了但确认还没等到，任务就结束了：交还等待，结论如实记没能确认。
        assertEquals(Interruptibility.UNSAFE_TO_STOP, action.interruptibility(), "等确认时不能被打断");
        action.close();
        assertTrue(sender.retireCalled);
        assertNotNull(action.result());
        assertEquals(InteractionVerdict.UNCONFIRMED, action.result().verdict());
    }

    private static String failedMessage(ActionStatus status) {
        return ((ActionStatus.Failed) status).problem().message();
    }

    private static String failedKind(ActionStatus status) {
        return ((ActionStatus.Failed) status).problem().kind().name();
    }
}
