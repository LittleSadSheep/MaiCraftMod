// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Identity;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Position;

/** 通过 inspect_machine 的真实参数与适配器入口验证：默认 full 读现状，diff 才引用原设计。 */
public final class MachineInspectionModesTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var fields = new LinkedHashMap<Field,Object>();
        for (String name : List.of("catalog","level","playerId","issue")) { var field = field(ClientMachineCatalog.class,name); fields.put(field,field.get(null)); }
        var cached = map("compiledBlueprints"); var oldCache = new LinkedHashMap<>(cached);
        var pending = map("pendingBuilt"); var oldPending = new LinkedHashMap<>(pending);
        var catalog = new MachineCatalog(Files.createTempDirectory("machine-inspection-modes-"),Runnable::run);
        catalog.bind(new Identity("inspection-world","player"),"one");
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true); var runtime = constructor.newInstance();
        try (var h = new InteractionWorldTestHarness()) {
            UUID playerId = UUID.randomUUID(); field(Entity.class,"uuid").set(h.player,playerId);
            field(ClientMachineCatalog.class,"catalog").set(null,catalog); field(ClientMachineCatalog.class,"level").set(null,h.level);
            field(ClientMachineCatalog.class,"playerId").set(null,playerId); cached.clear(); pending.clear();
            var design = JsonParser.parseString("""
                    {"blocks":[{"offset":[3,1,3],"block_id":"minecraft:stone"},{"offset":[5,1,3],"block_id":"minecraft:stone"}]}
                    """).getAsJsonObject();
            String id = catalog.registerBlueprint("观测机","minecraft:overworld",new Position(0,0,0),design,100,
                    new Position(3,1,3),new Position(5,1,3));
            h.set(new BlockPos(3,1,3),Blocks.DIAMOND_BLOCK.defaultBlockState()); h.set(new BlockPos(4,1,3),Blocks.OAK_LOG.defaultBlockState());
            var parameters = new JsonObject(); parameters.addProperty("machine_id",id);
            var full = inspect(h,runtime,parameters);
            var actual = full.getAsJsonObject("as_built_blueprint");
            check(full.get("inspection_mode").getAsString().equals("full") && actual.get("capture_complete").getAsBoolean()
                            && actual.getAsJsonArray("blocks").size() == 2 && actual.toString().contains("minecraft:diamond_block")
                            && !actual.toString().contains("minecraft:stone"), "default full exports changed and additional world blocks, not the saved stone design");
            parameters.addProperty("mode","diff"); var diff = inspect(h,runtime,parameters).getAsJsonObject("blueprint_diff");
            check(diff.get("wrong_block").getAsInt() == 1 && diff.get("missing").getAsInt() == 1,
                    "explicit diff compares current blocks with the recorded design");
            // 用户在地图上再次修改机器，后续 full 必须重新读取；不能重复上一次检查或原设计的内容。
            h.set(new BlockPos(3,1,3),Blocks.IRON_BLOCK.defaultBlockState()); parameters.addProperty("mode","full");
            check(inspect(h,runtime,parameters).getAsJsonObject("as_built_blueprint").toString().contains("minecraft:iron_block"),
                    "later inspections read the actual current map");
            parameters.addProperty("mode","invalid");
            try { MachineAbilityAdapter.validate(goal(parameters)); throw new AssertionError("unknown inspection mode accepted"); }
            catch (IllegalArgumentException expected) { }
            check(catalog.blueprint(id).orElseThrow().blueprint().equals(design) && h.blockUses() == 0 && h.itemUses() == 0,
                    "reading current data does not overwrite the saved design or modify the world");
        } finally {
            for (var entry : fields.entrySet()) entry.getKey().set(null,entry.getValue());
            cached.clear(); cached.putAll(oldCache); pending.clear(); pending.putAll(oldPending);
        }
        System.out.println("MachineInspectionModesTest: full defaults to live world data; explicit diff uses the saved reference");
    }
    private static JsonObject inspect(InteractionWorldTestHarness h,IntentRuntime runtime,JsonObject parameters) throws Exception {
        var goal = goal(parameters); SemanticGoalContract.validate(goal,IntentRuntime.KNOWN_ABILITIES); MachineAbilityAdapter.validate(goal);
        var action = MachineAbilityAdapter.adapt(goal,h.player,runtime,null);
        if (!(action instanceof IntentAction.Native nativeAction)) throw new AssertionError("inspection did not create its native observation: " + action);
        var snapshot = (MachineSnapshots.Snapshot) field(nativeAction.record().getClass(),"snapshot").get(nativeAction.record());
        // 可选服务端补读不能覆盖已经从地图得到的全量布局或差异。
        return MachineSnapshots.enrich(h.player,snapshot,new JsonObject()).report();
    }
    private static Goal goal(JsonObject p) { return new Goal("maicraft:inspect_machine","读取机器",null,p.toString(),"{}",List.of(),List.of()); }
    private static Field field(Class<?> type,String name) throws Exception { var value = type.getDeclaredField(name); value.setAccessible(true); return value; }
    @SuppressWarnings("unchecked") private static Map<Object,Object> map(String name) throws Exception { return (Map<Object,Object>) field(ClientMachineCatalog.class,name).get(null); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
