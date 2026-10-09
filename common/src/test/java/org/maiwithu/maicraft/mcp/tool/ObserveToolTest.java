// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

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

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    @TempDir
    Path temp;

    private Scene scene;
    private WorldMemory memory;
    private boolean inWorld = true;
    private ToolDispatcher tools;

    @BeforeEach
    void world() {
        memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "a".repeat(64));
        scene = new Scene(memory);
        // 角色站在原点，脸朝北；正前方 12 格一只僵尸盯着它，右边 4 格一只箱子。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 18, 300, 300, "minecraft:stone_pickaxe", List.of(), List.of(), true, false));
        scene.updateEntities(10, List.of(new EntitySight.Observation(1, "minecraft:zombie", null,
                WorldPosition.here(0, 64, -12), true, true, true, Map.of())));
        scene.updateFacilities(10, Instant.ofEpochMilli(1000),
                List.of(new NearbyBlocksSight.BlockSighting(WorldPosition.here(4, 64, 0), "minecraft:chest")));
        GoalRunTable table = new GoalRunTable(new AbilityRegistry(new TaskFactories()), new InMemoryGoalRunStore(),
                memory, new ControlLoop(List.of()));
        ClientThread direct = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                return work.apply(new Tick(10));
            }
        };
        tools = new ToolDispatcher(List.of(new ObserveTool(() -> inWorld ? scene : null,
                () -> inWorld ? memory : null, table, direct)));
    }

    private JsonObject observe(String arguments) {
        return tools.call("observe", JsonParser.parseString(arguments).getAsJsonObject());
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
        assertEquals("空闲，没有主任务", self.getAsJsonObject("task").get("doing").getAsString());
        assertEquals("natural", self.getAsJsonObject("permissions").get("change_blocks").getAsString());
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
    void mistakesAndBeingOutOfTheWorldAreSaidPlainly() {
        assertEquals("invalid_parameter", errorCode(observe("{\"what\": \"detail\"}")));
        assertEquals("invalid_parameter", errorCode(observe("{\"what\": \"self\", \"grid\": true}")));
        inWorld = false;
        assertEquals("not_in_world", errorCode(observe("{}")));
    }
}
