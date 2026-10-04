package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireCompanionTask;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.CreativeTakeItemsTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 备胶 -> 原生可达的首点和末点 -> 提交一次原生选区请求 -> 核对新胶实体与真实材料变化。 */
public final class PhysicalBondTask extends AbstractCompanionTask<PhysicalBondTaskRecord> {
    private final Level world;
    private final AssemblyApproach approach=new AssemblyApproach();
    private final FirstPersonActionGate selection=new FirstPersonActionGate();
    private Task supply;
    private NativeActionReceipt action;
    private List<NativeAssemblyApi.Bond> before=List.of(),after=List.of(),created=List.of();
    private Map<String,Object> materialBefore=Map.of(),materialAfter=Map.of(),supplyEvidence=Map.of();
    private AABB region;
    private Item adhesive;
    private boolean firstSelected,submitted,alreadyBonded,confirmed;
    private long movingSince=-1;
    private PhysicalAssemblyFrame frame;
    private JsonArray declarations=new JsonArray();
    private JsonObject designEvidence=new JsonObject();
    private CompletableFuture<PhysicalStructureDesignStore.Registration> design;
    private int selectionRetries;
    private BondMaterialSettlement materialSettlement;
    public PhysicalBondTask(LocalPlayer player,PhysicalBondTaskRecord record) {super(player,record);world=player.level();}
    @Override protected void onStart() {design=AssemblyDesignSession.prepare(player,r.parameters,r.anchor,r.dimension);}
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        if(player.level()!=world||!world.dimension().location().toString().equals(r.dimension))return stopWith("粘接期间世界或维度改变",FailureType.TARGET_LOST);
        if(design!=null) {
            if(!design.isDone())return TaskState.RUNNING;
            var saved=design.join();declarations=saved.targets();designEvidence=saved.evidence();design=null;
            if(!"saved".equals(designEvidence.get("persistence_status").getAsString()))return stopWith("完整粘接设计未能保存，尚未发出原生请求",FailureType.UNKNOWN);
        }
        if(action!=null) {
            action=ctx.actions().poll(ctx,action);if(!action.terminal())return TaskState.RUNNING;
            materialAfter=material();
            if(action.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED)return stopWith("未能确认原生粘接效果；保留材料和胶层事实，不重放请求",FailureType.UNKNOWN);
            confirmed=true;
            // 新胶层先同步时继续只读等待背包结算，防止下一轮设计把尚未到达的耐久扣减当成免费粘接。
            if(materialSettlement==null)materialSettlement=new BondMaterialSettlement(materialBefore,world.getGameTime());
            return materialSettlement.observe(world.getGameTime(),materialAfter)?TaskState.SUCCESS:TaskState.RUNNING;
        }
        if(supply!=null) {
            TaskState state=runChild(supply);if(state==null)return TaskState.RUNNING;
            var result=supply.result(state);supply=null;supplyEvidence=result.data();
            if(!result.success())return stopWith(result.message(),FailureType.UNKNOWN);
        }
        frame=PhysicalAssemblyFrame.read(player,r.parameters.structureId(),r.anchor);
        region=r.parameters.region(frame.origin());
        if(!frame.regionLoaded(region))return stopWith("胶水选区包含未加载区块，不能把未读到的胶层当作不存在",FailureType.TARGET_LOST);
        after=NativeAssemblyApi.bonds(player.clientLevel,region);
        // 现场已经有同种胶完整覆盖时只交付观察；无需再登上停稳位置或取第二份胶来制造重复效果。
        if(after.stream().anyMatch(b->NativeAssemblyApi.covers(b,r.parameters.adhesive(),region))) {
            alreadyBonded=true;return TaskState.SUCCESS;
        }
        if(!frame.stationary()) {
            if(movingSince<0)movingSince=world.getGameTime();
            if(world.getGameTime()-movingSince>200)return stopWith("起飞前粘接需要先让物理结构停稳",FailureType.NO_PATH);
            return TaskState.RUNNING;
        }
        movingSince=-1;
        if(!player.mayBuild())return stopWith("当前玩家没有原生建造权限",FailureType.UNKNOWN);
        adhesive=BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(r.parameters.adhesive().item))
                .orElseThrow(()->new IllegalStateException("当前安装没有所选胶水物品"));
        BlockPos point=firstSelected?r.parameters.second():r.parameters.first();
        boolean honey=r.parameters.adhesive()==PhysicalAssemblyParameters.Adhesive.HONEY;
        // 高处换位可能正在取落地水桶或准备飞行；先完成已开始的走位，胶水选择不能每刻抢回它的快捷栏。
        if(approach.moving()&&!approach.ready(player,frame,point,honey,r.getToolCallId(),r.getDeadlineGameTime())) {
            if(approach.failure()!=null)return stopWith(approach.failure(),FailureType.NO_PATH);
            return TaskState.RUNNING;
        }
        // 已经提交的换槽先结清，不能因物品暂在搬运中就另开补料或抹掉未确认交易。
        if(selection.pending()) {
            var state=selection.select(player,selection.requestedSlot());
            if(state==FirstPersonActionGate.Status.FAILED)return stopWith(selection.failure(),FailureType.UNKNOWN);
            return TaskState.RUNNING;
        }
        int slot=PlayerInv.findSlot(player.getInventory(),adhesive);
        if(slot<0||slot>=36) {
            // 创造模式仍通过原生领取任务拿胶，不直接改背包；生存模式复用真实供料链。
            if(player.getAbilities().instabuild) {
                supply=new CreativeTakeItemsCompanionTask(player,new CreativeTakeItemsTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),new ItemStack(adhesive),1));
                selection.reset();return TaskState.RUNNING;
            }
            var request=new SemanticAcquireTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),List.of(BuiltInRegistries.ITEM.getKey(adhesive)),1,
                    SemanticAcquireTaskRecord.DEFAULT_SOURCES,false,SemanticAcquireTaskRecord.SourceHint.empty(),List.of(),16).captureStorageOrigin(player);
            supply=new SemanticAcquireCompanionTask(player,request);selection.reset();return TaskState.RUNNING;
        }
        var equipped=selection.select(player,slot);
        if(equipped==FirstPersonActionGate.Status.FAILED) {
            if(!selection.pending()&&++selectionRetries<=2) {selection.reset();return TaskState.RUNNING;}
            return stopWith(selection.failure(),FailureType.UNKNOWN);
        }
        if(equipped!=FirstPersonActionGate.Status.READY)return TaskState.RUNNING;
        if(!player.getMainHandItem().is(adhesive)) {selection.reset();return TaskState.RUNNING;}
        selectionRetries=0;
        if(!approach.ready(player,frame,point,honey,r.getToolCallId(),r.getDeadlineGameTime())) {
            if(approach.failure()!=null)return stopWith(approach.failure(),FailureType.NO_PATH);
            return TaskState.RUNNING;
        }
        InputDriver.halt(player);InputDriver.sneak(player,false);
        var aim=frame.aim(player,point,player.getEyePosition(),honey);if(aim==null)return TaskState.RUNNING;
        InputDriver.lookAt(player,aim);
        if(player.isShiftKeyDown()||frame.actualHit(player,point,honey)==null)return TaskState.RUNNING;
        if(!firstSelected) {firstSelected=true;return TaskState.RUNNING;}
        if(!ctx.mutationAvailable())return TaskState.RUNNING;
        if(submitted)return stopWith("原生胶水请求已经提交，不能机械重放",FailureType.UNKNOWN);
        if(!player.getMainHandItem().is(adhesive))return stopWith("提交前主手胶水已改变",FailureType.UNKNOWN);
        before=after;materialBefore=material();submitted=true;
        var payload=NativeAssemblyApi.bondPacket(r.parameters.adhesive(),frame.storage(r.parameters.first()),frame.storage(r.parameters.second()));
        action=ctx.actions().submitControlProtocol(ctx,"physical glue "+r.parameters.adhesive().item,
                ()->ctx.connection().send(new ServerboundCustomPayloadPacket(payload)),NativeConfirmation.serverObservedEntity(fresh->{
                    if(fresh.level()!=world)return NativeConfirmation.Verdict.DIVERGED;
                    after=NativeAssemblyApi.bonds(fresh.level(),region);created=NativeAssemblyApi.newBonds(before,after,r.parameters.adhesive(),region);
                    return created.isEmpty()?NativeConfirmation.Verdict.PENDING:NativeConfirmation.Verdict.APPLIED;
                }),100);
        return TaskState.RUNNING;
    }
    private Map<String,Object> material() {
        int count=0;long remaining=0;
        for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            ItemStack stack=player.getInventory().getItem(slot);if(!stack.is(adhesive))continue;
            count+=stack.getCount();remaining+=(long)stack.getCount()*Math.max(0,stack.getMaxDamage()-stack.getDamageValue());
        }
        return Map.of("item",r.parameters.adhesive().item,"count",count,"remaining_durability",remaining,"creative",player.getAbilities().instabuild);
    }
    private TaskState stopWith(String why,FailureType type) {fail(why,type);return TaskState.FAILED;}
    @Override public void stop(LocalPlayer companion,Task.StopReason why) {
        approach.stop(companion,why);if(supply!=null)supply.stop(companion,why);super.stop(companion,why);
    }
    @Override protected void cleanup() {
        approach.close();selection.reset();
        if(supply!=null) {supply.result(TaskState.CANCELLED);supply=null;}
        var ctx=ClientRuntime.actor().activeContext().orElse(null);
        if(ctx!=null&&action!=null&&!action.terminal())ctx.actions().retireOneShotForTaskBoundary(ctx,action,"粘接任务结束");
        super.cleanup();
    }
    @Override protected String successMessage() {return alreadyBonded?"选区已有观察到的同类胶层，未重复消耗胶水":"原生粘接已确认，机械连接及组装结果需独立核验";}
    @Override public Map<String,Object> progress() {
        // 模型能看到正在选择哪一个端点及真实导航阶段，避免把长时间备降或等待结算误认为没有进展。
        BlockPos point=firstSelected?r.parameters.second():r.parameters.first();
        return Map.of("task",name(),"selection_endpoint",firstSelected?"second":"first",
                "point_offset",List.of(point.getX(),point.getY(),point.getZ()),"native_submitted",submitted,
                "phase",design!=null?"saving_design":confirmed?"settling_glue_material":action!=null?"confirming_native_glue"
                        :approach.moving()?"approaching_endpoint":selection.pending()?"preparing_glue_hand":"aiming_endpoint",
                "approach",approach.evidence(),"supply",supply==null?Map.of():supply.progress());
    }
    @Override protected Map<String,Object> resultData() {
        var result=new LinkedHashMap<String,Object>(Map.of("operation","bond","native_submitted",submitted,"native_confirmed",confirmed,"already_bonded_observed",alreadyBonded,
                "glue_before",before.stream().map(NativeAssemblyApi.Bond::evidence).toList(),"glue_after",after.stream().map(NativeAssemblyApi.Bond::evidence).toList(),
                "completed_effects",created.stream().map(NativeAssemblyApi.Bond::evidence).toList(),"material",Map.of("before",materialBefore,"after",materialAfter),
                "approach",approach.evidence(),"supply",supplyEvidence));
        result.put("design_declaration",designEvidence);result.put("declared_structure_diff",AssemblyDeclarationView.diff(frame,declarations));
        if(materialSettlement!=null)result.put("material_settlement",materialSettlement.evidence());
        if(designEvidence.has("world_design_id"))result.put("design_id",designEvidence.get("world_design_id").getAsString());
        return result;
    }
}
