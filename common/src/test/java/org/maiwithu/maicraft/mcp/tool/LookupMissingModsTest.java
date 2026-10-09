// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

/** 查能力说明时碰到缺模组的能力：回 unknown_ability，但说清缺的是哪个模组，不说"没有这个能力"。 */
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
}
