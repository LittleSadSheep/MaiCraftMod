// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Identity;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Position;

/** 普通机器的蓝图、原地编号和历史施工状态跨会话保留；目录只引用不可变蓝图文件。 */
public final class MachineBlueprintCatalogTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("machine-blueprint-catalog-");
        var identity = new Identity("world-one", "player"); var anchor = new Position(8,64,8);
        var catalog = new MachineCatalog(directory, Runnable::run); catalog.bind(identity, "first");
        var document = JsonParser.parseString("""
                {"schema_version":1,"expected_output":"minecraft:cobblestone","blocks":[
                  {"offset":[0,0,0],"block_id":"minecraft:stone"},
                  {"offset":[1,0,0],"block_id":"minecraft:water","properties":{"level":"0"}}]}
                """).getAsJsonObject();
        String id = catalog.registerBlueprint("刷石机", "minecraft:overworld", anchor, document, 100);
        var planned = catalog.blueprint(id).orElseThrow();
        catalog.recordBlueprintState(id,planned.fingerprint(),"running",200);
        catalog.recordBlueprintState(id,planned.fingerprint(),"success",300); catalog.saveAsync().join();
        // 不依赖当前任务或短期快照，重新连接后仍能定位原机器并取出完整蓝图。
        var restored = new MachineCatalog(directory,Runnable::run); restored.bind(identity,"second");
        var built = restored.blueprintAt("minecraft:overworld",anchor).orElseThrow();
        check(built.id().equals(id) && built.blueprint().equals(document) && built.builtAtMillis() == 300
                && built.lastBuildState().equals("success"), "machine identity, blueprint and completion survive reconnect");
        check(!built.summary().get("current_world_verified").getAsBoolean(), "historical construction cannot claim current world agreement");
        String index = Files.readString(directory.resolve(identity.key()+".json"));
        check(!index.contains("minecraft:water") && Files.exists(directory.resolve(identity.key()+"-blueprints").resolve(built.fingerprint()+".json")),
                "the bounded index references a separate complete blueprint");
        var copy = built.blueprint(); copy.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block_id","minecraft:gold_block");
        check(built.blueprint().equals(document), "reading a blueprint cannot mutate its archived revision");
        restored.registerBlueprint("刷石机","minecraft:overworld",anchor,copy,400);
        var revised = restored.blueprint(id).orElseThrow();
        restored.recordBlueprintState(id,built.fingerprint(),"success",500);
        check(revised.builtAtMillis() == 0 && restored.blueprint(id).orElseThrow().lastBuildState().equals("planned"),
                "late completion of an old revision cannot complete the revised machine");
        // 平台锚点相同仍是两台命名机器，不能让新设计覆盖已有机器或由无名称查询随意选中一台。
        String secondId = restored.registerBlueprint("装配机","minecraft:overworld",anchor,document,450);
        check(!secondId.equals(id) && restored.blueprints().size() == 2
                && restored.blueprintAt("minecraft:overworld",anchor).isEmpty()
                && restored.blueprintAt("minecraft:overworld",anchor,"刷石机").orElseThrow().id().equals(id),
                "co-located named machines retain independent blueprints");
        restored.saveAsync().join();
        var shared = new MachineCatalog(directory,Runnable::run); shared.bind(identity,"shared");
        check(shared.ready() && shared.blueprints().size() == 2, "both co-located machines survive reload");
        // 已保存的旧地点编号继续可读，不通过改名或伪造新图纸迁移旧机器。
        var legacy = new MachineBlueprint(MachineBlueprint.locationId(identity.key(),built.dimension(),anchor),built.label(),
                built.dimension(),anchor,built.blueprintJson(),built.fingerprint(),built.lastBuildState(),100,300);
        var oldIndex = CatalogCodec.encode(new CatalogCodec.Snapshot(identity.key(),List.of(),List.of(),List.of(),List.of(legacy)));
        check(CatalogCodec.decode(oldIndex,identity.key(),fingerprint -> legacy.blueprintJson()).blueprints().getFirst().id().equals(legacy.id()),
                "legacy machine identity remains readable");
        restored.bind(new Identity("world-two","player"),"third");
        check(restored.ready() && restored.blueprints().isEmpty(), "machine records cannot leak into another world");
        // 蓝图文件受损时不能把另一份结构当成用户保存的目标；失败不会改写原目录。
        var file = directory.resolve(identity.key()+"-blueprints").resolve(revised.fingerprint()+".json");
        Files.writeString(file,document.toString());
        var corrupt = new MachineCatalog(directory,Runnable::run); corrupt.bind(identity,"fourth");
        check(!corrupt.ready() && corrupt.status().state() == MachineCatalog.State.FAILED, "a corrupted blueprint is not silently accepted");
        System.out.println("MachineBlueprintCatalogTest: complete blueprints persist separately with world and revision identity");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
