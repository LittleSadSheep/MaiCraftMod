package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Set;

/**
 * 逐段投影的回归：调用方点名的段必须完整拿到，且投影结果要小到任何下游预算都装得下。
 *
 * <p>本测试存在的直接动机：完整 surroundings 响应超过一万字符，下游按上下文预算截断时
 * 排在后面的段（例如电梯楼层）会被整段切掉，读不到的一方会把"没读到"当成"没有"。
 */
public final class PerceiveSectionsTest {

    // 下游观察预算的量级（字符）；投影结果必须远小于它，否则这次修复没有意义。
    private static final int DOWNSTREAM_BUDGET = 2000;

    public static void main(String[] args) {
        projectionKeepsTheRequestedSection();
        projectionNamesWhatItCouldNotProduce();
        catalogAcceptsAndRejectsSectionRequests();
        advertisedSectionsAgreeWithValidation();
        crossViewErrorsExplainHowToCorrectTheRequest();
        constructionSiteHasItsOwnBoundedArguments();
        System.out.println("PerceiveSectionsTest: passed");
    }

    private static void projectionKeepsTheRequestedSection() {
        JsonObject payload = surroundingsShapedPayload();
        check(payload.toString().length() > DOWNSTREAM_BUDGET,
                "the unprojected fixture must exceed a downstream budget, or this regression proves nothing");

        JsonObject projected = PerceiveSections.select(payload, List.of("elevators"));
        check(projected.has("elevators"), "the requested section must survive the projection");
        check(projected.getAsJsonObject("elevators").getAsJsonArray("elevators").get(0).getAsJsonObject()
                        .get("floor_list_state").getAsString().equals("synchronized"),
                "the projected section must keep its own evidence fields, not a summary of them");
        check(!projected.has("terrain_overview") && !projected.has("local_decision_summary"),
                "sections nobody asked for must not be carried along");
        check(projected.toString().length() < DOWNSTREAM_BUDGET / 4,
                "the elevator answer must be far smaller than the budget that used to cut it off");

        check(PerceiveSections.select(payload, null) == payload,
                "omitting the request must return the full response unchanged");
        // 窄查询不点地形段时，调用方据此跳过只为地形准备的分帧采样。
        check(PerceiveSections.wants(null, "terrain_overview"),
                "no declared sections means every section is wanted, including the sampled terrain");
        check(!PerceiveSections.wants(List.of("elevators"), "terrain_overview"),
                "an elevator-only request must not wait for terrain sampling");
        check(PerceiveSections.wants(List.of("elevators", "terrain_overview"), "terrain_overview"),
                "asking for the sampled terrain explicitly must still wait for it");
    }

    /** 场地感知公开可直接复用的参数，不把半径或标签静默用于普通身体感知。 */
    private static void constructionSiteHasItsOwnBoundedArguments() {
        var plain = PublicToolCatalog.validateAndNormalize("perceive", request("construction_site", "{}"));
        check(plain.get("radius").getAsInt() == 8, "site survey defaults to a bounded eight-block radius");
        PublicToolCatalog.validateAndNormalize("perceive", request("construction_site", "{\"radius\":4,\"label\":\"platform\"}"));
        rejects("unbounded survey", "construction_site", "{\"radius\":999}");
        rejects("blank site label", "construction_site", "{\"label\":\" \"}");
        rejects("unrelated radius", "situation", "{\"radius\":4}");
        rejects("unrelated label", "surroundings", "{\"label\":\"platform\"}");
        rejects("ambiguous site selector", "construction_site", "{\"focus\":\"maicraft:build_machine\"}");
        var schema = PublicToolCatalog.definitions().get(0).getAsJsonObject().getAsJsonObject("inputSchema");
        check(schema.getAsJsonObject("properties").getAsJsonObject("view").getAsJsonArray("enum").toString().contains("construction_site"),
                "the callable site view is advertised to models");
    }

    private static void projectionNamesWhatItCouldNotProduce() {
        JsonObject payload = surroundingsShapedPayload();
        check(!payload.has("biome"), "the fixture must exercise a section this request produced nothing for");
        JsonObject projected = PerceiveSections.select(payload, List.of("elevators", "biome"));
        JsonArray unavailable = projected.getAsJsonArray(PerceiveSections.UNAVAILABLE);
        check(unavailable != null && unavailable.size() == 1 && unavailable.get(0).getAsString().equals("biome"),
                "a requested section without a value must be named, so absence cannot read as empty");
    }

    private static void catalogAcceptsAndRejectsSectionRequests() {
        // 受理面：合法段名（含跨 view 的同名段与 focus 才产出的诊断段）必须放行。
        // 校验只查段名合法性与形状，不去重——去重发生在运行时读取声明处，所以查这一层。
        JsonObject accepted = request("surroundings", "{\"sections\":[\"elevators\",\"elevators\"]}");
        JsonObject validated = PublicToolCatalog.validateAndNormalize("perceive", accepted);
        check(validated.getAsJsonArray("sections").size() == 2,
                "validation must pass the declared list through unchanged");
        check(PerceiveSections.requested(validated).size() == 1,
                "a repeated section name must not be projected twice");
        PublicToolCatalog.validateAndNormalize("perceive",
                request("situation", "{\"sections\":[\"inventory\"]}"));
        PublicToolCatalog.validateAndNormalize("perceive",
                request("situation", "{\"focus\":\"maicraft:elevators\",\"sections\":[\"elevators\"]}"));
        PublicToolCatalog.validateAndNormalize("perceive",
                request("situation", "{\"focus\":\"maicraft:transport\",\"sections\":[\"jetpack\"]}"));

        // 拒绝面：全部 fail-closed——拼错或跨视图的段名不能静默变成"空段"，那正是本次要修的误判。
        rejects("a section belonging to another view", "surroundings", "{\"sections\":[\"inventory\"]}");
        rejects("an invented section", "surroundings", "{\"sections\":[\"elevator_floors\"]}");
        rejects("the projection marker itself", "surroundings", "{\"sections\":[\"sections_unavailable\"]}");
        rejects("an empty request", "surroundings", "{\"sections\":[]}");
        rejects("a non-string entry", "surroundings", "{\"sections\":[7]}");
        rejects("a view without sections", "machines", "{\"sections\":[\"machines\"]}");
        rejects("attention", "attention", "{\"sections\":[\"tasks\"]}");
    }

