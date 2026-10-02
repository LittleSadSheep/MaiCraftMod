package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 用实际 SQLite 验证结构声明跨重启、隔离、并发与写失败，不靠进程内缓存冒充保存。 */
public final class PhysicalStructureDesignStoreTest {
    static final String DIM="minecraft:overworld";
    static final UUID SHIP=UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static void run() throws Exception {
        Path workspace=Path.of("").toRealPath(),folder=Files.createTempDirectory(workspace,"physical-design-");
        try {
            var identity=new StateIdentity("a".repeat(64),folder);var store=new PhysicalStructureDesignStore(identity);
            JsonArray top=patch(0,1,0,"minecraft:oak_planks");
            check(saved(store.merge(DIM,SHIP,top,PhysicalStructureDesignStoreTest::history)).size()==1,"首份声明未写入");
            top.get(0).getAsJsonObject().addProperty("block_id","minecraft:air");
            var resumed=new PhysicalStructureDesignStore(identity).merge(DIM,SHIP,patch(3,0,0,"minecraft:iron_block"),()->{throw new AssertionError("已迁移设计不能重复导入旧回执");});
            check(saved(resumed).size()==2&&saved(resumed).get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:oak_planks"),"重启丢失旧声明或保存内容被参数串改");
            var replacement=store.merge(DIM,SHIP,patch(0,1,0,"minecraft:air"),PhysicalStructureDesignStoreTest::history);
            check(saved(replacement).size()==2&&saved(replacement).get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:air"),"同格新声明必须覆盖旧目标且保留其他格");
            check(saved(store.merge("minecraft:the_nether",SHIP,patch(1,1,1,"minecraft:stone"),PhysicalStructureDesignStoreTest::history)).size()==1,"维度串用声明");
            check(saved(store.merge(DIM,UUID.randomUUID(),patch(1,1,1,"minecraft:stone"),PhysicalStructureDesignStoreTest::history)).size()==1,"结构 UUID 串用声明");
            var other=new StateIdentity("b".repeat(64),folder);
            check(saved(new PhysicalStructureDesignStore(other).merge(DIM,SHIP,patch(2,2,2,"minecraft:stone"),PhysicalStructureDesignStoreTest::history)).size()==1,"同库不同世界串用声明");
            // 两个同时接受的补丁都从独立存储器发起，必须保留两边目标，不能后写覆盖前写。
            var gate=new CountDownLatch(2);
            var first=concurrent(identity,4,gate);var second=concurrent(identity,5,gate);
            CompletableFuture.allOf(first,second).get(20,TimeUnit.SECONDS);
            check(saved(store.merge(DIM,SHIP,new JsonArray(),PhysicalStructureDesignStoreTest::history)).size()==4,"并发补丁丢失声明");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+identity.databaseFile());var sql=c.createStatement()) {
                sql.execute("CREATE TRIGGER reject_physical_update BEFORE UPDATE ON memory_records WHEN NEW.scope LIKE '%/physical-structure-designs' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            }
            var rejected=store.merge(DIM,SHIP,patch(9,0,0,"minecraft:gold_block"),PhysicalStructureDesignStoreTest::history);
            check(rejected.evidence().get("persistence_status").getAsString().equals("unavailable"),"写失败不能冒称已保存");
            check(rejected.targets().size()==5,"失败回执必须保留旧声明及本次未落盘补丁");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+identity.databaseFile());var sql=c.createStatement()) {sql.execute("DROP TRIGGER reject_physical_update");}
            check(saved(new PhysicalStructureDesignStore(identity).merge(DIM,SHIP,new JsonArray(),PhysicalStructureDesignStoreTest::history)).size()==4,"失败事务污染了保存的整机设计");
            // 存档内容损坏时保留原件，既不当新结构，也不拿本次单格补丁替换整机历史。
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+identity.databaseFile());var sql=c.createStatement()) {
                sql.executeUpdate("UPDATE memory_records SET payload='broken' WHERE scope='state/physical-structure-designs'");
            }
            check(store.merge(DIM,SHIP,patch(8,0,0,"minecraft:stone"),PhysicalStructureDesignStoreTest::history)
                    .evidence().get("persistence_status").getAsString().equals("unavailable"),"坏记录被当成空历史");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+identity.databaseFile());var sql=c.createStatement();var rows=sql.executeQuery("SELECT payload FROM memory_records")) {
                while(rows.next()) check(rows.getString(1).equals("broken"),"坏记录被覆写");
            }
        } finally {
            // 只清理本回归新建的测试目录，不接触正式游戏数据库。
            if(!folder.toRealPath().startsWith(workspace)) throw new AssertionError("fixture escaped workspace");
            try(var files=Files.walk(folder)) {for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    private static CompletableFuture<Void> concurrent(StateIdentity identity,int x,CountDownLatch gate) {
        return CompletableFuture.runAsync(()->{
            gate.countDown();try {if(!gate.await(5,TimeUnit.SECONDS))throw new AssertionError("parallel merge did not start");}
            catch(InterruptedException interrupted){throw new AssertionError(interrupted);}
            saved(new PhysicalStructureDesignStore(identity).merge(DIM,SHIP,patch(x,0,0,"minecraft:oak_planks"),PhysicalStructureDesignStoreTest::history));
        });
    }
    static JsonArray patch(int x,int y,int z,String block) {
        var cells=new JsonArray();var cell=new JsonObject();var position=new JsonObject();
        position.addProperty("x",x);position.addProperty("y",y);position.addProperty("z",z);
        cell.add("position",position);cell.addProperty("block_id",block);cells.add(cell);return cells;
    }
    private static PhysicalStructureDesignStore.History history() {
        return new PhysicalStructureDesignStore.History(new JsonArray(),JsonParser.parseString("{\"status\":\"fixture\",\"prior_history_complete\":false}").getAsJsonObject(),false);
    }
    static JsonArray saved(PhysicalStructureDesignStore.Registration registration) {
        check(registration.evidence().get("persistence_status").getAsString().equals("saved"),registration.evidence().toString());return registration.targets();
    }
    static void check(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
}
