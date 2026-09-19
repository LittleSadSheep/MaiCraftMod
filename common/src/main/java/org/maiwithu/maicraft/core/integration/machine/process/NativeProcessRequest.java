// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;

/** 使用机器只声明机制、锚点内位置和语义参数；不接受按钮号、点击序列或调用者伪造的执行回执。 */
public final class NativeProcessRequest {
    private final String process;
    private final BlockPos offset;
    private final JsonObject parameters;

    private NativeProcessRequest(String process, BlockPos offset, JsonObject parameters) {
        this.process = process; this.offset = offset.immutable(); this.parameters = parameters.deepCopy();
    }

    public static NativeProcessRequest parse(JsonObject source) {
        if (source == null || !Set.of("schema_version", "process", "offset", "parameters").containsAll(source.keySet()))
            throw new IllegalArgumentException("native process accepts schema_version, process, offset and parameters only");
        if (integer(source.get("schema_version"), "schema_version") != 2) throw new IllegalArgumentException("native process requires schema_version 2");
        var value = source.get("process");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > 256 || !value.getAsString().contains(":") || ResourceLocation.tryParse(value.getAsString()) == null)
            throw new IllegalArgumentException("process requires an installed namespaced mechanism id");
        if (!source.has("parameters") || !source.get("parameters").isJsonObject())
            throw new IllegalArgumentException("native process parameters must be an object");
        BlockPos offset = BlockPos.ZERO;
        if (source.has("offset")) {
            var at = source.get("offset");
            if (!at.isJsonArray() || at.getAsJsonArray().size() != 3) throw new IllegalArgumentException("process offset requires [x,y,z]");
            int[] coordinates = new int[3]; int radius = MachinePlanningBudget.current().maxRadius();
            for (int axis = 0; axis < 3; axis++) {
                coordinates[axis] = integer(at.getAsJsonArray().get(axis), "offset");
                if (Math.abs((long) coordinates[axis]) > radius) throw new IllegalArgumentException("process offset exceeds the machine planning radius");
            }
            offset = new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
        }
        return new NativeProcessRequest(value.getAsString(), offset, source.getAsJsonObject("parameters"));
    }

    public String process() { return process; }
    public JsonObject parameters() { return parameters.deepCopy(); }
    public BlockPos offset() { return offset; }
    public BlockPos position(BlockPos anchor) {
        // 建造和加工使用同一固定锚点；坐标相加溢出时拒绝，不能把投料地点绕到另一处世界位置。
        return new BlockPos(Math.addExact(anchor.getX(), offset.getX()), Math.addExact(anchor.getY(), offset.getY()),
                Math.addExact(anchor.getZ(), offset.getZ()));
    }
    private static int integer(com.google.gson.JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(field + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(field + " must be a bounded integer", invalid); }
    }
}