    private static void rejects(String what, String view, String extra) {
        try {
            PublicToolCatalog.validateAndNormalize("perceive", request(view, extra));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(what + " must be rejected before it reaches the runtime");
    }

    private static void advertisedSectionsAgreeWithValidation() {
        // 模拟模型只看公开工具说明选字段；每个被推荐的段都必须能被对应视图受理。
        JsonObject perceive = PublicToolCatalog.definitions().get(0).getAsJsonObject();
        String description = perceive.getAsJsonObject("inputSchema").getAsJsonObject("properties")
                .getAsJsonObject("sections").get("description").getAsString();
        for (String view : List.of("situation", "surroundings")) {
            String declaration = description.split(view + ": ", 2)[1].split("\\.", 2)[0];
            Set<String> advertised = Set.of(declaration.split(", "));
            check(advertised.equals(PerceiveSections.known(view)), "description must list exactly the accepted sections");
            for (String section : advertised) {
                PublicToolCatalog.validateAndNormalize("perceive",
                        request(view, "{\"sections\":[\"" + section + "\"]}"));
            }
        }
    }

    private static void crossViewErrorsExplainHowToCorrectTheRequest() {
        // 复现现场的三个跨视图字段：明确指向 surroundings，修正后的窄查询应立即通过校验。
        for (String section : List.of("nearby_entities", "terrain_overview", "local_decision_summary")) {
            String extra = "{\"sections\":[\"position\",\"" + section + "\"]}";
            try {
                PublicToolCatalog.validateAndNormalize("perceive", request("situation", extra));
                throw new AssertionError("surroundings-only sections must not be accepted by situation");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("view=surroundings"), "error must identify the correct view");
                check(expected.getMessage().contains("valid sections:"), "error must offer local correction choices");
            }
            PublicToolCatalog.validateAndNormalize("perceive", request("surroundings", extra));
        }
    }

    private static JsonObject request(String view, String extra) {
        JsonObject value = JsonParser.parseString(extra).getAsJsonObject();
        value.addProperty("view", view);
        return value;
    }

    /** 形状与真实 surroundings 响应一致：前面是两段大体量诊断，电梯段排在它们之后。 */
    private static JsonObject surroundingsShapedPayload() {
        JsonObject payload = new JsonObject();
        JsonObject position = new JsonObject();
        position.addProperty("x", -136.2809165787706);
        position.addProperty("y", 135.0);
        position.addProperty("z", 174.3212246501019);
        payload.add("position", position);
        payload.addProperty("dimension", "minecraft:overworld");
        payload.addProperty("sky_light", 15);
        payload.add("nearby_entities", new JsonArray());
        payload.add("nearby_signs", new JsonArray());
        payload.add("local_decision_summary", floorCandidates(5, 320));
        payload.add("terrain_overview", terrainRegions(12, 340));
        payload.add("elevators", elevatorOverview());
        payload.add("physical_structures", terrainRegions(3, 420));
        return payload;
    }

    private static JsonObject floorCandidates(int regions, int filler) {
        JsonObject summary = new JsonObject();
        JsonArray standable = new JsonArray();
        for (int index = 0; index < regions; index++) {
            JsonObject region = new JsonObject();
            region.addProperty("region", "region_" + index);
            region.addProperty("evidence", "x".repeat(filler));
            standable.add(region);
        }
        summary.add("standable_regions", standable);
        return summary;
    }

    private static JsonArray terrainRegions(int regions, int filler) {
        JsonArray rows = new JsonArray();
        for (int index = 0; index < regions; index++) {
            JsonObject row = new JsonObject();
            row.addProperty("direction", "west");
            row.addProperty("surface_material", "minecraft:stone");
            row.addProperty("evidence", "y".repeat(filler));
            rows.add(row);
        }
        return rows;
    }

    private static JsonObject elevatorOverview() {
        JsonObject floor = new JsonObject();
        floor.addProperty("id", "floor:10");
        floor.addProperty("short_name", "1");
        floor.addProperty("long_name", "Lobby");
        floor.addProperty("served", true);
        floor.addProperty("at_player_height", false);
        JsonArray floors = new JsonArray();
        floors.add(floor);
        JsonObject cabin = new JsonObject();
        cabin.addProperty("elevator_id", "3f1d0a5e-1c2b-4d3e-8f90-abcdef012345");
        cabin.addProperty("distance", 4.5);
        cabin.addProperty("approach_distance", 2.0);
        cabin.addProperty("floor_list_state", "synchronized");
        cabin.addProperty("floor_count", 1);
        cabin.addProperty("ready_for_floor_decision", true);
        cabin.add("floors", floors);
        JsonArray cabins = new JsonArray();
        cabins.add(cabin);
        JsonObject overview = new JsonObject();
        overview.addProperty("integration_available", true);
        overview.add("elevators", cabins);
        overview.addProperty("omitted", 0);
        return overview;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
