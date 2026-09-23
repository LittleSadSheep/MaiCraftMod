// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import org.maiwithu.maicraft.core.integration.machine.MachineProcessingCapabilities;

/** 从原生行为接口描述加工与承载能力，不按精密构件、铁板等产品名称挑选工作站。 */
public final class CreateProcessingCapabilities {
    private static final String SMART = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity";
    private static final String TYPE = "com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType";
    private static final String PROCESSOR = "com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour";
    private static final String SURFACE = "com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour";
    private static final String DEPLOYER = "com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity";
    private static final String BELT = "com.simibubi.create.content.kinetics.belt.BeltBlockEntity";
    private CreateProcessingCapabilities() {}

    public static MachineProcessingCapabilities describe(BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock factory)) return MachineProcessingCapabilities.unavailable("block_has_no_processing_entity");
        try {
            // 只构造未挂入世界的原生实体并读取构造器登记的行为；不 initialize、不 tick、不装配假玩家。
            return describe(factory.newBlockEntity(BlockPos.ZERO, state));
        } catch (RuntimeException | LinkageError unavailable) {
            return MachineProcessingCapabilities.unavailable("native_processing_descriptor_unavailable:" + unavailable.getClass().getSimpleName());
        }
    }

    public static MachineProcessingCapabilities describe(BlockEntity entity) {
        try {
            Class<?> smart = Class.forName(SMART), type = Class.forName(TYPE);
            if (!smart.isInstance(entity)) return MachineProcessingCapabilities.unavailable("no_native_smart_processing_contract");
            var get = smart.getMethod("getBehaviour", type);
            Object processor = get.invoke(entity, Class.forName(PROCESSOR).getField("TYPE").get(null));
            Object surface = get.invoke(entity, Class.forName(SURFACE).getField("TYPE").get(null));
            boolean belt = Class.forName(BELT).isInstance(entity);
            // 机械手的向下使用条件来自原生加工回调，和处理的是哪种配方无关。
            Map<String, String> required = Class.forName(DEPLOYER).isInstance(entity) ? Map.of("facing", "down") : Map.of();
            boolean moving = belt;
            if (belt) {
                var slope = entity.getBlockState().getBlock().getStateDefinition().getProperty("slope");
                String value = slope == null ? "unknown" : entity.getBlockState().getValue(slope).toString();
                if (!value.equalsIgnoreCase("horizontal")) surface = null;
                moving = List.of("horizontal", "upward", "downward").stream().anyMatch(value::equalsIgnoreCase);
            }
            return new MachineProcessingCapabilities(processor != null, surface != null, moving,
                    new BlockPos(0, -2, 0), List.of(new BlockPos(0, -1, 0)), required, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return MachineProcessingCapabilities.unavailable("native_processing_api_unavailable:" + unavailable.getClass().getSimpleName());
        }
    }

    public static JsonObject descriptor(BlockState state) {
        JsonObject result = describe(state).json();
        result.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        result.addProperty("evidence", "installed_native_behaviour_interfaces");
        return result;
    }

    /** 原生加工间隙允许漏斗及无碰撞形状；最终施工仍会在真实世界重复核对。 */
    public static boolean openProcessingSpace(BlockState state) {
        if (state.isAir()) return true;
        try {
            return Class.forName("com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock").isInstance(state.getBlock())
                    || state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
}
