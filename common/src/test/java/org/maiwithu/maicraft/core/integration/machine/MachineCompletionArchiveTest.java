// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

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
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.Identity;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;

/** 完工入口自动记录普通机器并附一次 diff；随后发生的差异不能把已完成施工改判成失败。 */
public final class MachineCompletionArchiveTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var saved = new LinkedHashMap<Field,Object>();
        for (String name : List.of("catalog","level","playerId","issue")) { var field = field(ClientMachineCatalog.class,name); saved.put(field,field.get(null)); }
        var cache = map("compiledBlueprints"); var oldCache = new LinkedHashMap<>(cache);
        var pending = map("pendingBuilt"); var oldPending = new LinkedHashMap<>(pending);
        var directory = Files.createTempDirectory("machine-completion-archive-");
        var identity = new Identity("completion-world","player"); var catalog = new MachineCatalog(directory,Runnable::run);
        catalog.bind(identity,"first");
        try (var h = new InteractionWorldTestHarness()) {
            UUID playerId = UUID.randomUUID(); field(Entity.class,"uuid").set(h.player,playerId);
            field(ClientMachineCatalog.class,"catalog").set(null,catalog); field(ClientMachineCatalog.class,"level").set(null,h.level);
            field(ClientMachineCatalog.class,"playerId").set(null,playerId); cache.clear(); pending.clear();
            var blueprint = JsonParser.parseString("{\"blocks\":[{\"offset\":[3,1,3],\"block_id\":\"minecraft:stone\"}]}").getAsJsonObject();
            var plan = MachineConstructionPlan.compile(BlockPos.ZERO,MachineBlueprintDocument.compile(blueprint,MachineConstructionPlan.registry()),false);
            var record = new MachineBuildTaskRecord("archive",1000,plan,"minecraft:overworld",MaterialPolicy.INVENTORY_ONLY,List.of(),"简易机器");
            var task = new MachineBuildTask(h.player,record); BlockPos at = new BlockPos(3,1,3);
            h.set(at,Blocks.STONE.defaultBlockState());
            check(catalog.blueprints().isEmpty(),"an uncompleted machine is not automatically marked built");
            check(invoke(task,"verify") == TaskState.RUNNING && catalog.blueprints().size() == 1,
                    "actual structural completion records even a machine with no external inputs");
            // 完工后世界又有变化，默认 diff 必须报告出来，但不得重新放块或要求 LLM 恢复施工。
            h.set(at,Blocks.GOLD_BLOCK.defaultBlockState()); h.nextTick();
            check(invoke(task,"compareCompletedMachine") == TaskState.SUCCESS,"a difference remains an observation, not a construction failure");
            var result = task.result(TaskState.SUCCESS); var diff = (JsonObject) result.data().get("blueprint_diff");
            check(result.success() && diff.get("wrong_block").getAsInt() == 1 && result.data().containsKey("recorded_machine"),
                    "completion receipt includes the saved identity and a default current-world diff");
            var built = catalog.blueprints().getFirst();
            check(built.label().equals("简易机器") && built.lastBuildState().equals("success")
                            && h.blockUses() == 0 && h.itemUses() == 0,"archiving and post-build comparison never mutate the world");
            var restored = new MachineCatalog(directory,Runnable::run); restored.bind(identity,"second");
            check(restored.blueprint(built.id()).orElseThrow().blueprint().equals(plan.blueprint()),"automatic completion records survive reconnect");
        } finally {
            for (var entry : saved.entrySet()) entry.getKey().set(null,entry.getValue());
            cache.clear(); cache.putAll(oldCache); pending.clear(); pending.putAll(oldPending);
        }
        System.out.println("MachineCompletionArchiveTest: automatic completion archive and non-blocking default diff passed");
    }
    private static Object invoke(Object task,String name) throws Exception { var method = MachineBuildTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task); }
    private static Field field(Class<?> type,String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    @SuppressWarnings("unchecked") private static Map<Object,Object> map(String name) throws Exception { return (Map<Object,Object>) field(ClientMachineCatalog.class,name).get(null); }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
