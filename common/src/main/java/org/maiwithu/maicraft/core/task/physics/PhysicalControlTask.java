package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.assembly.ServerBlockEntityReceipts;
import org.maiwithu.maicraft.core.integration.create.CreateManualInput;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.core.task.physics.PhysicalControlParameters.Operation.*;

/** 读取 -> 取原生工具/频率物品 -> 走到可见面 -> 单次提交 -> 服务端同步核对 -> 完整声明复查。 */
public final class PhysicalControlTask extends AbstractCompanionTask<PhysicalControlTaskRecord> {
    private final Level world;
    private final PhysicalControlHand hand=new PhysicalControlHand();
    private AssemblyApproach approach=new AssemblyApproach();
    private OnboardControlApproach onboard=new OnboardControlApproach();
    private final List<Map<String,Object>> effects=new ArrayList<>(),approaches=new ArrayList<>();
    private PhysicalAssemblyFrame frame;
    private NativeActionReceipt action;
    private ServerBlockEntityReceipts.Watch watch;
    private ItemStack selected=ItemStack.EMPTY;
    private Map<String,Object> before=Map.of(),after=Map.of();
    private int index;private boolean done,submitted;
    private long movingSince=-1,designSince=-1,configurationObservedSince=-1;
    private CompletableFuture<PhysicalStructureDesignStore.Registration> design;
    private JsonArray declarations=new JsonArray();private JsonObject designEvidence=new JsonObject();
    private String afterUnknown;
    private final CreateManualInput.UsageEvidence crankUsage=new CreateManualInput.UsageEvidence();
    private long crankStarted=-1,nextCrankAt;private int crankUses;
    private TypewriterControlSession typewriter;
    public PhysicalControlTask(LocalPlayer player,PhysicalControlTaskRecord record){super(player,record);world=player.level();}
    @Override protected TaskState onTick() {
        if(done)return finishDesign();
        if(player.level()!=world||!world.dimension().location().toString().equals(r.dimension))return failed("配置期间世界或维度改变",FailureType.TARGET_LOST);
        var ctx=ClientRuntime.requireContext(player);
        if(action!=null) {
            action=ctx.actions().poll(ctx,action);if(!action.terminal())return TaskState.RUNNING;
            if(action.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED)return failed("原生设置未确认，保留当前状态且不重放输入: "+action.detail(),FailureType.UNKNOWN);
            if(r.parameters.operation()==TURN_CRANK) {
                // 每次先结清实际手摇与网络响应，再决定是否续摇；移动船体的下一次瞄准会重新读取实时姿态。
                crankUses++;crankUsage.observe(world,frame.storage(r.parameters.position()),crankUses);
                after=NativePhysicalControl.state(entity());action=null;nextCrankAt=world.getGameTime()+4;
                if(r.parameters.crankTicks()==0||world.getGameTime()-crankStarted>=r.parameters.crankTicks())advance();
                return TaskState.RUNNING;
            }
            if(NativePhysicalControl.propeller(r.parameters.operation())) {
                // 右键已由服务器处理后，另等桨叶成型或减速拆回；设计错误和未成型作为观察结果，不抹去已确认输入。
                if(configurationObservedSince<0)configurationObservedSince=world.getGameTime();
                after=NativePhysicalControl.state(entity());
                if(!NativePhysicalControl.matches(entity(),r.parameters,index,selected)&&!after.containsKey("assembly_error")
                        &&world.getGameTime()-configurationObservedSince<300)return TaskState.RUNNING;
            }
            var receipt=action;action=null;if(watch!=null){watch.close();watch=null;}
            try {after=NativePhysicalControl.state(entity());}catch(RuntimeException unavailable){after=Map.of();afterUnknown=unavailable.toString();}
            effects.add(Map.of("step",index,"before",before,"after",after,"native_action_status",receipt.status().name()));
            advance();return TaskState.RUNNING;
        }
        frame=PhysicalAssemblyFrame.read(player,r.parameters.structureId(),r.anchor);
        BlockEntity entity=entity();NativePhysicalControl.require(entity,r.parameters);
        after=NativePhysicalControl.state(entity);
        if(r.parameters.operation()==INSPECT){done=true;return TaskState.RUNNING;}
        // 打字机的频率保存与按住/松开有独立会话，复用同一真实站位、座位约束和整机声明复查。
        if(NativePhysicalControl.typewriter(r.parameters.operation())) {
            if(!player.mayBuild()||player.isSpectator())return failed("当前玩家没有原生控制权限",FailureType.UNKNOWN);
            if(typewriter==null)typewriter=new TypewriterControlSession(r.parameters);
            try {
                if(typewriter.tick(ctx,frame,entity,hand,()->approachReady(entity),r.getToolCallId(),r.getDeadlineGameTime()))done=true;
            } catch(IllegalStateException unavailable) { return failed(unavailable.getMessage(),FailureType.UNKNOWN); }
            if(hand.failure()!=null)return failed(hand.failure(),FailureType.NO_MATERIAL);
            if(approachFailure()!=null)return failed(approachFailure(),FailureType.NO_PATH);
            after=NativePhysicalControl.state(entity);return TaskState.RUNNING;
        }
        // 装好的轮胎在取备料之前就可确认，避免缺少第二只同款轮胎时反复补料或把现有轮胎交换掉。
        if(r.parameters.operation()==SET_TIRE&&NativePhysicalControl.matches(entity,r.parameters,index,ItemStack.EMPTY)) {
            effects.add(Map.of("step",index,"already_matched",true,"after",after));advance();return TaskState.RUNNING;
        }
        if(r.parameters.operation()==TURN_CRANK&&crankStarted>=0) {
            if(world.getGameTime()-crankStarted>=r.parameters.crankTicks()){done=true;return TaskState.RUNNING;}
            if(world.getGameTime()<nextCrankAt)return TaskState.RUNNING;
        }
        // 频率和电机配置在停稳时完成；明确的油门操作仍可用于收车，不以“还在移动”拦截停机输入。
        if(r.parameters.operation()!=SET_THROTTLE&&r.parameters.operation()!=TURN_CRANK&&!frame.stationary()) {
            if(movingSince<0)movingSince=world.getGameTime();
            if(world.getGameTime()-movingSince>200)return failed("设置部件前需要先让结构停稳",FailureType.NO_PATH);
            return TaskState.RUNNING;
        }
        movingSince=-1;
        if(!player.mayBuild()||player.isSpectator())return failed("当前玩家没有原生修改权限",FailureType.UNKNOWN);
        // 导航可能正在准备飞行装备或自己的菜单；先让已开始的走位结算，不能每刻用取工具流程抢关它的界面。
        if(approachMoving()&&!approachReady(entity))return approachFailure()==null?TaskState.RUNNING:failed(approachFailure(),FailureType.NO_PATH);
        if(!hand.ready(player,NativePhysicalControl.requiredItem(entity,r.parameters,index),r.getToolCallId(),r.getDeadlineGameTime())) {
            if(hand.failure()!=null)return failed(hand.failure(),FailureType.NO_MATERIAL);return TaskState.RUNNING;
        }
        selected=player.getMainHandItem().copy();
        if(NativePhysicalControl.matches(entity,r.parameters,index,selected)) {
            effects.add(Map.of("step",index,"already_matched",true,"after",after));advance();return TaskState.RUNNING;
        }
        if(NativePhysicalControl.valueBox(r.parameters.operation())&&!NativePhysicalControl.settingAccessible(entity,player))
            return failed("当前原生旋钮不接受此玩家或主手物品的操作",FailureType.UNKNOWN);
        if(!approachReady(entity)) {
            if(approachFailure()!=null)return failed(approachFailure(),FailureType.NO_PATH);return TaskState.RUNNING;
        }
        if(!ctx.menus().ensureWorldVisible(ctx))return TaskState.RUNNING;
        InputDriver.halt(player);InputDriver.sneak(player,false);
        var aim=PhysicalControlAim.aim(player,frame,r.parameters.position(),entity,r.parameters,index,player.getEyePosition());
        if(aim==null)return TaskState.RUNNING;
        InputDriver.lookAt(player,aim);var hit=frame.actualHit(player,r.parameters.position(),false);
        if(player.isShiftKeyDown()||!PhysicalControlAim.valid(entity,r.parameters,index,hit)||!ctx.mutationAvailable())return TaskState.RUNNING;
        before=NativePhysicalControl.state(entity);
        int step=index;ItemStack expected=selected.copy();
        boolean propeller=NativePhysicalControl.propeller(r.parameters.operation());
        boolean crank=r.parameters.operation()==TURN_CRANK;
        boolean blockMode=r.parameters.operation()==SET_LINK_MODE||r.parameters.operation()==SET_TIRE||propeller||crank;
        if(!blockMode)watch=ServerBlockEntityReceipts.watch(world,entity.getBlockPos());
        var expectedWatch=watch;
        NativeConfirmation confirm=crank?CreateManualInput.confirmation(world,entity.getBlockPos()):new NativeConfirmation() {
            public boolean requiresBlockAcknowledgement(){return blockMode;}
            public Verdict observe(LocalPlayerContext fresh) {
                if(fresh.level()!=world)return Verdict.DIVERGED;
                if(propeller)return Verdict.APPLIED; // 原生方块交互确认与后续桨叶实际成型分开结算。
                if(!blockMode&&!expectedWatch.advanced())return Verdict.PENDING;
                return NativePhysicalControl.matches(entity(),r.parameters,step,expected)?Verdict.APPLIED:Verdict.PENDING;
            }
        };
        // 频率与收发模式使用真实物品右键；旋钮和油门使用对应原生玩家协议，均只有这一笔输入。
        if(r.parameters.operation()==SET_FREQUENCY||blockMode) {
            if(crank&&crankStarted<0)crankStarted=world.getGameTime();
            submitted=true;action=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,confirm,100);
        }
        else {
            var packet=NativePhysicalControl.packet(entity,player,r.parameters,hit);
            submitted=true;
            action=ctx.actions().submitControlProtocol(ctx,"physical component "+r.parameters.operation(),
                    ()->ctx.connection().send(new ServerboundCustomPayloadPacket(packet)),confirm,100);
        }
        return TaskState.RUNNING;
    }
    private boolean approachReady(BlockEntity entity) {
        // 有艇上操作要求时不调用地面寻路；已入座者够不到控制器则保留座位并如实返回。
        if(r.parameters.requireOnboard())return onboard.ready(player,frame,r.parameters.position(),
                eye->PhysicalControlAim.aim(player,frame,r.parameters.position(),entity,r.parameters,index,eye),r.getToolCallId(),r.getDeadlineGameTime());
        return approach.ready(player,frame,r.parameters.position(),eye->PhysicalControlAim.aim(player,frame,r.parameters.position(),entity,r.parameters,index,eye),
                r.getToolCallId(),r.getDeadlineGameTime());
    }
    private boolean approachMoving(){return r.parameters.requireOnboard()?onboard.moving():approach.moving();}
    private String approachFailure(){return r.parameters.requireOnboard()?onboard.failure():approach.failure();}
    private Map<String,Object> approachEvidence(){return r.parameters.requireOnboard()?onboard.evidence():approach.evidence();}
    private BlockEntity entity() {
        var pos=frame.storage(r.parameters.position());
        if(!frame.loaded(pos))throw new IllegalStateException("部件所在区块未加载");
        var entity=world.getBlockEntity(pos);if(entity==null)throw new IllegalStateException("原生部件已移除或未同步");return entity;
    }
    private void advance() {
        approaches.add(approachEvidence());approach.close();onboard.close();approach=new AssemblyApproach();onboard=new OnboardControlApproach();
        if(++index>=(r.parameters.operation()==SET_FREQUENCY?2:1))done=true;
    }
    private TaskState finishDesign() {
        if(player.level()!=world) {frame=null;afterUnknown="原生效果已结算，但玩家已切换世界，未能复查结构";return TaskState.SUCCESS;}
        if(designSince<0) {
            designSince=world.getGameTime();
            // 配置已确认后再复查整机声明，数据库或现场读取失败不会抹去已发生的原生效果。
            var request=new PhysicalAssemblyParameters(PhysicalAssemblyParameters.Operation.INSPECT,null,r.parameters.structureId(),
                    r.parameters.position(),r.parameters.position(),r.parameters.position(),null,r.parameters.designId(),new JsonArray());
            design=AssemblyDesignSession.prepare(player,request,r.anchor,r.dimension);
        }
        if(!design.isDone()&&world.getGameTime()-designSince<100)return TaskState.RUNNING;
        try {
            if(!design.isDone())throw new IllegalStateException("原生效果已确认，声明读取仍未完成");
            var read=design.join();declarations=read.targets();designEvidence=read.evidence();
            frame=PhysicalAssemblyFrame.read(player,r.parameters.structureId(),r.anchor);
        } catch(RuntimeException unavailable){afterUnknown=unavailable.toString();}
        return TaskState.SUCCESS;
    }
    private TaskState failed(String why,FailureType type){fail(why,type);return TaskState.FAILED;}
    @Override public void stop(LocalPlayer player,Task.StopReason why){approach.stop(player,why);onboard.stop(player,why);hand.stop(player,why);super.stop(player,why);}
    @Override protected void cleanup() {
        if(typewriter!=null)typewriter.close();
        approach.close();onboard.close();hand.close();if(watch!=null){watch.close();watch=null;}
        var ctx=ClientRuntime.actor().activeContext().orElse(null);
        if(ctx!=null&&action!=null&&!action.terminal())ctx.actions().retireOneShotForTaskBoundary(ctx,action,"物理控制任务结束");
        super.cleanup();
    }
    @Override protected String successMessage(){return r.parameters.operation()==INSPECT?"原生物理部件设置已读取":"原生部件配置已结算，实际转速、信号与结构差异分别返回";}
    @Override public Map<String,Object> progress() {
        // 操作者应能分清正在拿材料、换站位还是等服务器确认，不能只看到一个持续不变的任务类名。
        return Map.of("task",name(),"operation",r.parameters.operation().name().toLowerCase(Locale.ROOT),"configuration_step",index+1,
                "phase",done?"checking_full_design":typewriter!=null?typewriter.phase():configurationObservedSince>=0?"observing_propeller_state":action!=null?"confirming_native_input":approachMoving()?"approaching_control":movingSince>=0?"waiting_for_stop":"preparing_control",
                "hand",hand.progress(),"approach",approachEvidence());
    }
    @Override protected Map<String,Object> resultData() {
        var out=new LinkedHashMap<String,Object>();out.put("operation",r.parameters.operation().name().toLowerCase(Locale.ROOT));
        var completed=new ArrayList<>(effects);
        if(r.parameters.operation()==TURN_CRANK) {
            // 后续失败或取消也保留已确认的手摇次数和运行应力；停摇后的零转速不能覆盖这些实际效果。
            if(crankUses>0)completed.add(Map.of("operation","turn_crank","confirmed_native_uses",crankUses,"started_tick",crankStarted));
            out.put("manual_generator",crankUsage.data());out.put("requested_duration_ticks",r.parameters.crankTicks());
        }
        out.put("native_submitted",submitted);out.put("completed_effects",List.copyOf(completed));out.put("actual_configuration",after);
        boolean desired=!(NativePhysicalControl.propeller(r.parameters.operation()))
                || after.get("assembled") instanceof Boolean assembled && assembled==(r.parameters.operation()==ASSEMBLE_PROPELLER);
        out.put("native_configuration_confirmed",done&&r.parameters.operation()!=INSPECT&&desired);
        if(NativePhysicalControl.propeller(r.parameters.operation()))out.put("requested_propeller_state_observed",desired);
        out.put("structure_id",r.parameters.structureId()==null?"world":r.parameters.structureId().toString());
        out.put("component_offset",List.of(r.parameters.position().getX(),r.parameters.position().getY(),r.parameters.position().getZ()));
        out.put("design_declaration",designEvidence);out.put("declared_structure_diff",AssemblyDeclarationView.diff(frame,declarations));
        out.put("approach",Map.of("completed",List.copyOf(approaches),"current",approachEvidence()));out.put("supply",hand.evidence());
        out.put("required_onboard",r.parameters.requireOnboard());
        out.put("onboard_observed",frame!=null&&OnboardControlApproach.supported(player,frame));
        out.put("vehicle_operation_verified",false);if(action!=null)out.put("input_receipt",Map.of("status",action.status().name(),"detail",action.detail()));
        if(typewriter!=null)out.putAll(typewriter.evidence());
        if(afterUnknown!=null)out.put("after_observation_unknown",afterUnknown);return out;
    }
}
