package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
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
    private CompletableFuture<PhysicalStructureDesignStore.Registration> registration;
    private JsonObject declarationEvidence=new JsonObject();
    private Object declarationWorld;
    private boolean wrenching;
    private int rotations;
    private BlockState rotationState;
    private Set<Direction> rotationFaces=Set.of();
    private Map<String,Object> placementDiagnostics=Map.of();
    private Item placementItem;
    public StructureEditTask(LocalPlayer player,StructureEditTaskRecord record) { super(player,record); }
    @Override protected void onStart() {
        declarationWorld=player.level();declarationEvidence.addProperty("persistence_status","pending");
        registration=PhysicalStructureDesign.merge(player,r.structureId,r.edits);
    }
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        // 先恢复并落盘完整声明，再备料施工；存储故障是执行失败，历史未知或实际 diff 不符本身不会拦截施工。
        if(declarationWorld!=player.level()) { fail("登记结构设计后世界或维度已切换",FailureType.TARGET_LOST);return TaskState.FAILED; }
        if(declared==null) {
            if(!registration.isDone()) return TaskState.RUNNING;
            var saved=registration.join();declared=saved.targets();declarationEvidence=saved.evidence();
            if(!"saved".equals(declarationEvidence.get("persistence_status").getAsString())) {
                fail("无法持久登记整机声明；尚未提交本次施工动作，原记录和失败详情均保留",FailureType.UNKNOWN);return TaskState.FAILED;
            }
        }
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
            effects.add(Map.of("index",index,"action",wrenching?"rotate":placing?"place":"remove","native_confirmed",true,
                    "actual",player.level().getBlockState(pos).toString()));
            resetApproach();
            // 放下机翼后再核对明确朝向；可以原生扳手调向时继续同一格，不能仅因物品已落地就漏掉方向要求。
            BlockState live=player.level().getBlockState(pos);
            if(placing&&(matchesProperties(live,edit)||wrenchFaces(live,edit).isEmpty())||wanted(edit).isAir())finishTarget();
            return TaskState.RUNNING;
        }
        BlockState actual=player.level().getBlockState(pos), desired=wanted(edit);
        if(actual.is(desired.getBlock()) && (desired.isAir()||matchesProperties(actual,edit))) {finishTarget();return TaskState.RUNNING;}
        // 默认不在飞行中加配重；船体移动时等待停稳，不擅自关闭原本维持浮力的推进器。
        if(moving(ship)) {
            if(blockedSince<0) blockedSince=player.level().getGameTime();
            if(player.level().getGameTime()-blockedSince>200) { fail("起飞前改造需要先将结构停稳；尚未提交新的方块操作",FailureType.UNKNOWN); return TaskState.FAILED; }
            return TaskState.RUNNING;
        }
        blockedSince=-1;
        if(rotations>=12) {
            effects.add(Map.of("index",index,"property_adjustment","native_rotation_limit_reached","actual",actual.toString()));
            finishTarget();return TaskState.RUNNING;
        }
        var allowed=actual.is(desired.getBlock())?wrenchFaces(actual,edit):Set.<Direction>of();
        wrenching=!allowed.isEmpty();placing=!wrenching&&actual.canBeReplaced()&&!desired.isAir();
        Item item=wrenching?BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:wrench")):placing?desired.getBlock().asItem():null;
        int slot=item!=null?PlayerInv.findSlot(player.getInventory(),item):ToolSelect.bestSlot(player,actual);
        if(item!=null&&(slot<0||slot>=36)) {
            if(player.getAbilities().instabuild) {
                // 扳手和其他不可堆叠工具按原生堆叠上限领取，不能沿用施工方块的一组数量。
                if(ctx.mutationAvailable()) action=ctx.actions().creativeSetSlot(ctx,player.getInventory().selected,
                        new ItemStack(item,Math.min(64,item.getDefaultInstance().getMaxStackSize())),80);
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
        SlabType half=slabHalf(desired,edit);
        var faces=StructureEditTarget.targets(player.level(),ship::isLoaded,ship.pose(),pos,placing,half);
        // 普通右键扳手旋转、潜行右键却会拆走方块，因此只选择可达设计方向的真实命中面并保持站立。
        if(wrenching)faces=faces.stream().filter(candidate->allowed.contains(candidate.face())).toList();
        lastGaze=StructureEditApproach.gaze(ctx,faces);
        if(faces.isEmpty()) { fail("目标格没有已加载的原生施工面",FailureType.NO_PATH);return TaskState.FAILED; }
        var click=StructureEditApproach.current(ctx,ship,pos,placing,faces);
        // 身在触及距离内也可能看不见外侧面；先选真正可见的地面施工站位，再交回普通导航。
        if(click==null||mustReposition) return approach(ctx,pos,faces);
        BlockPos support=click.support();Vec3 aim=click.world();Direction face=click.face();
        InputDriver.lookAt(player,aim); BlockHitResult hit=DriverStation.hit(player,ship,support);
        if(hit==null||(placing||wrenching)&&hit.getDirection()!=face||placing&&!player.isShiftKeyDown()||wrenching&&player.isShiftKeyDown()
                ||placing&&half!=null&&click.preference()==0&&!slabHitMatches(player,desired,hit,half)) {
            if(++aimTicks>100) {
                ensureSearch(ctx,pos);worksites.record(Map.of("aim_failed",lastGaze));
                mustReposition=true;aimTicks=0;
            }
            return TaskState.RUNNING;
        }
        aimTicks=0;
        if(!ctx.mutationAvailable()) return TaskState.RUNNING;
        if(!placing&&!wrenching) action=ctx.actions().startBreaking(ctx,hit,200);
        else {
            if(wrenching)rotations++;
            BlockState before=actual;
            if(placing&&!wrenching)observePlacement(ctx,pos,hit,desired);
            action=ctx.actions().useBlock(ctx,InteractionHand.MAIN_HAND,hit,new NativeConfirmation() {
                public boolean requiresBlockAcknowledgement() { return true; }
                public Verdict observe(LocalPlayerContext c) {
                    return !c.level().getBlockState(pos).equals(before)?Verdict.APPLIED:Verdict.PENDING;
                }
            },80);
        }
        return TaskState.RUNNING;
    }
    private void observePlacement(LocalPlayerContext ctx,BlockPos target,BlockHitResult hit,BlockState desired) {
        var facts=new LinkedHashMap<String,Object>();placementItem=player.getMainHandItem().getItem();
        facts.put("target_index",index);facts.put("requested_block",BuiltInRegistries.BLOCK.getKey(desired.getBlock()).toString());
        facts.put("inventory_before",PlayerInv.count(player.getInventory(),placementItem));
        facts.put("source","client native placement-context observation; diagnostic only, never an execution gate");
        try {
            // 保留原生放置上下文的落点、生存条件和实体碰撞检查；即使检查不通过，下面仍尝试已授权的真实点击。
            var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,player.getMainHandItem(),hit);
            var at=context.getClickedPos();var state=desired.getBlock().getStateForPlacement(context);
            facts.put("native_position",List.of(at.getX(),at.getY(),at.getZ()));facts.put("target_matches",at.equals(target));
            facts.put("context_can_place",context.canPlace());facts.put("native_state",state==null?"unavailable":state.toString());
            if(state!=null) {
                facts.put("native_can_survive",state.canSurvive(ctx.level(),at));
                facts.put("native_entity_clearance",ctx.level().isUnobstructed(state,at,CollisionContext.of(player)));
            }
        } catch(RuntimeException unavailable){facts.put("observation_error",unavailable.toString());}
        placementDiagnostics=Map.copyOf(facts);
    }
    private static SlabType slabHalf(BlockState wanted,JsonObject edit) {
        if(!(wanted.getBlock() instanceof SlabBlock)||!edit.has("properties"))return null;
        var properties=edit.getAsJsonObject("properties");if(!properties.has("type"))return null;
        return switch(properties.get("type").getAsString()){case "bottom"->SlabType.BOTTOM;case "top"->SlabType.TOP;default->null;};
    }
    static boolean slabHitMatches(LocalPlayer player,BlockState wanted,BlockHitResult hit,SlabType half) {
        // 同一个面上的高低位置决定原生半砖类型；实际射线尚未落到所选半部时继续瞄准，不抢先点击。
        if(hit==null)return false;
        var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,player.getMainHandItem(),hit);
        var state=wanted.getBlock().getStateForPlacement(context);
        return state!=null&&state.hasProperty(SlabBlock.TYPE)&&state.getValue(SlabBlock.TYPE)==half;
    }
    private Set<Direction> wrenchFaces(BlockState actual,JsonObject edit) {
        if(actual!=rotationState) {
            rotationState=actual;
            try {rotationFaces=StructureWrenchPlan.faces(actual,edit);}
            catch(RuntimeException unavailable) {
                // 属性规划读取失败只保留实际差异，不抹去已经完成的拆放，也不伪造新的点击。
                rotationFaces=Set.of();effects.add(Map.of("index",index,"property_adjustment_unknown",unavailable.toString()));
            }
        }
        return rotationFaces;
    }
    private void finishTarget() {
        resetApproach();index++;rotations=0;rotationState=null;rotationFaces=Set.of();wrenching=false;
    }
    private void ensureSearch(LocalPlayerContext ctx,BlockPos pos) {
        if(worksites==null) worksites=new StructureWorksiteSearch(ship.pose().toWorld(Vec3.atCenterOf(pos)),player.position(),
                player.blockInteractionRange(),StructureEditApproach.eyeHeight(ctx,placing));
    }
    private TaskState approach(LocalPlayerContext ctx,BlockPos pos,List<StructureEditTarget.Click> faces) {
        ensureSearch(ctx,pos);var space=JetpackRoute.observed(ctx);
        var site=worksites.advance(feet->StructureEditApproach.probe(ctx,ship,pos,placing,faces,space,feet));
        if(site!=null) {
            // 单个施工站位失败就尝试下一面，不为这一个候选长时间复算“假如允许改地形”的路线。
            preparation=new MoveToCompanionTask(player,MoveToTaskRecord.strictStance(r.getToolCallId(),r.getDeadlineGameTime(),site.landing().feet(),false).withoutTerrainProbe());
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
        return StructureWrenchPlan.matches(state,edit);
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
    @Override public Map<String,Object> progress() {
        // 改造可能跨多个拆放、补料和走位阶段；公开当前目标及真实子任务，便于模型判断是否在前进。
        return Map.of("task",name(),"processed_targets",index,"total_targets",r.edits.size(),
                "phase",declared==null?"saving_design":action!=null?"confirming_native_action":preparation!=null
                        ?relocating?"approaching_target":"preparing_item":blockedSince>=0?"waiting_for_structure_stop":"choosing_gesture",
                "recorded_native_events",effects.size(),"preparation",preparation==null?Map.of():preparation.progress(),
                "placement_diagnostics",placementDiagnostics);
    }
    @Override protected Map<String,Object> resultData() {
        var diff=new ArrayList<Map<String,Object>>();
        for(var raw:declared==null?r.edits:declared) {
            var edit=raw.getAsJsonObject(); BlockPos local=position(edit);
            BlockState actual=ship==null||ship.plotCenter()==null||!ship.isLoaded(local.offset(ship.plotCenter()))?null:player.level().getBlockState(local.offset(ship.plotCenter()));
            // 旧设计的模组方块可能已卸载；仍展示该声明及当前实际值，不能因旧方块缺注册而丢掉整机回执。
            var expectedBlock=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(edit.get("block_id").getAsString()));
            diff.add(Map.of("expected",edit,"actual",actual==null?"unknown_unloaded":actual.toString(),"matches",
                    actual!=null&&expectedBlock.isPresent()&&actual.is(expectedBlock.get())&&matchesProperties(actual,edit)));
        }
        var placement=new LinkedHashMap<>(placementDiagnostics);
        if(placementItem!=null)placement.put("inventory_after",PlayerInv.count(player.getInventory(),placementItem));
        return Map.of("structure_id",r.structureId.toString(),"completed_effects",List.copyOf(effects),"declared_structure_diff",diff,
                "design_scope","persistent_world_dimension_structure","design_declaration",declarationEvidence,
                "processed_targets",index,"total_targets",r.edits.size(),"balance_verified",false,
                "placement_diagnostics",placement,
                "construction_approach",Map.of("history",List.copyOf(approachHistory),"current_search",worksites==null?Map.of():worksites.diagnostics(),"last_gaze",lastGaze));
    }
}
