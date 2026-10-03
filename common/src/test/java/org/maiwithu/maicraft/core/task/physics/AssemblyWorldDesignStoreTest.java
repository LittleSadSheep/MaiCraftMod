package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 世界设计换锚点和重启后仍保留全部明确目标，不把另一世界或维度的同号设计接入当前组装。 */
public final class AssemblyWorldDesignStoreTest {
    public static void run() throws Exception {
        Path workspace=Path.of("").toRealPath(),folder=Files.createTempDirectory(workspace,"assembly-world-design-");
        try {
            var identity=new StateIdentity("e".repeat(64),folder);var store=new AssemblyWorldDesignStore(identity);
            var targets=PhysicalStructureDesignStoreTest.patch(0,0,0,"minecraft:oak_planks");
            targets.addAll(PhysicalStructureDesignStoreTest.patch(3,0,0,"minecraft:oak_planks"));
            BlockPos anchor=new BlockPos(100,50,200);
            var first=store.merge("minecraft:overworld",null,anchor,targets,true);
            UUID id=UUID.fromString(first.evidence().get("world_design_id").getAsString());
            var updated=new AssemblyWorldDesignStore(identity).merge("minecraft:overworld",id,anchor.west(),
                    PhysicalStructureDesignStoreTest.patch(1,0,0,"minecraft:iron_block"),true);
            check(updated.targets().size()==2,"更新单格丢失整机其余声明");
            check(AssemblyDesignMapping.point(updated.targets().get(1).getAsJsonObject()).equals(new BlockPos(4,0,0)),"换锚点实际移动了旧世界目标");
            var restored=new AssemblyWorldDesignStore(identity).merge("minecraft:overworld",id,anchor,new JsonArray(),false);
            check(restored.targets().size()==2&&AssemblyDesignMapping.point(restored.targets().get(0).getAsJsonObject()).equals(BlockPos.ZERO),"重启后世界声明没有按原位置恢复");
            check(restored.targets().get(0).getAsJsonObject().get("block_id").getAsString().equals("minecraft:iron_block"),"同格新声明没有保留");
            check(restored.evidence().get("persistence_status").getAsString().equals("read"),"只读复查被冒称为新保存");
            rejects(()->store.merge("minecraft:the_nether",id,anchor,new JsonArray(),true));
            var other=new AssemblyWorldDesignStore(new StateIdentity("f".repeat(64),folder));
            rejects(()->other.merge("minecraft:overworld",id,anchor,new JsonArray(),true));
            rejects(()->store.merge("minecraft:overworld",UUID.randomUUID(),anchor,new JsonArray(),true));
            var unchanged=store.merge("minecraft:overworld",id,anchor,new JsonArray(),false);
            check(unchanged.targets().equals(restored.targets()),"拒绝跨世界/维度后原声明遭到覆盖");
        } finally {
            // 仅回收本测试创建的临时库，不清理真实存档和配置。
            if(!folder.toRealPath().startsWith(workspace))throw new AssertionError("fixture escaped workspace");
            try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private interface Attempt {void run() throws Exception;}
    private static void rejects(Attempt operation) throws Exception {
        try {operation.run();}catch(IOException|IllegalArgumentException expected){return;}
        throw new AssertionError("无效世界设计引用被接受");
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
