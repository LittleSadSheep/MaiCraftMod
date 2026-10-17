// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.design.api.CompiledDesign;
import org.maiwithu.maicraft.ability.design.api.DesignCompiler;

/**
 * 建筑资料里的每个 JSON 例子都要真的过校验、编得出格：模型照着资料写图纸，资料和代码漂移了它就只能瞎猜。
 * 例子按"能直接交给 design 的完整图纸"写；这里逐个喂给校验与编译，并把索引里登记的页面和资源文件对上。
 */
class BuildingKnowledgeTest {

    /** 资料页的 id 后段；索引和知识库都按这个登记。 */
    static final List<String> PAGES = List.of("design", "roofs", "house", "styles", "example-cottage");
    private static final Pattern EXAMPLE = Pattern.compile("```json\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static String page(String name) throws IOException {
        return resource("/assets/maicraft/knowledge/building/" + name + ".md");
    }

    private static String resource(String path) throws IOException {
        try (var stream = BuildingKnowledgeTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "缺资料 " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<JsonObject> examples(String text) {
        List<JsonObject> out = new ArrayList<>();
        Matcher matcher = EXAMPLE.matcher(text);
        while (matcher.find()) out.add(JsonParser.parseString(matcher.group(1)).getAsJsonObject());
        return out;
    }

    @Test
    void 资料里的每个例子都过校验并编得出格() throws IOException {
        int total = 0;
        for (String name : PAGES) {
            List<JsonObject> examples = examples(page(name));
            for (int index = 0; index < examples.size(); index++) {
                JsonObject drawing = examples.get(index);
                String where = "building/" + name + " 第 " + (index + 1) + " 个例子";
                try {
                    DesignCompiler.validate(drawing);
                    CompiledDesign compiled = DesignCompiler.compile(drawing);
                    assertTrue(compiled.cellCount() > 0, where + " 一格都没有");
                    // 例子都按不叠加写：读者照抄时不会在结果里看到一堆冲突。
                    assertEquals(0, compiled.overlapCells(), where + " 有叠加冲突：" + compiled.overlapExamples());
                } catch (IllegalArgumentException invalid) {
                    throw new AssertionError(where + " 过不了校验：" + invalid.getMessage(), invalid);
                }
                total++;
            }
        }
        assertTrue(total >= 15, "资料里的例子太少了：" + total);
    }

    @Test
    void 每页都写明坐标系_索引登记了每一页() throws IOException {
        String index = resource("/assets/maicraft/knowledge/index.md");
        for (String name : PAGES) {
            String text = page(name);
            assertFalse(text.contains("schema_version"), "building/" + name + " 还带着旧版字段 schema_version");
            for (JsonObject drawing : examples(text)) {
                assertEquals("minecraft_y_up", drawing.get("coordinate_system").getAsString(), "building/" + name + " 的例子要写 minecraft_y_up：不写按 z 向上算");
            }
            assertTrue(index.contains("maicraft://knowledge/building/" + name + ")"), "索引里没登记 building/" + name);
        }
    }
}
