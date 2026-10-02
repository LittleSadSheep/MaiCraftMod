package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import net.minecraft.data.registries.VanillaRegistries;

/** 探索目录只按需分页，下一页保留查询条件，模组名称不会因不在固定白名单而丢失。 */
public final class ExplorationCatalogTest {
    public static void main(String[] args) {
        var rows = new ArrayList<JsonObject>();
        for (int i = 0; i < 53; i++) {
            var item = new JsonObject(); item.addProperty("id", "newmod:biome_" + i); rows.add(item);
        }
        int count = 0, offset = 0;
        while (true) {
            var page = ExplorationCatalog.page("biomes", rows, "newmod", offset, 7);
            count += page.getAsJsonArray("entries").size();
            check(page.get("total").getAsInt() == 53, "retain total independently of page size");
            if (!page.has("next_offset")) break;
            offset = page.get("next_offset").getAsInt();
            check(page.getAsJsonObject("next_query").get("query").getAsString().equals("newmod"), "retain filter on continuation");
        }
        check(count == 53, "pagination neither duplicates nor drops discoveries");
        var normalized = PublicToolCatalog.validateAndNormalize("perceive", JsonParser.parseString(
                "{\"view\":\"exploration\",\"focus\":\"biomes\",\"query\":\"forest\",\"offset\":7}"));
        check(normalized.get("offset").getAsInt() == 7, "public contract accepts filtered catalog pagination");
        var nativeBiomes = ExplorationCatalog.read(VanillaRegistries.createLookup(), "biomes", "minecraft:beach", 0, 5);
        check(nativeBiomes.get("total").getAsInt() > 0, "native biome registry supplies beach metadata");
        System.out.println("ExplorationCatalogTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
