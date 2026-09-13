// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStore;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 同项目两格开孔只改未来施工要求；模型父链、坐标和真实原支撑均通过后才替换项目本体。 */
public final class BuildProjectRevisionTest {
    private static final String WORLD = "a".repeat(64), DIMENSION = "minecraft:overworld";
    private static final BlockPos FIRST = new BlockPos(6, 1, 6), SECOND = FIRST.above(), SUPPORT = new BlockPos(3, 1, 3);
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var root = Files.createTempDirectory("maicraft-project-revision-"); var identity = new StateIdentity(WORLD, root);
            var scenes = new BuildingSceneStore(identity); var projects = new BuildProjectStore(identity);
            var parent = scenes.save(scene(), new Goal.WorldPosition(0, 0, 0, DIMENSION));
            var child = scenes.update(parent.sceneId(), DIMENSION, json("""
                    {"objects":[{"name":"cover","modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"opening"}]},
                     {"name":"opening","type":"cube","role":"cutter","location":[6.5,2,6.5],"dimensions":[1,2,1]}]}
                    """));
            var oldTargets = List.of(target(FIRST, false), target(SECOND, false), target(SUPPORT, true));
            var revised = List.of(target(FIRST, true), target(SECOND, true), target(SUPPORT, true));
            JsonObject arguments = json("""
                    {"material_policy":"storage_available","protected_labels":["garden"],"replace_existing":true,
                     "allow_partial":true,"exact_states":true,"semantic_contract":{"design_source":"llm_authored_model"}}
                    """);
            arguments.getAsJsonObject("semantic_contract").addProperty("scene_id", parent.sceneId());
            String id = projects.save(DIMENSION, arguments, oldTargets);
            Path project = root.resolve("build-projects").resolve(WORLD).resolve(id + ".json");
            Path sidecar = project.resolveSibling(id + ".scaffolds.json");
            h.set(FIRST, Blocks.POLISHED_ANDESITE.defaultBlockState()); h.set(SECOND, Blocks.POLISHED_ANDESITE.defaultBlockState());
            h.set(SUPPORT, LOG);
            var oldPlan = plan(id, oldTargets); projects.bindScaffolds(oldPlan, h.level);
            // 仅夹具模拟此前已经取得的原生支撑确认；修订入口不能补造这一事实。
            oldPlan.scaffoldLedger().confirmed(SUPPORT, LOG);
            var before = Snapshot.read(project, sidecar); JsonObject oldArguments = projects.load(id, DIMENSION);
            rejectUnchanged(before, project, sidecar, () -> projects.reviseFromScene(id, h.level, UUID.randomUUID().toString(), child.sceneId(), revised));
            var unrelated = scenes.save(scene(), parent.anchor());
            rejectUnchanged(before, project, sidecar, () -> projects.reviseFromScene(id, h.level, parent.sceneId(), unrelated.sceneId(), revised));
            rejectUnchanged(before, project, sidecar, () -> projects.reviseFromScene(id, h.level, parent.sceneId(), child.sceneId(), revised.subList(0, 2)));
            rejectUnchanged(before, project, sidecar, () -> projects.reviseFromScene(id, h.level, parent.sceneId(), child.sceneId(),
                    List.of(revised.getFirst(), revised.get(1), target(SUPPORT.east(), true))));
            rejectUnchanged(before, project, sidecar, () -> projects.reviseFromScene(id, h.level, parent.sceneId(), child.sceneId(),
                    List.of(revised.getFirst(), revised.get(1), target(SUPPORT, false))));
            // 同种原木轴向变了也不是原记录；已经消失的支撑需独立结算，修订时不能悄悄覆盖旧账。
            for (BlockState changed : List.of(Blocks.OAK_LOG.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
                h.set(SUPPORT, changed);
                rejectUnchanged(before, project, sidecar, () -> projects.reviseFromScene(id, h.level, parent.sceneId(), child.sceneId(), revised));
            }
            h.set(SUPPORT, LOG);
            var result = projects.reviseFromScene(id, h.level, parent.sceneId(), child.sceneId(), revised);
            check(result.changedTargets() == 2 && result.retainedScaffolds() == 1 && result.projectId().equals(id),
                    "revision keeps the original project identity and reports two changed targets plus one retained support");
            JsonObject restored = new BuildProjectStore(identity).load(id, DIMENSION);
            check(BuildProjectTargets.decode(restored.getAsJsonArray("project_targets")).stream().allMatch(t -> t.desiredState().isAir()),
                    "subsequent project reads contain both new opening cells");
            JsonObject expected = oldArguments.deepCopy(); expected.add("project_targets", BuildProjectTargets.encode(revised));
            expected.getAsJsonObject("semantic_contract").addProperty("scene_id", child.sceneId());
            expected.getAsJsonObject("semantic_contract").addProperty("previous_scene_id", parent.sceneId());
            check(restored.equals(expected), "supply policy, protections and all other old arguments remain unchanged");
            var resumed = plan(id, BuildProjectTargets.decode(restored.getAsJsonArray("project_targets")));
            projects.bindScaffolds(resumed, h.level);
            check(resumed.scaffoldLedger().snapshot().equals(Map.of(SUPPORT, LOG)) && resumed.completed() == 0 && resumed.placed() == 0,
                    "the revised project binds its original native support ledger without inventing construction completion");
            check(before.sidecar().equals(Files.readString(sidecar)) && before.sidecarTime().equals(Files.getLastModifiedTime(sidecar)),
                    "successful revision and binding leave the original support sidecar untouched");
            check(h.level.getBlockState(FIRST).is(Blocks.POLISHED_ANDESITE) && h.level.getBlockState(SECOND).is(Blocks.POLISHED_ANDESITE)
                    && h.blockUses() == 0 && h.itemUses() == 0, "editing the plan does not dig either opening or mutate the player's materials");
            var after = Snapshot.read(project, sidecar);
            rejectUnchanged(after, project, sidecar, () -> projects.reviseFromScene(id, h.level, parent.sceneId(), child.sceneId(), revised));
        }
        System.out.println("BuildProjectRevisionTest: scene lineage, fixed coordinates, preserved scaffold ownership and atomic refusal passed");
    }
    private static BuildTaskRecord plan(String id, List<BuildTaskRecord.Target> targets) {
        var plan = new BuildTaskRecord("revise-resume", 1000, targets, true); plan.project(id, ignored -> {}); return plan;
    }
    private static BuildTaskRecord.Target target(BlockPos at, boolean air) {
        return new BuildTaskRecord.Target(air ? Blocks.AIR : Blocks.POLISHED_ANDESITE, air ? Items.AIR : Items.POLISHED_ANDESITE,
                at, "opening", null, null, null);
    }
    private static JsonObject scene() { return json("""
            {"schema_version":1,"coordinate_system":"minecraft_y_up",
             "materials":{"stone":{"block_id":"minecraft:polished_andesite"},"air":{"block_id":"minecraft:air"}},
             "objects":[{"name":"cover","type":"cube","location":[6.5,2,6.5],"dimensions":[1,2,1],"material":"stone"},
                        {"name":"support_clearance","type":"cube","location":[3.5,1.5,3.5],"dimensions":[1,1,1],"material":"air"}]}
            """); }
    private record Snapshot(String project, FileTime projectTime, String sidecar, FileTime sidecarTime) {
        static Snapshot read(Path project, Path sidecar) throws Exception {
            return new Snapshot(Files.readString(project), Files.getLastModifiedTime(project), Files.readString(sidecar), Files.getLastModifiedTime(sidecar));
        }
    }
    private static void rejectUnchanged(Snapshot before, Path project, Path sidecar, Runnable revision) throws Exception {
        boolean rejected = false;
        try { revision.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected && before.equals(Snapshot.read(project, sidecar)), "invalid revision must not overwrite the project or its native support ledger");
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
