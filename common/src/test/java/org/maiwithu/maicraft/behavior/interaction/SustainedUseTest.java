// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.InteractionHand;

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
 * 任务收尾时投影一并交还，不留一直按着的使用键。
 */
class SustainedUseTest {

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
}
