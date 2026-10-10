// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.remember.RememberAbility;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.OverheadGrid;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.perception.SubtitleEar;
import org.maiwithu.maicraft.behavior.travel.SceneSeenTargets;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** observe 的四种视图：场景、自己、细节（观察编号或地标名）、世界记忆；不在世界里时如实回答。 */
class ObserveToolTest {

    /** 本刻上下文替身：刻号由测试推进；角色上下文替身只在看背包的用例里给。 */
    private record Tick(long gameTick, PlayerContext player) implements TickContext {
        Tick(long gameTick) {
            this(gameTick, null);
        }
    }

    /** 只带一个背包视图的角色上下文替身：observe(self) 读背包不碰玩家对象。 */
    private static PlayerContext playerWithBackpack(BackpackView backpack) {
        return new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return null; }
            @Override public long clientTick() { return 10; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return false; }
            @Override public boolean tryClaimInteraction() { return false; }
            @Override public BackpackView backpack() { return backpack; }
        };
    }

    @TempDir
    Path temp;

    private Scene scene;
    private WorldMemory memory;
    private boolean inWorld = true;
    /** 本刻的角色上下文替身：默认没有（不在世界里或还没接上），看背包的用例先摆好。 */
    private PlayerContext heldPlayer;
    private ToolDispatcher tools;

    @BeforeEach
    void world() {
        memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "a".repeat(64));
        scene = new Scene(memory, new FacilityKinds(FakeBlockTags.vanilla()));
        // 角色站在原点，脸朝北；正前方 12 格一只僵尸盯着它，右边 4 格一只箱子。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 18, 300, 300, "minecraft:stone_pickaxe", List.of(), List.of(), true, false));
        scene.updateEntities(10, List.of(new EntitySight.Observation(1, "minecraft:zombie", null,
                WorldPosition.here(0, 64, -12), true, true, true, Map.of())));
        scene.updateFacilities(10, Instant.ofEpochMilli(1000),
                List.of(new NearbyBlocksSight.BlockSighting(WorldPosition.here(4, 64, 0), "minecraft:chest")));
        // 控制权交接给空实现：observe 测试不接输入层。
        // 记地点用真实的能力：脚下那一格固定在原点，观察编号从场景查。
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(new RememberAbility(memory, () -> new WorldPosition(0, 64, 0, "minecraft:overworld"),
                new SceneSeenTargets(() -> scene)));
        GoalRunTable table = new GoalRunTable(registry, new InMemoryGoalRunStore(),
                memory, new ControlLoop(List.of()), new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() {
                        return true;
                    }

                    @Override public void requestControl() {
                    }
                });
        ClientThread direct = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                return work.apply(new Tick(10, heldPlayer));
            }
        };
        tools = new ToolDispatcher(List.of(new ObserveTool(() -> inWorld ? scene : null,
                () -> inWorld ? memory : null, table, direct), new ExecuteTool(registry, table, direct)));
    }

    private JsonObject observe(String arguments) {
        return tools.call("observe", JsonParser.parseString(arguments).getAsJsonObject());
    }

    private JsonObject execute(String goal) {
        return tools.call("execute", JsonParser.parseString("{\"goal\": " + goal + "}").getAsJsonObject());
    }

    private static JsonObject data(JsonObject reply) {
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("data");
    }

    private static String errorCode(JsonObject reply) {
        assertFalse(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("error").get("code").getAsString();
    }

    @Test
    void theSceneListsWhatIsAroundWithDirectionsAndIds() {
        JsonObject view = data(observe("{}"));

        JsonObject zombie = view.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals("e1", zombie.get("id").getAsString());
        assertEquals("前方", zombie.get("direction").getAsString());
        assertTrue(zombie.get("targeting_me").getAsBoolean());
        assertEquals("minecraft:chest", view.getAsJsonArray("facilities").get(0).getAsJsonObject()
                .get("block").getAsString());
        assertTrue(view.get("summary").getAsString().contains("e1"));
    }

    @Test
    void selfShowsStateIdleTaskAndDefaultPermissions() {
        JsonObject self = data(observe("{\"what\": \"self\"}"));

        assertEquals(18, self.get("food").getAsInt());
        assertEquals("minecraft:stone_pickaxe", self.get("held").getAsString());
        assertEquals("空闲，没有在做的目标", self.getAsJsonObject("goal").get("doing").getAsString());
        assertEquals("natural", self.getAsJsonObject("permissions").get("change_blocks").getAsString());
    }

    @Test
    void selfMergesSameItemsInInventoryAndSaysTheKeyIsItem() {
        // 消费方按 inventory 条目的 item 键读物品名（不是旧叫法 item_id）；两格同物品合并成一条给总数。
        // 背包摆法：圆石 5 件与 3 件分放两格，另有一格铁剑，36 格主格占了 3 格。
        heldPlayer = playerWithBackpack(new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of(
                        new BackpackStack("minecraft:cobblestone", 5, 64, false, false, false, true),
                        new BackpackStack("minecraft:cobblestone", 3, 64, false, false, false, true),
                        new BackpackStack("minecraft:iron_sword", 1, 1, true, false, false, false));
            }

            @Override public int usedSlots() { return 3; }
            @Override public int totalSlots() { return 36; }
        });

        JsonObject self = data(observe("{\"what\": \"self\"}"));

        JsonArray inventory = self.getAsJsonArray("inventory");
        assertEquals(2, inventory.size(), inventory::toString);
        JsonObject cobble = inventory.get(0).getAsJsonObject();
        assertEquals("minecraft:cobblestone", cobble.get("item").getAsString());
        assertFalse(cobble.has("item_id"), "物品名的键是 item，旧叫法 item_id 不再出现");
        assertEquals(8, cobble.get("count").getAsInt(), "两格圆石合并成 8 件");
        assertEquals("minecraft:iron_sword", inventory.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals(33, self.get("free_slots").getAsInt());
    }

    @Test
    void detailFindsSeenThingsAndRememberedPlaces() {
        memory.remember("家", WorldPosition.here(0, 64, 30));

        JsonObject zombie = data(observe("{\"what\": \"detail\", \"id\": \"E1\"}"));
        JsonObject home = data(observe("{\"what\": \"detail\", \"id\": \"家\"}"));

        assertEquals("minecraft:zombie", zombie.getAsJsonObject("now").get("type").getAsString());
        assertEquals("后方", home.get("direction").getAsString());
        assertEquals(30, home.get("distance").getAsInt());
        assertEquals("unknown_id", errorCode(observe("{\"what\": \"detail\", \"id\": \"e99\"}")));
    }

    @Test
    void worldMemoryListsPlacesAndRecordsNearestFirst() {
        memory.remember("家", WorldPosition.here(0, 64, 30));
        memory.remember("矿洞口", WorldPosition.here(5, 60, 0));

        JsonObject view = data(observe("{\"what\": \"world_memory\"}"));

        assertEquals("矿洞口", view.getAsJsonArray("places").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("container", view.getAsJsonArray("records").get(0).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void rememberedPlacesShowUpInObserveAndGoAwayWhenForgotten() {
        // 记地点当场完成：execute 直接带回结果，不成为主任务。
        JsonObject remembered = data(execute("{\"ability\": \"remember\", \"parameters\": {\"name\": \"家\"}}"));
        JsonObject chest = data(execute("{\"ability\": \"remember\", \"target\": {\"kind\": \"seen\", \"id\": \"b1\"},"
                + " \"parameters\": {\"name\": \"箱子\"}}"));

        assertEquals("done", remembered.getAsJsonObject("result").get("status").getAsString(), remembered::toString);
        assertEquals("箱子", data(observe("{\"what\": \"world_memory\"}")).getAsJsonArray("places").get(1)
                .getAsJsonObject().get("name").getAsString(), chest::toString);
        assertEquals("空闲，没有在做的目标", data(observe("{\"what\": \"self\"}")).getAsJsonObject("goal")
                .get("doing").getAsString());

        data(execute("{\"ability\": \"remember\", \"parameters\": {\"name\": \"家\", \"operation\": \"forget\"}}"));
        assertEquals("unknown_id", errorCode(observe("{\"what\": \"detail\", \"id\": \"家\"}")));
    }

    @Test
    void theSceneReportsHeardSoundsFeaturesAndTheGridWhenAsked() {
        // 身后有脚步声、左前有一片树林、右侧有水：声音只有方位与远近，不给坐标。
        scene.updateSounds(10, List.of(new SubtitleEar.Event("苦力怕嘶嘶声", WorldPosition.here(0, 64, 6))));
        scene.updateGrid(new OverheadGrid.View(new BlockPos(0, 64, 0), 2, new char[][]{
                        {'T', 'T', '.', '.'},
                        {'T', 'T', '.', '~'},
                        {'.', '.', '@', '~'},
                        {'.', '.', '~', '~'}}),
                10, Instant.ofEpochMilli(1000));

        JsonObject view = data(observe("{\"grid\": true}"));

        JsonObject sound = view.getAsJsonArray("heard").get(0).getAsJsonObject();
        assertEquals("苦力怕嘶嘶声", sound.get("sound").getAsString());
        assertEquals("后方", sound.get("direction").getAsString());
        assertFalse(sound.has("position"), "听见的声音不给精确位置");
        // 成片的地形特征各领一个 f# 编号，可以直接指着它下指令。
        JsonArray features = view.getAsJsonArray("features");
        assertTrue(features.size() >= 2, features::toString);
        JsonObject forest = features.get(0).getAsJsonObject();
        assertEquals("树林", forest.get("kind").getAsString());
        assertTrue(forest.get("id").getAsString().matches("f[0-9]+"), forest::toString);
        JsonObject water = features.get(1).getAsJsonObject();
        assertEquals("水体", water.get("kind").getAsString());
        // 俯视网格：第一行在北，一格一个字符，附图例。
        JsonObject grid = view.getAsJsonObject("grid");
        assertEquals(2, grid.get("radius").getAsInt());
        assertEquals("TT..", grid.getAsJsonArray("rows").get(0).getAsString());
        assertTrue(grid.get("legend").getAsString().contains("北朝上"));
        // 不点 grid 就不带网格：地形特征照常给。
        assertFalse(data(observe("{}")).has("grid"));
        assertTrue(data(observe("{}")).getAsJsonArray("features").size() >= 2);
    }

    @Test
    void mistakesAndBeingOutOfTheWorldAreSaidPlainly() {
        assertEquals("invalid_parameter", errorCode(observe("{\"what\": \"detail\"}")));
        assertEquals("invalid_parameter", errorCode(observe("{\"what\": \"self\", \"grid\": true}")));
        inWorld = false;
        assertEquals("not_in_world", errorCode(observe("{}")));
    }
}
