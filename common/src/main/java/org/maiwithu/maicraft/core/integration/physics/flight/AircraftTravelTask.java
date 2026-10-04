package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.baritone.NavigationDismount;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 登机飞行 -> 停稳释放键盘 -> 原生下车 -> 地面到达；失败时保留已完成的各段回执。 */
public final class AircraftTravelTask extends AbstractCompanionTask<AircraftTravelTaskRecord> {
    private AircraftFlightTask flight;
    private MoveToCompanionTask ground;
    private final NavigationDismount dismount = new NavigationDismount();
    private boolean landed, dismounted, arrived;
    private Map<String,Object> flightEvidence = Map.of(), groundEvidence = Map.of();
    public AircraftTravelTask(LocalPlayer player, AircraftTravelTaskRecord record) { super(player, record); }
    @Override protected TaskState onTick() {
        if (!landed) {
            if (flight == null) {
                // 未提供 Y 时只用当前高度初始化巡航参考；最后一段仍按原来的 X/Z 列验收。
                var destination = new Vec3(r.arrival.x, r.arrival.y == null ? player.getY() : r.arrival.y, r.arrival.z);
                flight = new AircraftFlightTask(player, new AircraftFlightTaskRecord(r.getToolCallId(),
                        r.getDeadlineGameTime(), r.aircraftId, "fly", null, destination, null, 0, r.altitude));
            }
            TaskState state = runChild(flight);
            if (state == null) return TaskState.RUNNING;
            var result = flight.result(state); flightEvidence = result.data(); flight = null;
            if (!result.success()) return failed("飞行段未完成：" + result.message());
            landed = true;
        }
        if (!dismounted) {
            // 即使座位恰好落在目标范围内，也必须确认角色真的下车，再判断步行终点。
            var state = dismount.tick(ClientRuntime.requireContext(player));
            if (state == NavigationDismount.State.FAILED) return failed("停稳后原生下车未确认：" + dismount.detail());
            if (state != NavigationDismount.State.READY) return TaskState.RUNNING;
            dismounted = true;
        }
        if (ground == null) ground = new MoveToCompanionTask(player, r.arrival);
        TaskState state = runChild(ground);
        if (state == null) return TaskState.RUNNING;
        var result = ground.result(state); groundEvidence = result.data(); ground = null;
        if (!result.success()) return failed("飞机已停稳，最后地面路段未完成：" + result.message());
        arrived = true; return TaskState.SUCCESS;
    }
    private TaskState failed(String reason) { fail(reason, FailureType.NO_PATH); return TaskState.FAILED; }
    @Override public void stop(LocalPlayer player, Task.StopReason reason) {
        // 飞行子任务取消后仍由交通租约执行附近着陆；不在空中启动步行或强行退出座椅。
        if (flight != null) flight.stop(player, reason);
        if (ground != null) ground.stop(player, reason);
        dismount.cancel(player); super.stop(player, reason);
    }
    @Override protected void cleanup() {
        // 中止也保留实际飞行与步行证据；飞机正在自主收尾时不能把已经发生的动作抹成空回执。
        if (flight != null) { flightEvidence = flight.result(TaskState.CANCELLED).data(); flight = null; }
        if (ground != null) { groundEvidence = ground.result(TaskState.CANCELLED).data(); ground = null; }
        dismount.cancel(player); super.cleanup();
    }
    @Override public Map<String,Object> progress() {
        if (flight != null) return flight.progress();
        return Map.of("phase", !landed ? "boarding_aircraft" : !dismounted ? "dismounting_parked_aircraft" : "last_ground_leg");
    }
    @Override protected Map<String,Object> resultData() {
        var data = new LinkedHashMap<String,Object>();
        data.put("aircraft_id", r.aircraftId.toString()); data.put("flight", flightEvidence);
        data.put("aircraft_landed", landed); data.put("dismounted", dismounted);
        data.put("ground_leg", groundEvidence); data.put("destination_reached", arrived);
        return data;
    }
    @Override protected String successMessage() { return "已乘飞机着陆停稳、原生下车，并按原旅行坐标完成地面到达"; }
}
