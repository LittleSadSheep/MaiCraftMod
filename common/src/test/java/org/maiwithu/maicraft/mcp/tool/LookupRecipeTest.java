// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeNotReady;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

/**
 * lookup 查配方与资料：配方页按类别分组、写工作站与原始定义；用途页分"当原料"和"当工作站"两部分，每条一行；
 * 按名字找物品 ID；参数组合写错一次报全；角色不在世界里时配方回答 not_in_world，资料退到随包常识并说明。
 */
class LookupRecipeTest {

    private static final String ALLOY = "create:andesite_alloy";
    private static final String MIXER = "create:mechanical_mixer";

    @Test
    void 配方页按类别分组_写工作站与原始定义() {
        JsonObject data = ok(lookup(true).call(args("recipe", ALLOY, null, null)));

        assertEquals("安山合金", data.get("name").getAsString());
        assertEquals("emi", data.get("read_from").getAsString());
        assertEquals(1, data.get("recipe_count").getAsInt());
        JsonObject mixing = data.getAsJsonArray("categories").get(0).getAsJsonObject();
        assertEquals("create:mixing", mixing.get("category").getAsString());
        assertEquals("搅拌", mixing.get("category_name").getAsString());
        assertEquals(MIXER, mixing.getAsJsonArray("workstations").get(0).getAsJsonObject().get("item").getAsString());
        JsonObject recipe = mixing.getAsJsonArray("recipes").get(0).getAsJsonObject();
        JsonArray inputs = recipe.getAsJsonArray("inputs");
        assertEquals("c:nuggets/iron", inputs.get(1).getAsJsonObject().get("tag").getAsString(), "标签原料写标签");
        assertEquals(0.5, recipe.getAsJsonArray("outputs").get(1).getAsJsonObject().get("chance").getAsDouble(), "标了几率才写几率");
        assertFalse(recipe.getAsJsonArray("outputs").get(0).getAsJsonObject().has("chance"));
        assertEquals("none", recipe.getAsJsonObject("definition").get("heat_requirement").getAsString());
    }

    @Test
    void 用途页分当原料与当工作站两部分_每条一行() {
        JsonObject data = ok(lookup(true).call(args("recipe", MIXER, null, true)));

        JsonObject station = data.getAsJsonObject("as_workstation");
        assertEquals(1, station.get("recipe_count").getAsInt());
        String line = station.getAsJsonArray("categories").get(0).getAsJsonObject()
                .getAsJsonArray("recipes").get(0).getAsString();
        assertEquals("create:mixing/andesite_alloy：安山合金 ×1、碎石 ×1（50%） ← 安山岩 ×1、铁粒（#c:nuggets/iron）×1", line);
        assertEquals(0, data.getAsJsonObject("as_ingredient").get("recipe_count").getAsInt());
    }

    @Test
    void 按名字找物品ID并建议读第一个的配方页() {
        JsonObject reply = lookup(true).call(args("recipe", null, "安山合金", null));

        JsonObject data = ok(reply);
        assertEquals(ALLOY, data.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(ALLOY, reply.getAsJsonObject("next").getAsJsonObject("arguments").get("id").getAsString());
    }

    @Test
    void 参数写错一次报全_没有的物品回答unknown_id() {
        JsonObject missing = lookup(true).call(args("recipe", null, null, null));
        assertEquals(ErrorCode.INVALID_PARAMETER.wireName(), missing.getAsJsonObject("error").get("code").getAsString());

        JsonObject wrongTopic = lookup(true).call(args("knowledge", null, null, true));
        assertEquals(ErrorCode.INVALID_PARAMETER.wireName(), wrongTopic.getAsJsonObject("error").get("code").getAsString());

        JsonObject unknown = lookup(true).call(args("recipe", "create:no_such_thing", null, null));
        assertEquals(ErrorCode.UNKNOWN_ID.wireName(), unknown.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void 不在世界里时配方回答not_in_world_资料退到随包常识() {
        JsonObject recipe = lookup(false).call(args("recipe", ALLOY, null, null));
        assertEquals(ErrorCode.NOT_IN_WORLD.wireName(), recipe.getAsJsonObject("error").get("code").getAsString());

        JsonObject catalog = lookup(false).call(args("knowledge", null, null, null));
        assertTrue(catalog.get("ok").getAsBoolean());
        assertTrue(catalog.getAsJsonArray("notes").get(0).getAsString().startsWith("角色不在世界里"));
    }

    @Test
    void 资料目录附各来源现状() {
        JsonObject data = ok(lookup(true).call(args("knowledge", null, null, null)));

        assertEquals(List.of("思索：可用，1 个场景"),
                data.getAsJsonArray("sources").asList().stream().map(element -> element.getAsString()).toList());
    }

    @Test
    void 资料一刻准备不完时下一刻接着读同一篇() {
        int[] reads = {0};
        KnowledgeSource slow = new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            @Override public KnowledgeDocument read(String uri) {
                if (!uri.equals("maicraft://knowledge/ponder/create/mechanical_mixer/1")) return null;
                if (++reads[0] < 3) throw new KnowledgeNotReady("还在回放，已到第 " + reads[0] * 100 + " 刻");
                return new KnowledgeDocument(uri, "scene", "场景", "思索场景", "正文");
            }
            @Override public String status() { return ""; }
        };
        ClientThread thread = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                return work.apply(null);
            }
        };
        LookupTool lookup = new LookupTool(new AbilityRegistry(new TaskFactories(), modId -> false), new KnowledgeLibrary(List.of(slow)),
                new RecipeLookup(List.of(), new MixingViewer()), new Items(), thread);

        JsonObject data = ok(lookup.call(args("knowledge", "maicraft://knowledge/ponder/create/mechanical_mixer/1", null, null)));

        assertEquals("正文", data.get("text").getAsString());
        assertEquals(3, reads[0], "读了三次：前两次还在准备");
    }

