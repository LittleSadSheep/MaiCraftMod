// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.core.build.BuildStates;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;

/**
 * 提供当前机器装配使用的状态解析与多格方块检查，并暂留旧格式的 validateWire。
 * 实际机器装配由 MachineConstructionPlan 和 MachineBuildTask 负责。
 */
public final class MachineBlueprint {
    public static final int MAX_BLOCKS = MachineBlueprintSpec.MAX_BLOCKS;

    private MachineBlueprint() {}

    /**
     * 旧蓝图格式检查：只收 blocks，并限制在旧观察半径内；它不是当前模型与机器布局的通用入口。
     */
    public static void validateWire(JsonObject blueprint) {
        MachineBlueprintSpec.parse(blueprint, MachineSurvey.MAX_RADIUS);
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException(
                "invalid value " + value + " for property " + property.getName()));
        return state.setValue(property, parsed);
    }

    /** Registry/state compilation is separately testable without constructing a player or a world. */
    // 当前机器规划仍共用此方法：名字和属性必须存在；若建造会重置明确给出的属性，立即报不支持，不静默换值。
    static BlockState resolveState(String blockId, Map<String, String> properties) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("unknown block registry ID: " + blockId);
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState desired = block.defaultBlockState();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException(blockId + " has no property " + entry.getKey());
            desired = withProperty(desired, property, entry.getValue());
        }
        BlockState normalized = BuildStates.normalize(desired);
        boolean changedExplicitRequest = normalized.getBlock() != block;
        if (!changedExplicitRequest) for (String name : properties.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(name);
            changedExplicitRequest |= !desired.getValue(property).equals(normalized.getValue(property));
        }
        if (changedExplicitRequest) {
            throw new IllegalArgumentException("unsupported copied runtime state for " + blockId
                    + "; describe a physically placeable initial state, not stored contents or generated runtime values");
        }
        return normalized;
    }

    static void validateGeneratedCells(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> targets) {
        BlockState desired = target.desiredState();
        BlockPos other = null;
        BlockState expected = null;
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower = desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            other = lower ? target.pos().above() : target.pos().below();
            expected = desired.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, lower ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        } else if (desired.hasProperty(BlockStateProperties.BED_PART) && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            boolean foot = desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
            var facing = desired.getValue(BlockStateProperties.HORIZONTAL_FACING);
            other = target.pos().relative(foot ? facing : facing.getOpposite());
            expected = desired.setValue(BlockStateProperties.BED_PART, foot ? BedPart.HEAD : BedPart.FOOT);
        }
        if (other != null) {
            BuildTaskRecord.Target declared = targets.get(other.asLong());
            if (declared == null || !declared.desiredState().equals(expected)) {
                throw new IllegalArgumentException("generated block half must be explicitly included with matching state: " + target.label());
            }
        }
    }

    /** Mekanism bounding machines alter extra cells that the generic block-item builder cannot receipt. */
    // 拒绝需要额外结构格却没有专门安装步骤的方块。Mekanism 通过可选 API 查询，无法确认时也拒绝。
    static void requireModeledEffects(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String namespace = id.getNamespace();
        if (namespace.equals("create") && (id.getPath().equals("large_water_wheel")
                || id.getPath().equals("water_wheel_structural") || id.getPath().equals("belt"))) {
            throw new IllegalArgumentException("unsupported_multicell_placement: " + id
                    + " has linked structural cells and requires a dedicated native assembly plan");
        }
        if (!namespace.equals("mekanism") && !namespace.equals("mekanismgenerators")
                && !namespace.equals("mekanismadditions")) return;
        try {
            ClassLoader loader = MachineBlueprint.class.getClassLoader();
            Class<?> attribute = Class.forName("mekanism.common.block.attribute.Attribute", false, loader);
            Class<?> bounding = Class.forName("mekanism.common.block.attribute.AttributeHasBounding", false, loader);
            Method has = attribute.getMethod("has", Block.class, Class.class);
            Object answer = has.invoke(null, block, bounding);
            if (!(answer instanceof Boolean present)) throw new IllegalArgumentException("unknown Mekanism bounding effect metadata");
            if (present) throw new IllegalArgumentException("unsupported_multicell_placement: " + BuiltInRegistries.BLOCK.getKey(block)
                    + " creates/removes bounding blocks and requires a dedicated native assembly plan");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new IllegalArgumentException("cannot verify Mekanism bounding-cell effects with the installed optional API", unavailable);
        }
    }

}
