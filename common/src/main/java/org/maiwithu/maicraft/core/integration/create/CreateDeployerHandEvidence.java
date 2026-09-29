// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.machine.create.CreateDeployerHandView;

/** 交换前后保留机械手自己的持料观察；同物同数没有变化时仍保持动作不确定，不伪造点击成功。 */
public final class CreateDeployerHandEvidence {
    private CreateDeployerHandEvidence() {}
    public static Map<String,Object> capture(Level world, BlockPos at) {
        if (at == null || !world.isLoaded(at)
                || !BuiltInRegistries.BLOCK.getKey(world.getBlockState(at).getBlock()).toString().equals("create:deployer")) return Map.of();
        var entity = world.getBlockEntity(at);
        return data(entity instanceof CreateDeployerHandView view ? view : null, world.registryAccess());
    }
    static Map<String,Object> data(CreateDeployerHandView view, HolderLookup.Provider registries) {
        if (view == null || view.maicraft$handUpdateRevision() == 0)
            return Map.of("observation_status","not_observed","provenance","client_received_native_update");
        var stack = view.maicraft$receivedHandStack();
        var result = new LinkedHashMap<String,Object>();
        result.put("observation_status","observed"); result.put("provenance","client_received_native_update");
        result.put("received_update_revision",view.maicraft$handUpdateRevision());
        result.put("item_id",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); result.put("count",stack.getCount());
        // 工件的序列进度也属于物品身份；保留原生组件，不能把所有未完成精密构件视为同一加工阶段。
        if (!stack.isEmpty()) {
            try {
                var encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,registries),stack).result();
                result.put("components_observed",encoded.isPresent());
                encoded.ifPresent(value -> result.put("stack",value));
            } catch (RuntimeException | LinkageError unavailable) {
                // 可选组件编码失败不影响真实交互；保留已读到的种类数量，并明确组件尚不可观察。
                result.put("components_observed",false);
            }
        }
        return Map.copyOf(result);
    }
}
