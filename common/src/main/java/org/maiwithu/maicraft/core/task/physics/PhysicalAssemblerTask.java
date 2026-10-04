package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.server.AssemblyObservationReader;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.physics.PhysicalAssemblyParameters.Operation;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 继承设计 -> 走位 -> 登记原生观察 -> 拉杆请求一次 -> 等待真实转换 -> 保存新坐标下的整机声明。 */
public final class PhysicalAssemblerTask extends AbstractCompanionTask<PhysicalAssemblerTaskRecord> {
    private final Level world;
    private final AssemblyApproach approach=new AssemblyApproach();
    private PhysicalAssemblyFrame frame;
    private AssemblyObservationReader observer;
    private NativeActionReceipt action;
    private JsonObject observation,designEvidence=new JsonObject();
    private JsonArray declarations=new JsonArray();
    private CompletableFuture<PhysicalStructureDesignStore.Registration> registration;
    private boolean prepared,submitted,followingExisting,converted,finished;
    private UUID resultStructure;
    private String afterReadError;
    private long movingSince=-1;
    private AABB glueRegion;
    private BlockPos resultWorldAnchor;
    public PhysicalAssemblerTask(LocalPlayer player,PhysicalAssemblerTaskRecord record) {super(player,record);world=player.level();}
    @Override protected void onStart() {
        registration=AssemblyDesignSession.prepare(player,r.parameters,r.anchor,r.dimension);
    }
    @Override protected TaskState onTick() {
        var ctx=ClientRuntime.requireContext(player);
        if(player.level()!=world||!world.dimension().location().toString().equals(r.dimension))return failure("组装期间世界或维度改变",FailureType.TARGET_LOST);
        if(finished)return TaskState.SUCCESS;
        if(submitted||followingExisting) {
            try {return awaitResult();}
            catch(RuntimeException after) {
                // 已有服务器原生搬移证据时，后续转换或读取失败只作为未知项，不抹去已发生效果。
                if(!transformed())throw after;
                afterReadError=after.toString();frame=null;finished=true;return TaskState.SUCCESS;
            }
        }
        if(!prepared) {
            if(registration!=null) {
                if(!registration.isDone())return TaskState.RUNNING;
                var saved=registration.join();declarations=saved.targets();designEvidence=saved.evidence();registration=null;
                String expected=r.parameters.operation()==Operation.INSPECT?"read":"saved";
                if(!expected.equals(designEvidence.get("persistence_status").getAsString()))return failure("完整设计未能读取或保存，尚未提交原生请求",FailureType.UNKNOWN);
            }
            prepared=true;
        }
        frame=PhysicalAssemblyFrame.read(player,r.parameters.structureId(),r.anchor);
        glueRegion=r.parameters.region(frame.origin());
        if(r.parameters.operation()==Operation.INSPECT) {finished=true;return TaskState.SUCCESS;}
        if(!frame.stationary()) {
            if(movingSince<0)movingSince=world.getGameTime();
            if(world.getGameTime()-movingSince>200)return failure("原生组装/拆回前需要先让结构停稳",FailureType.NO_PATH);
            return TaskState.RUNNING;
        }
        movingSince=-1;
        BlockPos offset=r.parameters.assembler(),target=frame.storage(offset);
        if(!frame.loaded(target)||!NativeAssemblyApi.assembler(player.level().getBlockEntity(target)))return failure("当前目标不是已加载的物理组装器",FailureType.TARGET_LOST);
        if(!approach.ready(player,frame,offset,false,r.getToolCallId(),r.getDeadlineGameTime())) {
            if(approach.failure()!=null)return failure(approach.failure(),FailureType.NO_PATH);return TaskState.RUNNING;
        }
        if(!ctx.menus().ensureWorldVisible(ctx))return TaskState.RUNNING;
        InputDriver.halt(player);InputDriver.sneak(player,false);
        var aim=frame.aim(player,offset,player.getEyePosition(),false);if(aim==null)return TaskState.RUNNING;
        InputDriver.lookAt(player,aim);
        if(player.isShiftKeyDown()||frame.actualHit(player,offset,false)==null)return TaskState.RUNNING;
        if(!player.mayBuild())return failure("当前玩家没有原生建造权限",FailureType.UNKNOWN);
        if(observer==null)observer=new AssemblyObservationReader(player,target);
        observation=observer.tick(player);if(observation==null)return TaskState.RUNNING;
        String before=observation.has("before_structure_id")?observation.get("before_structure_id").getAsString():null;
        if(r.parameters.operation()==Operation.ASSEMBLE?before!=null:!r.parameters.structureId().toString().equals(before))
            return failure("组装器原生状态与请求方向不同，不能把创建请求变成拆回或操作别的结构",FailureType.TARGET_LOST);
        // 同一原生请求已经开始时只继续观察，不能为了恢复任务而再次切换拉杆。
        if(observation.get("native_input_observed").getAsBoolean()) {followingExisting=true;return TaskState.RUNNING;}
        if(!ctx.mutationAvailable())return TaskState.RUNNING;
        var payload=NativeAssemblyApi.assemblyPacket(target);submitted=true;
        action=ctx.actions().submitControlProtocol(ctx,"physical assembler "+r.parameters.operation(),
                ()->ctx.connection().send(new ServerboundCustomPayloadPacket(payload)),fresh->observation!=null
                        &&observation.get("native_input_observed").getAsBoolean()?NativeConfirmation.Verdict.APPLIED:NativeConfirmation.Verdict.PENDING,100);
        return TaskState.RUNNING;
    }
    private TaskState awaitResult() {
        var ctx=ClientRuntime.requireContext(player);
        observation=observer.tick(player);
        if(action!=null&&!action.terminal())action=ctx.actions().poll(ctx,action);
        if(action!=null&&action.terminal()&&action.status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED
                &&(observation==null||!observation.get("native_input_observed").getAsBoolean()))
            return failure("原生输入未确认，保留观察回执且不重放组装请求",FailureType.UNKNOWN);
        if(observation==null||!observation.get("complete").getAsBoolean())return TaskState.RUNNING;
        // 服务器可能一刻内完成搬移；先结清原生输入的稳定确认，避免任务收尾把已执行拉杆记为未知。
        if(action!=null&&!action.terminal())return TaskState.RUNNING;
        String outcome=observation.has("outcome")?observation.get("outcome").getAsString():"unknown";
        // 原生处理完却未转换也可结清请求；只有真实 assembled/disassembled 才迁移设计并报告结构改变。
        boolean changed=outcome.equals("assembled")||outcome.equals("disassembled");
        if(!changed&&observation.has("observation_error"))return failure(observation.get("observation_error").getAsString(),FailureType.UNKNOWN);
        if(!changed&&observation.has("native_handler_interrupted"))return failure("原生组装入口被异常中断，结果未完整确认",FailureType.UNKNOWN);
        if(changed&&!converted) {
            converted=true;
            if(outcome.equals("assembled")) {
                resultStructure=UUID.fromString(observation.get("structure_id").getAsString());
                BlockPos offset=AssemblyDesignMapping.point(observation.getAsJsonArray("world_to_storage_offset"));
                glueRegion=glueRegion.move(Vec3.atLowerCornerOf(offset));
                declarations=AssemblyDesignMapping.assembled(declarations,r.anchor,offset,
                        AssemblyDesignMapping.point(observation.getAsJsonArray("origin_storage")));
                registration=PhysicalStructureDesign.merge(player,resultStructure,declarations);
            } else {
                BlockPos from=AssemblyDesignMapping.point(observation.getAsJsonArray("storage_anchor")),to=AssemblyDesignMapping.point(observation.getAsJsonArray("world_anchor"));
                Rotation rotation=Rotation.valueOf(observation.get("rotation").getAsString());
                glueRegion=AABB.encapsulatingFullBlocks(frame.storage(r.parameters.first()).subtract(from).rotate(rotation).offset(to),
                        frame.storage(r.parameters.second()).subtract(from).rotate(rotation).offset(to));
                declarations=AssemblyDesignMapping.movedToWorld(declarations,frame.origin(),AssemblyDesignMapping.point(observation.getAsJsonArray("storage_anchor")),
                        AssemblyDesignMapping.point(observation.getAsJsonArray("world_anchor")),Rotation.valueOf(observation.get("rotation").getAsString()));
                resultWorldAnchor=to;
                registration=AssemblyDesignSession.saveDisassembled(player,to,declarations);
                frame=new PhysicalAssemblyFrame(player.clientLevel,null,BlockPos.ZERO);
            }
        }
        if(registration!=null) {
            if(!registration.isDone())return TaskState.RUNNING;
            var saved=registration.join();declarations=saved.targets();designEvidence=saved.evidence();registration=null;
            if(resultWorldAnchor!=null)frame=new PhysicalAssemblyFrame(player.clientLevel,null,resultWorldAnchor);
            // 结构已经由原生创建，后续声明存储失败必须保留真实效果，不能把它改写为“没有组装”。
        }
        if(resultStructure!=null) {
            try {frame=PhysicalAssemblyFrame.read(player,resultStructure,BlockPos.ZERO);}
            catch(RuntimeException unavailable) {frame=null;afterReadError=unavailable.getMessage();}
        }
        finished=true;return TaskState.SUCCESS;
    }
    private TaskState failure(String why,FailureType type) {fail(why,type);return TaskState.FAILED;}
    private boolean transformed() {
        if(observation==null||!observation.has("outcome"))return false;
        return List.of("assembled","disassembled").contains(observation.get("outcome").getAsString());
    }
    @Override public void stop(LocalPlayer companion,Task.StopReason why) {approach.stop(companion,why);super.stop(companion,why);}
    @Override protected void cleanup() {
        approach.close();if(observer!=null)observer.close();
        var ctx=ClientRuntime.actor().activeContext().orElse(null);
        if(ctx!=null&&action!=null&&!action.terminal())ctx.actions().retireOneShotForTaskBoundary(ctx,action,"物理组装任务结束");
        super.cleanup();
    }
    @Override protected String successMessage() {return r.parameters.operation()==Operation.INSPECT?"原生胶层与声明目标已观察":"原生组装器请求已结算，实际结构结果与设计差异分别返回";}

