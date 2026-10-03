package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.server.PhysicsSnapshotReader;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsSimulation;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsTrim;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsWrench;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsComputation;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 完整采样 -> 后台试算 -> 交付证据；只有明确 apply 才先执行补丁，再重读实际结构。 */
public final class PhysicalBalanceTask extends AbstractCompanionTask<PhysicalBalanceTaskRecord> {
    private static final ThreadPoolExecutor COMPUTE=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),run->{var thread=new Thread(run,"maicraft-preflight-physics");thread.setDaemon(true);return thread;});
    private PhysicsSnapshotReader reader;
    private StructureEditTask edit;
    private TaskResult construction;
    private CompletableFuture<JsonObject> computation;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private JsonObject report=new JsonObject();
    private boolean applied;
    private long observeAfter;
    public PhysicalBalanceTask(LocalPlayer player,PhysicalBalanceTaskRecord record) { super(player,record); }
    @Override protected TaskState onTick() {
        if(r.parameters.operation().equals("apply")&&!applied) {
            if(edit==null) edit=new StructureEditTask(player,new StructureEditTaskRecord(r.getToolCallId(),r.getDeadlineGameTime(),
                    r.parameters.structureId(),r.parameters.request().getAsJsonArray("edits")));
            var state=runChild(edit); if(state==null) return TaskState.RUNNING;
            construction=edit.result(state); edit=null; applied=true; observeAfter=player.level().getGameTime()+5;
            report.add("construction",new Gson().toJsonTree(construction.data()));
            if(!construction.success()) { fail(construction.message(),FailureType.UNKNOWN); return TaskState.FAILED; }
        }
        if(player.level().getGameTime()<observeAfter) return TaskState.RUNNING;
        try {
            if(computation!=null) {
                if(!computation.isDone()) return TaskState.RUNNING;
                JsonObject calculated=computation.join();
                for(var entry:calculated.entrySet()) report.add(entry.getKey(),entry.getValue());
                return TaskState.SUCCESS;
            }
            if(reader==null) {
                var parameters=r.parameters.request();
                // 施工后的读取观察真实新船，不能把同一份补丁再叠加一次导致配重翻倍。
                if(applied) parameters.remove("edits");
                reader=new PhysicsSnapshotReader(player,parameters);
            }
            var observation=reader.tick(player); if(observation==null) return TaskState.RUNNING;
            reader.close(); reader=null;
            computation=CompletableFuture.supplyAsync(()->analyze(observation),COMPUTE);
            return TaskState.RUNNING;
        } catch(RuntimeException unavailable) {
            report.addProperty("analysis_unavailable",unavailable.getMessage());
            // 已确认的拆放不会因随后无法观察物理状态而改判失败；未证实的配平另行标注。
            if(applied&&construction.success()) return TaskState.SUCCESS;
            fail("物理分析未完成: "+unavailable.getMessage(),FailureType.UNKNOWN); return TaskState.FAILED;
        }
    }
    private JsonObject analyze(PhysicsSnapshotReader.Observation observation) {
        var gson=new Gson(); var out=new JsonObject();
        var measured=observation.measured(); var model=observation.preflight();
        // 参考航速只用于候选工况，实测箭头仍保留原生速度，避免把假设飞行冒充已经发生。
        if(r.parameters.referenceVelocity()!=null)model=model.movingAt(r.parameters.referenceVelocity());
        out.addProperty("structure_id",measured.structureId().toString()); out.addProperty("snapshot_id",observation.snapshotId().toString());
        out.addProperty("observed_tick",measured.tick()); out.addProperty("dimension",measured.dimension());
        out.addProperty("workflow","preflight"); out.addProperty("native_flight_verified",false);
        out.add("origin_storage",gson.toJsonTree(observation.origin()));
        out.add("measured_body",gson.toJsonTree(measured)); out.add("preflight_body",gson.toJsonTree(model));
        if(r.parameters.referenceVelocity()!=null)out.add("reference_velocity",gson.toJsonTree(r.parameters.referenceVelocity()));
        out.add("measured_forces",gson.toJsonTree(PhysicsWrench.evaluate(measured,measured.rotation(),measured.position(),
                measured.angularVelocity(),Map.of(),1)));
        out.addProperty("coordinate_rule","补丁坐标是 origin_storage 的局部方块偏移；力向量的 BODY/WORLD 坐标系独立标注");
        out.addProperty("verification_rule","停在地面不证明飞行稳定；预测通过后仍需验证实际动力、供气和启停过程");
        try(var budget=PhysicsComputation.begin(cancelled::get)) {
        if(r.parameters.operation().equals("recommend")) {
            var recommendation=PhysicsTrim.recommend(model,observation.candidates(),r.parameters.maxBallastBlocks(),
                    r.parameters.controls(),r.parameters.limits());
            out.add("recommendation",gson.toJsonTree(recommendation));
            var patch=new JsonArray();
            for(var placement:recommendation.placements()) {
                var cell=new JsonObject();var position=new JsonObject();
                position.addProperty("x",(int)Math.floor(placement.point().x()));position.addProperty("y",(int)Math.floor(placement.point().y()));
                position.addProperty("z",(int)Math.floor(placement.point().z()));cell.add("position",position);cell.addProperty("block_id",placement.blockId());patch.add(cell);
            }
            out.add("suggested_edits",patch);
            out.addProperty("recommendation_search","最多 64 个附着候选格，比较启停工况的最差偏差；没有找到不等于不存在其他方案");
        } else out.add("prediction",gson.toJsonTree(PhysicsSimulation.assess(model,r.parameters.limits(),r.parameters.controls())));
        } catch(PhysicsComputation.Limit exhausted) { out.addProperty("analysis_incomplete",exhausted.getMessage()); }
        return out;
    }
    @Override protected void cleanup() {
        cancelled.set(true);
        if(reader!=null) reader.close();
        if(computation!=null&&!computation.isDone()) computation.cancel(true);
        if(edit!=null) { edit.result(TaskState.CANCELLED); edit=null; }
        super.cleanup();
    }
    @Override protected String successMessage() { return applied?"配平补丁施工已完成，实际差异与预测分别返回":"起飞前受力分析与配平预测已完成"; }
    @Override protected Map<String,Object> resultData() { return Map.of("physics_balance",report,"construction_started",applied||edit!=null); }
}
