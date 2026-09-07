package org.maiwithu.maicraft.core.task.chain;

import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.landing.EmergencyLanding;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistSession;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import net.minecraft.client.player.LocalPlayer;

/** Unexpected-fall trigger. Planned and reflex falls use the same native landing/recovery session. */
public final class MLGChain implements Task, org.maiwithu.maicraft.task.reflex.Reflex {
    private LandingAssistSession session;
    private boolean attentionActive;
    private float attentionStartHealth;
    private int attentionActions;

    @Override
    public boolean canRun(LocalPlayer companion) {
        if (EmbeddedBaritoneRuntime.ownsActiveLandingAssist(companion)) return false;
        if (session != null) return !session.complete();
        boolean grounded = companion.onGround() || companion.isInWater()
                || companion.isSwimming() || companion.onClimbable();
        return !WorkProfile.of(companion).fearless()
                && SurvivalDecisions.mlgTriggered(grounded, companion.getDeltaMovement().y, EmergencyLanding.hasItem(companion));
    }

    @Override
    public TaskState tick(LocalPlayer companion) { return tick(ClientRuntime.requireContext(companion)); }

    public TaskState tick(LocalPlayerContext context) {
        var player = context.player();
        if (session == null) {
            session = EmergencyLanding.find(context);
            if (session == null) {
                context.body().requestLook(player.getYRot(), 90, context.tickRevision());
                return TaskState.RUNNING;
            }
            attentionActive = true;
            attentionStartHealth = player.getHealth();
            attentionActions = 0;
            GameplayAttentionMonitor.reflexStarted(id(), "rapid fall with imminent impact", "native landing assistance",
                    "temporary landing aid; only confirmed own placement may be recovered", "fall damage or death if protection fails");
        }
        EmergencyLanding.tick(context, session);
        attentionActions += session.drainChanges().size();
        LandingAssistPolicy.report(session.diagnostics());
        if (session.complete()) finishAttention(player, session.failed() ? "landing protection unverified" : "landing protection verified");
        return TaskState.RUNNING;
    }

    private void finishAttention(LocalPlayer player, String outcome) {
        if (!attentionActive) return;
        var facts = session == null ? java.util.Map.<String, Object>of() : session.diagnostics();
        String resource = Boolean.TRUE.equals(facts.get("removed_own_aid")) ? "confirmed own landing aid recovered"
                : Boolean.TRUE.equals(facts.get("confirmed_own_placement")) ? "confirmed own landing aid remains"
                : "no own placement or recovery confirmed";
        GameplayAttentionMonitor.reflexFinished(id(), outcome, attentionActions, resource,
                "health lost: " + facts.getOrDefault("health_lost", Math.max(0, attentionStartHealth - player.getHealth()))
                        + "; mitigated with damage: " + facts.getOrDefault("mitigated_with_damage", false));
        attentionActive = false;
    }

    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        try {
            ClientRuntime.actor().activeContext().filter(c -> c.player() == companion && c.isCurrent()).ifPresent(context -> {
                if (session != null && !session.complete()) session.stop(context, "emergency landing owner ended: " + why);
                context.body().releaseAll();
            });
        } finally {
            finishAttention(companion, why == StopReason.BODY_GONE ? "body unavailable; result unconfirmed" : "fall episode ended");
            session = null;
        }
    }

    @Override public String name() { return "mlg"; }
    @Override public String id() { return name(); }
    @Override public String describe() { return "高处坠落时会用水桶或落地辅助自救；仅回收已确认自放的辅助，干草减伤会如实记录受伤"; }
}