    /** 面板行动行的一句话汇报；阶段来自原生请求提交状态与结构停稳观察。 */
    @Override
    public String describeCurrentAction() {
        if (submitted || followingExisting) return "正在等待原生组装器结果";
        if (registration != null || !prepared) return "正在读取整机设计";
        if (frame != null && !frame.stationary()) return "正在等待物理结构停稳";
        return switch (r.parameters.operation()) {
            case INSPECT -> "正在观察整机胶层与声明目标";
            case ASSEMBLE -> "正在走近组装器准备组装结构";
            case DISASSEMBLE -> "正在走近组装器准备拆回结构";
            default -> "正在操作物理组装器";
        };
    }

    @Override protected Map<String,Object> resultData() {
        var out=new LinkedHashMap<String,Object>();out.put("operation",r.parameters.operation().name().toLowerCase(Locale.ROOT));
        out.put("native_submitted",submitted);out.put("continued_native_observation",followingExisting);
        out.put("native_observation",observation==null?new JsonObject():observation);out.put("design_declaration",designEvidence);
        if(action!=null)out.put("input_receipt",Map.of("status",action.status().name(),"detail",action.detail()));
        if(designEvidence.has("world_design_id"))out.put("design_id",designEvidence.get("world_design_id").getAsString());
        if(resultWorldAnchor!=null)out.put("world_anchor",List.of(resultWorldAnchor.getX(),resultWorldAnchor.getY(),resultWorldAnchor.getZ()));
        out.put("declared_structure_diff",AssemblyDeclarationView.diff(frame,declarations));out.put("declarations",declarations.deepCopy());
        out.put("approach",approach.evidence());out.put("flight_verified",false);
        String outcome=observation!=null&&observation.has("outcome")?observation.get("outcome").getAsString():"not_observed";
        out.put("structure_changed",outcome.equals("assembled")||outcome.equals("disassembled"));
        out.put("completed_effects",outcome.equals("assembled")||outcome.equals("disassembled")?List.of(observation.deepCopy()):List.of());
        if(resultStructure!=null)out.put("structure_id",resultStructure.toString());if(afterReadError!=null)out.put("after_observation_unknown",afterReadError);
        if(frame!=null) {
            try {
                var area=glueRegion==null?r.parameters.region(frame.origin()):glueRegion;out.put("glue_observation_complete",frame.regionLoaded(area));
                out.put("glue",NativeAssemblyApi.bonds(player.clientLevel,area).stream().map(NativeAssemblyApi.Bond::evidence).toList());
                if(!converted) {
                    BlockPos at=frame.storage(r.parameters.assembler());var be=frame.loaded(at)?player.level().getBlockEntity(at):null;
                    out.put("assembler",Map.of("present",NativeAssemblyApi.assembler(be),"native_error",NativeAssemblyApi.failure(be)));
                }
            } catch(RuntimeException unavailable) {out.put("after_observation_unknown",unavailable.toString());}
        }
        return out;
    }
}
