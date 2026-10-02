package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import static org.maiwithu.maicraft.core.task.physics.PhysicalStructureDesignStoreTest.*;

/** 用旧任务原始回执恢复整机声明；模拟、别的世界及缺维度记录不能被伪装成本结构历史。 */
public final class PhysicalStructureDesignRecoveryTest {
    public static void run() throws Exception {
        var earlier=patch(3,0,0,"minecraft:oak_planks");earlier.addAll(patch(0,1,0,"minecraft:oak_planks"));
        JsonObject checkpoint=new JsonObject();var tasks=new JsonArray();checkpoint.add("tasks",tasks);
        // 故意把较新的单格补丁排在旧整机回执前，恢复顺序应取原生观测刻而非数组位置。
        tasks.add(task("later",DIM,"apply",200,patch(3,0,0,"minecraft:iron_block")));
        tasks.add(task("earlier",DIM,"apply",100,earlier));
        tasks.add(task("simulation",DIM,"simulate",300,patch(5,0,0,"minecraft:stone")));
        tasks.add(task("other_dimension","minecraft:the_nether","apply",400,patch(6,0,0,"minecraft:stone")));
        tasks.add(task("no_dimension",null,"apply",500,patch(7,0,0,"minecraft:stone")));
        var history=PhysicalStructureDesignRecovery.recover(checkpoint,DIM,SHIP);
        check(history.targets().size()==2,"必须恢复旧顶板，不能混入模拟、别的维度或身份未知的目标");
        check(history.targets().get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:iron_block"),"较新同格声明没有覆盖旧目标");
        check(!history.evidence().get("prior_history_complete").getAsBoolean(),"留存任务不能证明旧历史完整");
        check(history.evidence().getAsJsonArray("unknowns").size()==1,"缺维度记录不能静默丢弃");
        var collision=new JsonObject();var ties=new JsonArray();collision.add("tasks",ties);
        ties.add(task("one",DIM,"apply",100,patch(1,0,0,"minecraft:oak_planks")));
        ties.add(task("two",DIM,"apply",100,patch(1,0,0,"minecraft:iron_block")));
        var unresolved=PhysicalStructureDesignRecovery.recover(collision,DIM,SHIP);
        check(unresolved.targets().isEmpty()&&unresolved.evidence().getAsJsonArray("unknowns").size()==1,"次序冲突应保留两个声明而非猜一个");
        Path workspace=Path.of("").toRealPath(),folder=Files.createTempDirectory(workspace,"physical-recovery-");
        try {
            var identity=new StateIdentity("c".repeat(64),folder);
            checkpoint.addProperty("version",1);checkpoint.addProperty("identity_key",identity.key());
            new MemoryDatabase(identity.databaseFile()).write(identity.scope(),identity.key(),checkpoint.toString(),Map.of(),false);
            var store=new PhysicalStructureDesignStore(identity);
            var merged=store.merge(DIM,SHIP,patch(9,0,0,"minecraft:gold_block"),()->PhysicalStructureDesignRecovery.load(identity,DIM,SHIP));
            check(saved(merged).size()==3,"数据库旧任务未迁移为完整声明");
            // 迁移成功后移走旧任务检查点，新进程仍必须从独立设计记录恢复全部目标。
            new MemoryDatabase(identity.databaseFile()).deleteRecord(identity.scope(),identity.key(),"");
            check(saved(new PhysicalStructureDesignStore(identity).merge(DIM,SHIP,new JsonArray(),()->{throw new AssertionError("不应再依赖旧任务");})).size()==3,"重启后仍依赖已经移走的旧任务");
            var foreign=new StateIdentity("d".repeat(64),folder);
            var empty=PhysicalStructureDesignRecovery.load(foreign,DIM,SHIP);
            check(empty.targets().isEmpty()&&!empty.evidence().get("prior_history_complete").getAsBoolean(),"其他世界或缺失历史必须保持未知");
        } finally {
            if(!folder.toRealPath().startsWith(workspace))throw new AssertionError("fixture escaped workspace");
            try(var files=Files.walk(folder)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    private static JsonObject task(String id,String dimension,String operation,long tick,JsonArray declarations) {
        var task=new JsonObject();task.addProperty("id",id);
        var parameters=new JsonObject();parameters.addProperty("operation",operation);parameters.addProperty("structure_id",SHIP.toString());
        var goal=new JsonObject();goal.addProperty("ability","maicraft:physical_balance");goal.add("parameters",parameters);
        var steps=new JsonArray();steps.add(goal);task.add("steps",steps);task.add("attempts",new JsonArray());
        var report=new JsonObject();report.addProperty("structure_id",SHIP.toString());report.addProperty("observed_tick",tick);
        if(dimension!=null)report.addProperty("dimension",dimension);
        var construction=new JsonObject();construction.addProperty("structure_id",SHIP.toString());var diff=new JsonArray();
        for(var declaration:declarations){var row=new JsonObject();row.add("expected",declaration.deepCopy());row.addProperty("actual","not_used_for_design");diff.add(row);}
        construction.add("declared_structure_diff",diff);report.add("construction",construction);
        var data=new JsonObject();data.add("physics_balance",report);var result=new JsonObject();result.add("data",data);
        var completed=new JsonArray();var step=new JsonObject();step.addProperty("index",0);step.add("result",result);completed.add(step);
        task.add("completed_steps",completed);return task;
    }
}
