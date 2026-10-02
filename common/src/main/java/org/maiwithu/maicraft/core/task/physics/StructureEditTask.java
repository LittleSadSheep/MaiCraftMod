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
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.SelectInventorySlotTaskRecord;
import org.maiwithu.maicraft.core.task.move.BoardStructureTask;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 停稳 -> 备料并靠近 -> 原生拆放 -> 服务端确认 -> 返回补丁差异；设计预测不参与施工准入。 */
public final class StructureEditTask extends AbstractCompanionTask<StructureEditTaskRecord> {
    private final List<Map<String,Object>> effects=new ArrayList<>();
    private Task preparation; private NativeActionReceipt action;
    private int index, approached=-1, aimTicks; private boolean placing; private long blockedSince=-1;
    private SableStructureBridge.Structure ship;
    private JsonArray declared;
    public StructureEditTask(LocalPlayer player,StructureEditTaskRecord record) { super(player,record); }
    @Override protected void onStart() { declared=PhysicalStructureDesign.merge(player.level(),r.structureId,r.edits); }
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        // 最后一块拆完后 Sable 可以删除整条结构；已经确认的动作仍按完成结算。
        if(index>=r.edits.size()) return TaskState.SUCCESS;
        if(preparation!=null) {
            TaskState state=runChild(preparation); if(state==null) return TaskState.RUNNING;
            var result=preparation.result(state); preparation=null;
            if(!result.success()) { fail(result.message(),FailureType.UNKNOWN); return TaskState.FAILED; }
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
            if(placing||wanted(edit).isAir()) { index++; approached=-1; }
            return TaskState.RUNNING;
        }
        BlockState actual=player.level().getBlockState(pos), desired=wanted(edit);
        if(actual.is(desired.getBlock()) && (desired.isAir()||matchesProperties(actual,edit))) { index++; approached=-1; return TaskState.RUNNING; }
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
                preparation=new SemanticAcquireCompanionTask(player,record); approached=-1;
            }
            return TaskState.RUNNING;
        }
        if(slot>=0&&slot!=player.getInventory().selected) {
            preparation=new SelectInventorySlotCompanionTask(player,new SelectInventorySlotTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),slot));
            return TaskState.RUNNING;
        }
        BlockPos support=pos; Vec3 aim=DriverStation.aim(player,ship,pos); Direction face=null;
        if(placing) {
            var click=StructureEditTarget.placement(player,ship,pos);
            if(click==null) { fail("目标格没有可点击的原生支撑面",FailureType.NO_PATH); return TaskState.FAILED; }
            support=click.support();face=click.face();aim=click.world();
        }
        if(aim==null||aim.distanceTo(player.getEyePosition())>player.blockInteractionRange()-.1) {
            if(approached==index) { fail("已靠近结构但施工格仍超出原生触及范围",FailureType.NO_PATH); return TaskState.FAILED; }
            approached=index; preparation=new BoardStructureTask(player,new BoardStructureTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),r.structureId,Vec3.atCenterOf(pos)));
            return TaskState.RUNNING;
        }
        ctx.body().applyMovement(new BodyControlPort.Movement(0,0,false,placing,false),ctx.tickRevision());
        InputDriver.lookAt(player,aim); BlockHitResult hit=DriverStation.hit(player,ship,support);
        if(hit==null||placing&&(hit.getDirection()!=face||!player.isShiftKeyDown())) {
            if(++aimTicks>100) { fail("原生视线持续无法命中施工面，保留已完成效果和剩余补丁",FailureType.NO_PATH); return TaskState.FAILED; }
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
    @Override protected void cleanup() {
        var ctx=ClientRuntime.actor().activeContext().orElse(null);
        if(preparation!=null) { preparation.result(TaskState.CANCELLED); preparation=null; }
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
                "design_scope","current_loaded_world","processed_targets",index,"total_targets",r.edits.size(),"balance_verified",false);
    }
}
