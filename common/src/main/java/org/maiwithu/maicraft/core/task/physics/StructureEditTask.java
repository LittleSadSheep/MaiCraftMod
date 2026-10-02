package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.ToolSelect;
import org.maiwithu.maicraft.core.integration.machine.control.DriverStation;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotTaskRecord;
import org.maiwithu.maicraft.core.task.move.BoardStructureTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 停稳 -> 备料并靠近 -> 原生拆放 -> 服务端确认 -> 返回补丁差异；设计预测不参与施工准入。 */
public final class StructureEditTask extends AbstractCompanionTask<StructureEditTaskRecord> {
    private final List<Map<String,Object>> effects=new ArrayList<>();
    private Task preparation; private NativeActionReceipt action;
    private int index, approached=-1, aimTicks; private boolean placing; private long blockedSince=-1;
    private StructureWorksiteSearch worksites;
    private final List<Map<String,Object>> approachHistory=new ArrayList<>();
    private Map<String,Object> lastGaze=Map.of(),route=Map.of();
    private boolean relocating,mustReposition;
    private SableStructureBridge.Structure ship;
    private JsonArray declared;
    public StructureEditTask(LocalPlayer player,StructureEditTaskRecord record) { super(player,record); }
    @Override protected void onStart() { declared=PhysicalStructureDesign.merge(player.level(),r.structureId,r.edits); }
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        // 最后一块拆完后 Sable 可以删除整条结构；已经确认的动作仍按完成结算。
        if(index>=r.edits.size()) return TaskState.SUCCESS;
        if(preparation!=null) {
            // 局部站位路线失败后保留完整导航回执，再试尚未检查的站位；仍有进展的导航继续由原执行器推进。
            TaskState state=runChild(preparation);
            if(state==null) return TaskState.RUNNING;
            var result=preparation.result(state); preparation=null;
            if(relocating) {
                worksites.record(Map.of("route",route,"state",state.name(),"success",result.success(),"message",result.message(),"data",result.data()));
                relocating=false;mustReposition=!result.success();aimTicks=0;
                // 只对明确无路或搜索停滞换位；交通效果未明及内部错误必须原样交回，不能机械重试原生副作用。
                if(!result.success()&&lastFailure()!=FailureType.NO_PATH&&lastFailure()!=FailureType.PLANNING_STALL) {
                    fail(result.message(),lastFailure());return TaskState.FAILED;
                }
            } else if(!result.success()) { fail(result.message(),FailureType.UNKNOWN); return TaskState.FAILED; }
        }
        ship=SableStructureBridge.find(player.clientLevel,r.structureId);
        if(ship==null||ship.pose()==null||ship.plotCenter()==null) { fail("目标物理结构不可读",FailureType.TARGET_LOST); return TaskState.FAILED; }
        JsonObject edit=r.edits.get(index).getAsJsonObject(); BlockPos pos=position(edit).offset(ship.plotCenter());
        if(!ship.isLoaded(pos)) { fail("施工目标所在结构区块未加载",FailureType.TARGET_LOST); return TaskState.FAILED; }
        if(action!=null) {
            if(action.kind()==NativeActionReceipt.Kind.BREAK_BLOCK) action=ctx.actions().continueBreaking(ctx,action);
            else action=ctx.actions().poll(ctx,action);
            if(!action.terminal()) return TaskState.RUNNING;
            var completed=action; action=null;
            if(completed.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                effects.add(Map.of("index",index,"status",completed.status().name(),"detail",completed.detail()));
                fail("原生结构操作未确认: "+completed.detail(),FailureType.UNKNOWN); return TaskState.FAILED;
            }
            if(completed.kind()==NativeActionReceipt.Kind.CREATIVE_SET_SLOT) return TaskState.RUNNING;
            effects.add(Map.of("index",index,"action",placing?"place":"remove","native_confirmed",true));
            resetApproach();
            if(placing||wanted(edit).isAir()) index++;
            return TaskState.RUNNING;
        }
        BlockState actual=player.level().getBlockState(pos), desired=wanted(edit);
        if(actual.is(desired.getBlock()) && (desired.isAir()||matchesProperties(actual,edit))) { resetApproach();index++;return TaskState.RUNNING; }
        // 默认不在飞行中加配重；船体移动时等待停稳，不擅自关闭原本维持浮力的推进器。
        if(moving(ship)) {
            if(blockedSince<0) blockedSince=player.level().getGameTime();
            if(player.level().getGameTime()-blockedSince>200) { fail("起飞前改造需要先将结构停稳；尚未提交新的方块操作",FailureType.UNKNOWN); return TaskState.FAILED; }
            return TaskState.RUNNING;
        }
        blockedSince=-1;
        placing=actual.canBeReplaced()&&!desired.isAir();
        Item item=placing?desired.getBlock().asItem():null;
        int slot=placing?PlayerInv.findSlot(player.getInventory(),item):ToolSelect.bestSlot(player,actual);
        if(placing&&(slot<0||slot>=36)) {
            if(player.getAbilities().instabuild) {
                if(ctx.mutationAvailable()) action=ctx.actions().creativeSetSlot(ctx,player.getInventory().selected,new ItemStack(item,64),80);
            } else {
                var record=new SemanticAcquireTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),
                        List.of(BuiltInRegistries.ITEM.getKey(item)),1,SemanticAcquireTaskRecord.DEFAULT_SOURCES,false,
                        SemanticAcquireTaskRecord.SourceHint.empty(),List.of(),16).captureStorageOrigin(player);
                preparation=new SemanticAcquireCompanionTask(player,record);
            }
            return TaskState.RUNNING;
        }
        if(slot>=0&&slot!=player.getInventory().selected) {
            preparation=new SelectInventorySlotCompanionTask(player,new SelectInventorySlotTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),slot));
            return TaskState.RUNNING;
        }
        ctx.body().applyMovement(new BodyControlPort.Movement(0,0,false,placing,false),ctx.tickRevision());
        var faces=StructureEditTarget.targets(player.level(),ship::isLoaded,ship.pose(),pos,placing);
        lastGaze=StructureEditApproach.gaze(ctx,faces);
        if(faces.isEmpty()) { fail("目标格没有已加载的原生施工面",FailureType.NO_PATH);return TaskState.FAILED; }
        var click=StructureEditApproach.current(ctx,ship,pos,placing,faces);
        // 身在触及距离内也可能看不见外侧面；先选真正可见的地面施工站位，再交回普通导航。
        if(click==null||mustReposition) return approach(ctx,pos,faces);
        BlockPos support=click.support();Vec3 aim=click.world();Direction face=click.face();
        InputDriver.lookAt(player,aim); BlockHitResult hit=DriverStation.hit(player,ship,support);
        if(hit==null||placing&&(hit.getDirection()!=face||!player.isShiftKeyDown())) {
            if(++aimTicks>100) {
                ensureSearch(ctx,pos);worksites.record(Map.of("aim_failed",lastGaze));
                mustReposition=true;aimTicks=0;
            }
            return TaskState.RUNNING;
        }
        aimTicks=0;
        if(!ctx.mutationAvailable()) return TaskState.RUNNING;
        if(!placing) action=ctx.actions().startBreaking(ctx,hit,200);
        else {
            BlockState before=actual;
            action=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,new NativeConfirmation() {
                public boolean requiresBlockAcknowledgement() { return true; }
                public Verdict observe(LocalPlayerContext c) {
                    return !c.level().getBlockState(pos).equals(before)?Verdict.APPLIED:Verdict.PENDING;
                }
            },80);
        }
        return TaskState.RUNNING;
    }
    private void ensureSearch(LocalPlayerContext ctx,BlockPos pos) {
        if(worksites==null) worksites=new StructureWorksiteSearch(ship.pose().toWorld(Vec3.atCenterOf(pos)),player.position(),
                player.blockInteractionRange(),StructureEditApproach.eyeHeight(ctx,placing));
    }
    private TaskState approach(LocalPlayerContext ctx,BlockPos pos,List<StructureEditTarget.Click> faces) {
        ensureSearch(ctx,pos);var space=JetpackRoute.observed(ctx);
        var site=worksites.advance(feet->StructureEditApproach.probe(ctx,ship,pos,placing,faces,space,feet));
        if(site!=null) {
            preparation=new MoveToCompanionTask(player,MoveToTaskRecord.strictStance(r.getToolCallId(),r.getDeadlineGameTime(),site.landing().feet(),false));
            route=Map.of("kind","ground_worksite","feet_world",StructureEditApproach.vector(site.landing().landingPoint()));
        } else if(!worksites.exhausted()) return TaskState.RUNNING;
        else if(approached!=index) {
            // 地面候选耗尽时仍保留原来的登船路径；抵达甲板不算施工成功，下一刻必须重新验证目标面。
            approached=index;preparation=new BoardStructureTask(player,new BoardStructureTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),r.structureId,Vec3.atCenterOf(pos)));
            route=Map.of("kind","board_structure");
        } else {
            fail("附近地面站位及登船尝试均未建立可用施工视线；保留已完成效果、差异和站位回执",FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        relocating=true;aimTicks=0;return TaskState.RUNNING;
    }
    private void resetApproach() {
        // 换下一格或拆完后改为放置时，旧站位事实归档；新目标重新观察，不复用旧面和路线。
        if(worksites!=null) approachHistory.add(Map.of("index",index,"placing",placing,"search",worksites.diagnostics()));
        worksites=null;approached=-1;mustReposition=false;aimTicks=0;
    }
    private static boolean moving(SableStructureBridge.Structure s) {
        if(s.lastPose()==null) return true;
        Vec3 origin=Vec3.atLowerCornerOf(s.plotCenter());
        return s.pose().toWorld(origin).distanceToSqr(s.lastPose().toWorld(origin))>.0004
                || Math.abs(s.pose().orientationX()*s.lastPose().orientationX()+s.pose().orientationY()*s.lastPose().orientationY()
                +s.pose().orientationZ()*s.lastPose().orientationZ()+s.pose().orientationW()*s.lastPose().orientationW())<.99999;
    }
    private static BlockPos position(JsonObject edit) {
        var p=edit.getAsJsonObject("position"); return new BlockPos(p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt());
    }
    private static BlockState wanted(JsonObject edit) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(edit.get("block_id").getAsString()))
                .orElseThrow(()->new IllegalArgumentException("未知目标方块")).defaultBlockState();
    }
    private static boolean matchesProperties(BlockState state,JsonObject edit) {
        if(!edit.has("properties")) return true;
        for(var entry:edit.getAsJsonObject("properties").entrySet()) {
            var property=state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if(property==null||!state.getValue(property).toString().equalsIgnoreCase(entry.getValue().getAsString())) return false;
        }
        return true;
    }
    @Override public void stop(LocalPlayer companion,Task.StopReason why) {
        // 自救或其他任务接管身体时一并暂停施工移位，恢复后由同一子任务继续核对实际位置。
        if(preparation!=null) preparation.stop(companion,why);
        super.stop(companion,why);
    }
    @Override protected void cleanup() {
        var ctx=ClientRuntime.actor().activeContext().orElse(null);
        if(preparation!=null) {
            var result=preparation.result(TaskState.CANCELLED);preparation=null;
            // 外层取消或超时时仍交付这一段实际导航的收场证据，不把尚未到达的站位记为成功。
            if(relocating&&worksites!=null) worksites.record(Map.of("route",route,"state","CANCELLED",
                    "success",false,"message",result.message(),"data",result.data()));
            relocating=false;
        }
        if(ctx!=null&&action!=null) {
            if(action.kind()==NativeActionReceipt.Kind.BREAK_BLOCK) ctx.actions().cancelBreakingForTaskBoundary(ctx,action,"配平施工结束");
            else ctx.actions().retireOneShotForTaskBoundary(ctx,action,"配平施工结束");
        }
        super.cleanup();
    }
    @Override protected String successMessage() { return "结构补丁的原生施工已完成，配平结果需独立核验"; }
    @Override protected Map<String,Object> resultData() {
        var diff=new ArrayList<Map<String,Object>>();
        for(var raw:declared==null?r.edits:declared) {
            var edit=raw.getAsJsonObject(); BlockPos local=position(edit);
            BlockState actual=ship==null||!ship.isLoaded(local.offset(ship.plotCenter()))?null:player.level().getBlockState(local.offset(ship.plotCenter()));
            diff.add(Map.of("expected",edit,"actual",actual==null?"unknown_unloaded":actual.toString(),"matches",actual!=null&&actual.is(wanted(edit).getBlock())&&matchesProperties(actual,edit)));
        }
        return Map.of("structure_id",r.structureId.toString(),"completed_effects",List.copyOf(effects),"declared_structure_diff",diff,
                "design_scope","current_loaded_world","processed_targets",index,"total_targets",r.edits.size(),"balance_verified",false,
                "construction_approach",Map.of("history",List.copyOf(approachHistory),"current_search",worksites==null?Map.of():worksites.diagnostics(),"last_gaze",lastGaze));
    }
}
