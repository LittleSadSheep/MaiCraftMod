package org.maiwithu.maicraft.core.task.move;

import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.integration.physics.ShipLandingTarget;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportTargets;

/**
 * 完成登上指定移动结构的任务：已站稳就直接确认，否则找可起飞位置并交给喷气背包会话，结束时保留其实际成功或失败信息。
 */
public final class BoardStructureTask extends AbstractCompanionTask<BoardStructureTaskRecord> {
    private final ShipLandingTarget target;
    private JetpackFlightSession flight;
    private TransportSession.Result result;
    private MoveToCompanionTask departure;
    private boolean repositioned;
    public BoardStructureTask(LocalPlayer player, BoardStructureTaskRecord record) {
        super(player,record); target = new ShipLandingTarget(record.structureId,record.interactionFocus);
    }
    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (departure != null) {
            TaskState state = runChild(departure);
            if (state == null) return TaskState.RUNNING;
            var moved = departure.result(state); departure = null;
            if (!moved.success()) { fail("cannot reach a clear takeoff stance: " + moved.message(),FailureType.NO_PATH); return TaskState.FAILED; }
            return TaskState.RUNNING;
        }
        if (result != null) {
            if (result.state() == TransportSession.State.SUCCEEDED) return TaskState.SUCCESS;
            fail(result.code() + ": " + result.detail(), result.uncertain() || result.code().equals("jetpack_search_budget_exhausted")
                    ? FailureType.UNKNOWN : FailureType.NO_PATH); return TaskState.FAILED;
        }
        if (flight == null) {
            if (!target.update(context)) { fail(target.diagnostics().toString(), FailureType.TARGET_LOST); return TaskState.FAILED; }
            if (target.contact()) return target.touchdown() ? TaskState.SUCCESS : TaskState.RUNNING;
            if (TransportRuntime.occupied()) return TaskState.RUNNING;
            var space = JetpackRoute.observed(context);
            if (!space.clear(player.position(),player.position().add(0,1.5,0))) {
                var stance = repositioned ? null : departureStance(context,space);
                if (stance == null) { fail("no clear nearby takeoff stance",FailureType.NO_PATH); return TaskState.FAILED; }
                repositioned = true;
                departure = new MoveToCompanionTask(player,MoveToTaskRecord.strictStance(
                        "boarding-departure",player.level().getGameTime()+600,stance,false,
                        TransportMode.GROUND));
                return TaskState.RUNNING;
            }
            flight = new JetpackFlightSession(target, NavigationSafetyContext.forbiddenBodyCells());
        }
        if (!TransportRuntime.owns(this) && !TransportRuntime.acquire(this,"jetpack",flight,context, value -> result = value))
            return TaskState.RUNNING;
        TransportRuntime.drive(this,context);
        return TaskState.RUNNING;
    }
    // 起飞上方被挡时，仅在同高度周围两格找一个更近的干燥站位；当前任务最多作一次这样的移位。
    private BlockPos departureStance(LocalPlayerContext context,
            JetpackRoute.Space space) {
        var origin = player.blockPosition();
        var candidates = new ArrayList<TransportTargets.Destination>();
        for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) {
            if (x==0 && z==0) continue;
            var probe = TransportLanding.inspect(context.level(),context.level()::isLoaded,
                    origin.offset(x,0,z),player.getBbWidth(),player.getBbHeight(),NavigationSafetyContext.forbiddenBodyCells());
            if (probe.destination()!=null && space.clear(probe.destination().landingPoint(),probe.destination().landingPoint().add(0,1.5,0)))
                candidates.add(probe.destination());
        }
        return candidates.stream().min(Comparator.comparingDouble(p -> p.landingPoint().distanceToSqr(player.position())))
                .map(TransportTargets.Destination::feet).orElse(null);
    }
    @Override protected void cleanup() {
        if (departure != null) { departure.result(TaskState.CANCELLED); departure = null; }
        TransportRuntime.cancel(this); super.cleanup();
    }
    @Override protected String successMessage() { return "native collision confirms stable boarding of structure " + r.structureId; }
    @Override protected Map<String,Object> resultData() {
        var data = new LinkedHashMap<String,Object>(target.diagnostics());
        if (flight != null) data.put("flight",flight.diagnostics());
        if (result != null) { data.put("code",result.code()); data.put("effects_started",result.effectsStarted()); data.put("uncertain",result.uncertain()); }
        return data;
    }
}
