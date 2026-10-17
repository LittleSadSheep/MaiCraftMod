// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

/**
 * 查能力说明时碰到缺模组的能力：回 unknown_ability，但说清缺的是哪个模组，不说"没有这个能力"；
 * 列能力时顶上一行写这局装上了哪些联动模组。
 */
class LookupMissingModsTest {

    @Test
    void 缺模组的能力按名字查时说清缺哪个() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> false);
        registry.register(ToolTestAbility.needsMods("maicraft:machine_probe", "create", "ae2"));
        LookupTool lookup = new LookupTool(registry, KnowledgeLibrary.offline());
        JsonObject query = new JsonObject();
        query.addProperty("id", "machine_probe");

        JsonObject reply = lookup.call(query);
        JsonObject error = reply.getAsJsonObject("error");
        assertEquals(ErrorCode.UNKNOWN_ABILITY.wireName(), error.get("code").getAsString());
        assertTrue(error.get("message").getAsString().contains("需要模组 ae2、create"), error::toString);
    }

    @Test
    void 没有这个能力时照旧给相近的名字() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> false);
        registry.register(ToolTestAbility.sleep());
        LookupTool lookup = new LookupTool(registry, KnowledgeLibrary.offline());
        JsonObject query = new JsonObject();
        query.addProperty("id", "slep");

        JsonObject error = lookup.call(query).getAsJsonObject("error");
        assertEquals(ErrorCode.UNKNOWN_ABILITY.wireName(), error.get("code").getAsString());
        assertTrue(error.get("message").getAsString().contains("maicraft:sleep"), error::toString);
    }

    @Test
    void 列能力时顶上写这局装上了哪些联动模组() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> false);
        registry.register(ToolTestAbility.sleep());
        LookupTool lookup = new LookupTool(registry, KnowledgeLibrary.offline(),
                new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)), NO_ITEMS, NO_IDS, NOT_IN_WORLD,
                () -> List.of("ae2", "create"));

        JsonObject data = lookup.call(new JsonObject()).getAsJsonObject("data");
        assertEquals("[\"ae2\",\"create\"]", data.get("installed_mods").toString());
        assertTrue(data.getAsJsonArray("abilities").size() > 0);
    }

    @Test
    void 没有联动模组时是空的一行() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> false);
        registry.register(ToolTestAbility.sleep());

        JsonObject data = new LookupTool(registry, KnowledgeLibrary.offline()).call(new JsonObject()).getAsJsonObject("data");
        assertEquals(0, data.getAsJsonArray("installed_mods").size());
    }

    /** 没有物品可读。 */
    private static final ReadsItemDescriptions NO_ITEMS = new ReadsItemDescriptions() {
        @Override public Optional<ItemDescription> describe(String itemId) { return Optional.empty(); }
        @Override public List<String> search(List<String> terms) { return List.of(); }
        @Override public String nameOf(String itemId) { return itemId; }
    };

    /** 没有注册表可读。 */
    private static final ReadsRegisteredIds NO_IDS = new ReadsRegisteredIds() {
        @Override public List<String> ids(IdKind kind) { return List.of(); }
        @Override public List<String> tags(IdKind kind) { return List.of(); }
    };

    /** 角色不在世界里。 */
    private static final ClientThread NOT_IN_WORLD = new ClientThread() {
        @Override public <T> T call(Function<TickContext, T> work) {
            throw new NotInWorld();
        }
    };
}