    private static JsonObject ok(JsonObject reply) {
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("data");
    }

    private static JsonObject args(String topic, String id, String query, Boolean uses) {
        JsonObject args = new JsonObject();
        args.addProperty("topic", topic);
        if (id != null) args.addProperty("id", id);
        if (query != null) args.addProperty("query", query);
        if (uses != null) args.addProperty("uses", uses);
        return args;
    }

    private static LookupTool lookup(boolean inWorld) {
        ClientThread thread = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                if (!inWorld) throw new NotInWorld();
                return work.apply(null);
            }
        };
        KnowledgeSource ponder = new KnowledgeSource() {
            @Override public List<KnowledgeDocument.Entry> entries() { return List.of(); }
            @Override public KnowledgeDocument read(String uri) { return null; }
            @Override public String status() { return "思索：可用，1 个场景"; }
        };
        return new LookupTool(new AbilityRegistry(new TaskFactories(), modId -> false), new KnowledgeLibrary(List.of(ponder)),
                new RecipeLookup(List.of(new MixingViewer()), new MixingViewer()), new Items(), thread);
    }

    /** 替身物品表：安山合金与动力搅拌器。 */
    private static final class Items implements ReadsItemDescriptions {
        @Override public Optional<ItemDescription> describe(String itemId) {
            if (!itemId.equals(ALLOY) && !itemId.equals(MIXER)) return Optional.empty();
            return Optional.of(new ItemDescription(itemId, nameOf(itemId), null, List.of(), List.of(), "", List.of(), List.of()));
        }

        @Override public List<String> search(List<String> terms) {
            return terms.stream().allMatch((ALLOY + " 安山合金")::contains) ? List.of(ALLOY) : List.of();
        }

        @Override public String nameOf(String itemId) {
            return itemId.equals(ALLOY) ? "安山合金" : "动力搅拌器";
        }
    }

    /** 替身配方查看器：只有一条搅拌配方，动力搅拌器是它的工作站。 */
    private static final class MixingViewer implements RecipeViewer {
        @Override public String name() { return "emi"; }
        @Override public boolean importsOtherViewers() { return true; }
        @Override public Readiness readiness() { return Readiness.yes(); }
        @Override public List<ShownRecipe> making(String itemId) { return itemId.equals(ALLOY) ? List.of(mixing()) : List.of(); }
        @Override public List<ShownRecipe> using(String itemId) { return List.of(); }
        @Override public List<ShownRecipe> atWorkstation(String itemId) { return itemId.equals(MIXER) ? List.of(mixing()) : List.of(); }

        private static ShownRecipe mixing() {
            JsonObject definition = new JsonObject();
            definition.addProperty("type", "create:mixing");
            definition.addProperty("heat_requirement", "none");
            ShownIngredient nugget = new ShownIngredient("c:nuggets/iron", List.of(ShownStack.item("minecraft:iron_nugget", "铁粒", 1)), 1);
            return new ShownRecipe("create:mixing/andesite_alloy", "create:mixing", "搅拌",
                    List.of(ShownStack.item(MIXER, "动力搅拌器", 1), ShownStack.item("create:basin", "工作盆", 1)),
                    List.of(ShownIngredient.of(ShownStack.item("minecraft:andesite", "安山岩", 1)), nugget), List.of(),
                    List.of(ShownStack.item(ALLOY, "安山合金", 1),
                            new ShownStack(ShownStack.Kind.ITEM, "minecraft:gravel", "碎石", 1, 0.5)), definition);
        }
    }
}
