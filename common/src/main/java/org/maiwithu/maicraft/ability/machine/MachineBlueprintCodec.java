// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 机器蓝图附加条目的读写：档案存盘时把部件、安装段、装后设置与声明工序写进 JSON，读回时原样重建。
 * 只翻译形状，不解释含义；字段与能力入口解析的正文同一套名字。
 */
final class MachineBlueprintCodec {

    private MachineBlueprintCodec() {
    }

    static JsonArray parts(MachineBlueprint blueprint) {
        JsonArray out = new JsonArray();
        for (MachineBlueprint.Part part : blueprint.parts()) {
            JsonObject json = new JsonObject();
            json.addProperty("offset", part.offset().asLong());
            json.addProperty("side", part.side().getName());
            json.addProperty("item", part.itemId());
            out.add(json);
        }
        return out;
    }

    static JsonArray installations(MachineBlueprint blueprint) {
        JsonArray out = new JsonArray();
        for (MachineBlueprint.Segment segment : blueprint.installations()) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", segment.kind());
            json.add("offsets", positions(segment.offsets()));
            out.add(json);
        }
        return out;
    }

    static JsonArray settings(MachineBlueprint blueprint) {
        JsonArray out = new JsonArray();
        for (MachineBlueprint.Setting setting : blueprint.settings()) {
            JsonObject json = new JsonObject();
            json.addProperty("offset", setting.offset().asLong());
            json.addProperty("key", setting.key());
            json.addProperty("value", setting.value());
            out.add(json);
        }
        return out;
    }

    static JsonArray processes(MachineBlueprint blueprint) {
        JsonArray out = new JsonArray();
        for (MachineBlueprint.Process process : blueprint.processes()) {
            JsonObject json = new JsonObject();
            json.addProperty("offset", process.offset().asLong());
            json.addProperty("item", process.item());
            out.add(json);
        }
        return out;
    }

    static List<MachineBlueprint.Part> readParts(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<MachineBlueprint.Part> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            out.add(new MachineBlueprint.Part(BlockPos.of(json.get("offset").getAsLong()),
                    Direction.byName(json.get("side").getAsString()), json.get("item").getAsString()));
        }
        return out;
    }

    static List<MachineBlueprint.Segment> readInstallations(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<MachineBlueprint.Segment> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            out.add(new MachineBlueprint.Segment(json.get("kind").getAsString(), readPositions(json.getAsJsonArray("offsets"))));
        }
        return out;
    }

    static List<MachineBlueprint.Setting> readSettings(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<MachineBlueprint.Setting> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            out.add(new MachineBlueprint.Setting(BlockPos.of(json.get("offset").getAsLong()),
                    json.get("key").getAsString(), json.get("value").getAsString()));
        }
        return out;
    }

    static List<MachineBlueprint.Process> readProcesses(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<MachineBlueprint.Process> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            out.add(new MachineBlueprint.Process(BlockPos.of(json.get("offset").getAsLong()),
                    json.get("item").getAsString()));
        }
        return out;
    }

    private static JsonArray positions(List<BlockPos> cells) {
        JsonArray out = new JsonArray();
        for (BlockPos pos : cells) {
            out.add(pos.asLong());
        }
        return out;
    }

    private static List<BlockPos> readPositions(JsonArray array) {
        List<BlockPos> out = new ArrayList<>();
        for (JsonElement element : array) {
            out.add(BlockPos.of(element.getAsLong()));
        }
        return out;
    }
}
