// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.ability.design.DesignSamples.drawing;
import static org.maiwithu.maicraft.ability.design.DesignSamples.json;
import static org.maiwithu.maicraft.ability.design.DesignSamples.mesh;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.BuildingDesign;
import org.maiwithu.maicraft.ability.design.api.DesignStore;

/** 设计存储：每次修改新建一版并记父版本，旧版本不动；坏图纸与坏修改不进库；换个进程再开库还能读。 */
class DesignStoreTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 新建_修改成新版_父版本保留() throws Exception {
        Path file = Files.createTempDirectory("design-store-").resolve("designs.sqlite");
        var store = new DesignStore(file);
        JsonObject original = drawing(mesh("Wall", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body"));
        original.addProperty("name", "小屋");
        BuildingDesign first = store.create(original);
        assertNull(first.parentId());
        assertEquals("小屋", first.name());
        assertEquals(original, store.load(first.id()).drawing());
        BuildingDesign second = store.update(first.id(), json("{\"objects\":[{\"name\":\"Wall\",\"dimensions\":[1,2,1],\"location\":[0.5,1,0.5]}],\"name\":\"高一点的小屋\"}"));
        assertEquals(first.id(), second.parentId());
        assertTrue(!second.id().equals(first.id()), "修改生成新编号");
        assertEquals(2, second.drawing().getAsJsonArray("objects").get(0).getAsJsonObject().getAsJsonArray("dimensions").get(1).getAsInt());
        assertEquals("Body", second.drawing().getAsJsonArray("objects").get(0).getAsJsonObject().get("material").getAsString(), "只改尺寸时材料保留");
        assertEquals(original, store.load(first.id()).drawing(), "父版本不就地改");
        // 返回的副本改了不影响库里那份。
        second.drawing().remove("objects");
        assertTrue(new DesignStore(file).load(second.id()).drawing().has("objects"), "换个库对象再读仍是完整的");
    }

    @Test
    void 坏图纸与坏修改不进库() throws Exception {
        Path file = Files.createTempDirectory("design-store-").resolve("designs.sqlite");
        var store = new DesignStore(file);
        JsonObject broken = drawing(mesh("Wall", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Missing"));
        assertThrows(IllegalArgumentException.class, () -> store.create(broken));
        BuildingDesign first = store.create(drawing(mesh("Wall", "cube", new double[]{.5, .5, .5}, new int[]{1, 1, 1}, "Body")));
        assertThrows(IllegalArgumentException.class, () -> store.update(first.id(), json("{\"remove_objects\":[\"Wall\"]}")), "删光对象的图纸不合法");
        assertThrows(IllegalArgumentException.class, () -> store.update(first.id(), json("{\"objects\":[{\"name\":\"Wall\",\"material\":\"Missing\"}]}")));
        assertEquals(first.drawing(), store.load(first.id()).drawing(), "失败的修改不留痕迹");
        assertThrows(IllegalArgumentException.class, () -> store.load("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> store.load("00000000-0000-0000-0000-000000000000"));
    }
}
