// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackFlightSession;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackNativeAdapter;
import org.maiwithu.maicraft.core.integration.jetpack.RegionalFlightTarget;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.goal.RegionalGoal;
import org.maiwithu.maicraft.core.pathing.goal.RegionalTerrain;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.pathing.transport.TransportRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 不带坐标地回到露天地表：地面分段走向已观察到的露天列，地面候选用尽后改飞行逐段向上。
 * 停止判据是脚下这一列“忽略树叶的最高实体或液体遮挡物”正好在脚底；头顶还有遮挡、
 * 站在水底或列所在区块未加载时都不算到达，未加载先移动加载再采样。
 */
public final class TravelSurfaceTask extends AbstractCompanionTask<TravelSurfaceTaskRecord> {
    /** 阶段飞行次数上限：每次阶段落地都比上一处至少高三格，这个上限只是防病态振荡的保险。 */
    private static final int MAX_FLIGHT_STAGES = 128;
    private Vec3 origin;
    private RegionalTerrain terrain;
    private MoveToCompanionTask walk;
    private JetpackFlightSession flight;
    private TransportSession.Result flightResult;
    private final Set<BlockPos> attempted=new HashSet<>();
    private Vec3 lastPosition;
    private int legs, stages;
    private boolean groundExhausted;
    private int verifiedColumnY=Integer.MIN_VALUE;
    public TravelSurfaceTask(LocalPlayer player,TravelSurfaceTaskRecord record) { super(player,record); }
    protected void onStart() {
        origin=player.position(); lastPosition=origin;
        terrain=new RegionalTerrain(origin);
    }
    protected TaskState onTick() {
        // 有实际移动就续期；先处理已经开始的飞行/步行段，不每刻重新选交通方式。
        var ctx=ClientRuntime.requireContext(player);
        if(player.position().distanceToSqr(lastPosition)>.04) {
            lastPosition=player.position(); r.extendDeadlineTo(player.level().getGameTime()+600);
        }
        if(flightResult!=null) {
            if(flightResult.state()==TransportSession.State.SUCCEEDED) return landed();
            fail(flightResult.code()+": "+flightResult.detail(),flightResult.uncertain() ? FailureType.UNKNOWN : FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        // 站立即露天时无需起飞；飞行进行中不抢判，落地的稳定性仍由交通会话确认。
        if(flight==null && player.onGround() && openSkyAt(player.blockPosition())) return arrived();
        boolean wantsFlight=walk==null && (r.mode==TransportMode.JETPACK || r.mode==TransportMode.AUTO
                && groundExhausted) && JetpackNativeAdapter.inspect(ctx).controllable();
        // 明确 jetpack 直接起飞；auto 只在地面候选用尽后向上飞。
        if(flight!=null || wantsFlight) {
            if(TransportRuntime.occupied() && !TransportRuntime.owns(this)) return TaskState.RUNNING;
            if(flight==null) flight=new JetpackFlightSession(stageTarget(),NavigationSafetyContext.forbiddenBodyCells());
            if(!TransportRuntime.owns(this) && !TransportRuntime.acquire(this,"jetpack",flight,ctx,value->flightResult=value)) return TaskState.RUNNING;
            TransportRuntime.drive(this,ctx); return TaskState.RUNNING;
        }
        if(player.position().distanceTo(origin)>r.radius+2) {
            fail(rangeFailure(),FailureType.NO_PATH); return TaskState.FAILED;
        }
        if(walk!=null) {
            var state=runChild(walk);
            if(state==null) return TaskState.RUNNING;
            walk.result(state); walk=null; terrain=new RegionalTerrain(player.position());
            return TaskState.RUNNING;
        }
        var view=RegionalTerrain.observed(player);
        terrain.advance(view,4);
        if(!terrain.complete()) return TaskState.RUNNING;
        Vec3 next=terrain.surfaces().stream().filter(s->s.point().distanceTo(origin)<=r.radius)
                .filter(s->s.point().distanceToSqr(player.position())>=9 && !attempted.contains(BlockPos.containing(s.point())))
                .sorted(Comparator.comparingDouble(this::groundScore))
                .map(RegionalTerrain.Surface::point).findFirst().orElse(null);
        if(next==null || legs>=128) {
            // auto 若有可用背包再换飞行向上，否则说明目前没有可尝试的已加载候选。
            if(r.mode==TransportMode.AUTO && JetpackNativeAdapter.inspect(ctx).controllable()) {
                groundExhausted=true; return TaskState.RUNNING;
            }
            fail(rangeFailure(),FailureType.NO_PATH); return TaskState.FAILED;
        }
        attempted.add(BlockPos.containing(next)); legs++;
        BlockPos feet=BlockHelper.playerFeet(player.level(),next.x,next.y,next.z);
        var record=new MoveToTaskRecord("surface-leg-"+legs,player.level().getGameTime()+1200,
                (double)feet.getX(),(double)feet.getY(),(double)feet.getZ(),null,r.mayAlterTerrain,false,TransportMode.GROUND,false,false,1,.5);
        walk=new MoveToCompanionTask(player,record); return TaskState.RUNNING;
    }
    private double groundScore(RegionalTerrain.Surface surface) {
        // 露天列直接满足目标优先，其余按就近探索加载更多区块。
        BlockPos feet=BlockHelper.playerFeet(player.clientLevel,surface.point().x,surface.point().y,surface.point().z);
        return (openSkyAt(feet) ? 0 : 1000)+surface.point().distanceTo(player.position());
    }
    /** 阶段目标锚在当前落脚点向上三格起算：已站过的列不再满足条件，飞行逐段抬升而不是原地反复。 */
    private RegionalFlightTarget stageTarget() {
        return new RegionalFlightTarget(new RegionalGoal(player.position(),RegionalGoal.direction("up",0),r.radius));
    }
    private TaskState landed() {
        // 飞行只保证落在有支撑的面上；露天以落地后的真实列高为准，累计漂移出搜索范围同样失败。
        if(player.position().distanceTo(origin)>r.radius+2) {
            fail(rangeFailure(),FailureType.NO_PATH); return TaskState.FAILED;
        }
        BlockPos feet=player.blockPosition();
        if(openSkyAt(feet)) return arrived();
        attempted.add(feet);
        if(++stages>MAX_FLIGHT_STAGES) { fail(rangeFailure(),FailureType.NO_PATH); return TaskState.FAILED; }
        flight=null; flightResult=null; groundExhausted=false;
        terrain=new RegionalTerrain(player.position());
        return TaskState.RUNNING;
    }
    private boolean openSkyAt(BlockPos feet) {
        // 未加载的列既不判露天也不判封闭：先移动加载，再采样；缺席不构成“没有地表”的证据。
        var level=player.clientLevel;
        if(!level.hasChunkAt(feet)) return false;
        return ClientSurfaceHeight.motionBlockingNoLeaves(level,feet.getX(),feet.getZ())==feet.getY();
    }
    private TaskState arrived() {
        BlockPos feet=player.blockPosition();
        verifiedColumnY=ClientSurfaceHeight.motionBlockingNoLeaves(player.clientLevel,feet.getX(),feet.getZ());
        r.verified=new InternalPositionReceipt.Position(feet.getX(),feet.getY(),feet.getZ(),player.level().dimension().location().toString());
        return TaskState.SUCCESS;
    }
    private String rangeFailure() {
        // 范围型失败必须携带搜索范围：没找到不等于世界里没有，调用方应换位置或扩大半径再试。
        return "no open-sky column reached within "+r.radius+" blocks of the start ("
                +legs+" ground legs, "+stages+" flight stages); unseen or unloaded terrain remains unknown, "
                +"which is not evidence that no surface exists";
    }
    public void stop(LocalPlayer player,StopReason reason) {
        // 与区域探索相同：当前对暂停和取消都结束交通控制。
        if(walk!=null) walk.stop(player,reason);
        TransportRuntime.cancel(this); super.stop(player,reason);
    }
    protected void cleanup() {
        if(walk!=null) { walk.result(TaskState.CANCELLED); walk=null; }
        TransportRuntime.cancel(this); super.cleanup();
    }
    protected String successMessage() {
        return "arrived under open sky: the highest solid or liquid cover of this column ignoring foliage is the ground at y="
                +verifiedColumnY+", with nothing above the standing cell (tree foliage is not treated as cover)";
    }

    /** 面板行动行的一句话汇报；阶段来自在飞的交通方式（步行分段/飞行抬升）。 */
    @Override
    public String describeCurrentAction() {
        if (flight != null) return "正在向上飞行寻找露天地表";
        if (walk != null) return "正在走向附近的露天地表候选点";
        return "正在观察附近寻找露天地表";
    }
    protected Map<String,Object> resultData() {
        var data=new LinkedHashMap<String,Object>();
        data.put("search_radius_cap",r.radius);
        data.put("ground_legs",legs);
        data.put("flight_stages",stages);
        if(verifiedColumnY!=Integer.MIN_VALUE) {
            BlockPos feet=player.blockPosition();
            data.put("open_sky_evidence",Map.of("feet_y",feet.getY(),"column_surface_y",verifiedColumnY,
                    "foliage_not_treated_as_cover",true,"dimension",player.level().dimension().location().toString()));
        }
        data.put("terrain",terrain==null ? Map.of() : terrain.summary());
        data.put("flight",flight==null ? Map.of() : flight.diagnostics());
        return data;
    }
}
