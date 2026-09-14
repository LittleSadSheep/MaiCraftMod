// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 用一份可复用窗饰和整组快速图元跑实际编译/导出；可选输出仅写验收文件，不启动游戏或施工。 */
public final class BuildingModelShowcaseTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        JsonObject scene;
        try (var input = BuildingModelShowcaseTest.class.getResourceAsStream("/building-model-v2-showcase.json")) {
            if (input == null) throw new AssertionError("缺少快速建模示例");
            scene = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var blueprint = BuildingSceneCompiler.compile(scene); var metadata = blueprint.getAsJsonObject("metadata");
        int targets = blueprint.getAsJsonArray("blocks").size();
        if (targets <= 500 || targets >= 3000 || metadata.get("component_count").getAsInt() != 1)
            throw new AssertionError("示例未完整展开或意外膨胀");
        if (BuildingSceneExport.structure(blueprint).getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size() != targets)
            throw new AssertionError("示例导出的目标数量不一致");
        String output = System.getProperty("maicraft.model.example.output");
        if (output != null) {
            // 只输出同一份作者场景、体素蓝图和NBT，供查看与复用；不以导出成功冒充生存建造完成。
            Path directory = Path.of(output).toAbsolutePath().normalize(); Files.createDirectories(directory);
            Files.writeString(directory.resolve("building-model-v2.scene.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(scene));
            Files.writeString(directory.resolve("building-model-v2.blueprint.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(blueprint));
            BuildingSceneExport.write(directory,"f24c6540-81d2-4d13-8760-4f6aed30bea0",blueprint,"nbt");
        }
        System.out.println("BuildingModelShowcaseTest: compiled and exported " + targets + " targets from one reusable bay and mixed quick primitives");
    }
}
