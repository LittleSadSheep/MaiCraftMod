package org.maiwithu.maicraft.core.task.physics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.core.integration.machine.control.DriverStation;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 从可见侧面接近座位 -> 空手原生右键 -> 确认乘坐；低顶吊舱不要求先从上方飞落，入座本身不启动载具。 */
public final class StructureSeatTask extends AbstractCompanionTask<BoardStructureTaskRecord> {
    private final PhysicalStructureApproach approach=new PhysicalStructureApproach();
    private final PhysicalControlHand hand=new PhysicalControlHand();
    private DriverStation station;
    private NativeActionReceipt action;
    private boolean seated,submitted;
    public StructureSeatTask(LocalPlayer player,BoardStructureTaskRecord record){super(player,record);}
    @Override protected TaskState onTick() {
        var frame=PhysicalAssemblyFrame.read(player,r.structureId,BlockPos.ZERO);
        var pos=frame.storage(r.seatOffset);
        station=new DriverStation(pos,List.of());
        var ctx=ClientRuntime.requireContext(player);
        if(action!=null) {
            action=ctx.actions().poll(ctx,action);if(!action.terminal())return TaskState.RUNNING;
            if(action.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED){fail("座位右键未确认，不重放输入: "+action.detail(),FailureType.UNKNOWN);return TaskState.FAILED;}
            seated=station.seated(player);return seated?TaskState.SUCCESS:failed("右键确认后乘坐关系已经结束");
        }
        if(!frame.loaded(pos)||!ControlReflection.is(frame.level().getBlockState(pos).getBlock(),"com.simibubi.create.content.contraptions.actors.seat.SeatBlock"))
            return failed("声明位置不是当前可读的 Create 座位");
        if(station.seated(player)){seated=true;return TaskState.SUCCESS;}
        if(!DriverStation.unoccupied(player,pos))return failed("座位已有其他乘客");
        // 原生右键座位会让玩家上艇，先复用驾驶座侧面走位；不能强制飞到燃烧器上方被气囊封住的落点。
        if(!approach.ready(player,r.structureId,pos,r.getToolCallId(),r.getDeadlineGameTime()))
            return approach.failure()==null?TaskState.RUNNING:failed(approach.failure());
        if(!hand.ready(player,"minecraft:air",r.getToolCallId(),r.getDeadlineGameTime()))
            return hand.failure()==null?TaskState.RUNNING:failed(hand.failure());
        InputDriver.halt(player);InputDriver.sneak(player,false);
        if(player.isShiftKeyDown())return TaskState.RUNNING;
        var aim=frame.aim(player,r.seatOffset,player.getEyePosition(),false);
        if(aim==null)return failed("入座前实时射线被遮挡，未提交右键");
        InputDriver.lookAt(player,aim);var hit=frame.actualHit(player,r.seatOffset,false);
        if(hit==null||!ctx.mutationAvailable())return TaskState.RUNNING;
        var leashed=ControlReflection.call(ControlReflection.type("com.simibubi.create.content.contraptions.actors.seat.SeatBlock"),"getLeashed",ctx.level(),player);
        if(Boolean.TRUE.equals(ControlReflection.call(leashed,"isPresent")))return failed("当前牵引生物会优先占座，未代替玩家入座");
        submitted=true;
        action=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,NativeConfirmation.serverObservedEntity(
                fresh->station.seated(fresh.player())?NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING),40);
        return TaskState.RUNNING;
    }
    private TaskState failed(String reason){fail(reason,FailureType.NO_PATH);return TaskState.FAILED;}
    @Override public void stop(LocalPlayer player,Task.StopReason why){approach.stop(player,why);hand.stop(player,why);super.stop(player,why);}
    @Override protected void cleanup(){
        approach.close();hand.close();
        // 入座请求已发出时取消只退役本次观察，不能重发右键或直接改掉玩家的乘坐关系。
        var ctx=ClientRuntime.actor().activeContext().orElse(null);
        if(ctx!=null&&action!=null&&!action.terminal())ctx.actions().retireOneShotForTaskBoundary(ctx,action,"指定座位任务结束");
        super.cleanup();
    }
    @Override protected String successMessage(){return "原生乘坐关系确认，已坐入指定飞艇座位，未启动载具";}
    @Override public Map<String,Object> progress(){return Map.of("phase",action!=null?"confirming_seat":"boarding_seat","approach",approach.evidence());}
    @Override protected Map<String,Object> resultData(){
        var out=new LinkedHashMap<String,Object>();out.put("structure_id",r.structureId.toString());
        out.put("seat_offset",List.of(r.seatOffset.getX(),r.seatOffset.getY(),r.seatOffset.getZ()));
        out.put("seat_confirmed",seated);out.put("native_submitted",submitted);out.put("approach",approach.evidence());
        if(action!=null)out.put("native_receipt",Map.of("status",action.status().name(),"detail",action.detail()));return out;
    }
}
