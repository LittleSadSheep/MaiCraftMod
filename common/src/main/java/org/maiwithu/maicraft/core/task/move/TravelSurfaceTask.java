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
    private boolean shaftTried, inShaft;
    private String shaftNote;
    /** 出发列的顶盖高度（heightmap 口径）；列未加载时保持未知。用于失败回执判断起点是否被埋。 */
    private int originCoverY=Integer.MIN_VALUE;
    private int verifiedColumnY=Integer.MIN_VALUE;
    public TravelSurfaceTask(LocalPlayer player,TravelSurfaceTaskRecord record) { super(player,record); }
    protected void onStart() {
        origin=player.position(); lastPosition=origin;
        var start=player.blockPosition();
        originCoverY=player.clientLevel.hasChunkAt(start)
                ? ClientSurfaceHeight.motionBlockingNoLeaves(player.clientLevel,start.getX(),start.getZ())
                : Integer.MIN_VALUE;
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
        // 已授权动土且明显被埋（所在列顶盖高于脚下）时，先沿本列竖直上掘到顶盖再破出：
        // 坑道里的地面段只会原地绕圈，授权一次就消费一次，而不是等地面探索耗尽。
        if(walk==null && flight==null && r.mayAlterTerrain && !shaftTried) {
            shaftTried=true;
            var leg=verticalLeg();
            if(leg!=null) { inShaft=true; walk=new MoveToCompanionTask(player,leg); return TaskState.RUNNING; }
        }
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
            if(inShaft && state!=TaskState.SUCCESS) {
                shaftNote="the vertical ascent leg toward the column cover ended as "+state+" before open sky";
            }
            inShaft=false;
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
    /**
     * 授权动土且所在列顶盖明显高于脚下时，生成本列竖直上掘的精确段：目标即 heightmap 顶，
     * 逐格破头顶方块上来（与 exact 坐标上掘同一条已实测的路径链路），破出顶格后露天判定在常规检查处收口。
     * 顶盖是液体或非实体方块时竖井无法通过，记下位置证据后仍走原有地面流程，不臆造换位。
     */
    MoveToTaskRecord verticalLeg() {
        BlockPos feet=player.blockPosition();
        var level=player.clientLevel;
        if(!level.hasChunkAt(feet)) return null;
        int coverY=ClientSurfaceHeight.motionBlockingNoLeaves(level,feet.getX(),feet.getZ());
        // heightmap 顶指向最高遮挡物上一格；coverY<=脚底+1 说明未被埋，交给原有地面/飞行流程。
        if(coverY<=feet.getY()+1) return null;
        var cover=level.getBlockState(new BlockPos(feet.getX(),coverY-1,feet.getZ()));
        if(!cover.blocksMotion()) {
            shaftNote="vertical ascent skipped: the top cover of the starting column at x="+feet.getX()
                    +" y="+feet.getY()+" z="+feet.getZ()+" is "+cover.getBlock().getName().getString()
                    +" (fluid or non-solid), which a dug shaft cannot pass; other exits remain possible";
            return null;
        }
        shaftNote=null;
        return new MoveToTaskRecord("surface-vertical",player.level().getGameTime()+2400,
                (double)feet.getX(),(double)coverY,(double)feet.getZ(),null,r.mayAlterTerrain,false,
                TransportMode.GROUND,false,true,0,0);
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
        StringBuilder message=new StringBuilder("no open-sky column reached within "+r.radius+" blocks of the start ("
                +legs+" ground legs, "+stages+" flight stages); unseen or unloaded terrain remains unknown, "
                +"which is not evidence that no surface exists");
        // 起点被埋且未授权动土时明示出路：坐标目标 + exact 上掘，或补授权。
        if(originCoverY>BlockPos.containing(origin).getY()+1 && !r.mayAlterTerrain) {
            message.append("; the start sits under a cover reaching y=").append(originCoverY)
                    .append(" — to dig out from underground, submit a coordinate target above with exact arrival")
                    .append(" and may_alter_terrain, or re-submit this surface goal with may_alter_terrain=true");
        }
        if(shaftNote!=null) message.append("; ").append(shaftNote);
        return message.toString();
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
        if (walk != null) return inShaft ? "正在竖直向上挖掘寻找露天地表" : "正在走向附近的露天地表候选点";
        return "正在观察附近寻找露天地表";
    }
    protected Map<String,Object> resultData() {
        var data=new LinkedHashMap<String,Object>();
        data.put("search_radius_cap",r.radius);
        data.put("ground_legs",legs);
        data.put("flight_stages",stages);
        if(shaftNote!=null) data.put("vertical_ascent",shaftNote);
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
