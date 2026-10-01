// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 测试夹具直接生成已声明的方块、原料与配置；不替模型改布局，不伪造运输或生产事件。 */
final class BlueprintTestNative {
    private BlueprintTestNative() {}
    static BlockPos position(JsonArray value) {
        if (value == null || value.size() != 3) throw new IllegalArgumentException("anchor/offset requires three integers");
        return new BlockPos(value.get(0).getAsInt(), value.get(1).getAsInt(), value.get(2).getAsInt());
    }
    static JsonObject apply(ServerLevel world, MachineConstructionPlan plan) {
        var result = new JsonObject(); var errors = new JsonArray(); int changed = 0;
        var deferredUpdates = new HashSet<BlockPos>();
        var retained = new HashSet<BlockPos>();
        // 修改只触及真实差异；整份蓝图重交也不能把运行中的旧皮带改回准备轴并清掉带上工件。
        for (var installation : plan.installations()) if (installation.matches(world)) retained.addAll(installation.targets().keySet());
        // 清空声明格 -> 普通实体与承载面 -> 流体 -> 原生皮带；统一通知后由原模组继续邻接、流体与动力结算。
        var targets = plan.blocks().stream().sorted(Comparator.comparingInt((BuildTaskRecord.Target target)
                -> target.desiredState().isAir() ? 0 : target.desiredState().getFluidState().isEmpty() ? 1 : 2)
                .thenComparingInt(target -> target.pos().getY())).toList();
        for (var target : targets) {
            if (retained.contains(target.pos())) continue;
            try {
                world.getChunkAt(target.pos());
                var before = world.getBlockState(target.pos());
                if (!before.equals(target.desiredState()) && NativeApi.is(world.getBlockEntity(target.pos()),
                        "com.simibubi.create.content.kinetics.base.KineticBlockEntity")) {
                    // 改已有齿轮箱或轴的朝向时走 Create 的拆接网络回调，不能只改外观后留下旧动力关系。
                    NativeApi.call(null, "com.simibubi.create.content.kinetics.base.KineticBlockEntity", "switchToBlockState",
                            world, target.pos(), target.desiredState());
                    if (!world.getBlockState(target.pos()).equals(before)) changed++;
                } else if (world.setBlock(target.pos(), target.desiredState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                    changed++; deferredUpdates.add(target.pos());
                }
            } catch (RuntimeException failure) { errors.add(target.pos() + ": " + failure); }
        }
        int installationIndex = 0;
        if (plan.blueprint().has("assembly")) for (var raw : array(plan.blueprint().getAsJsonObject("assembly"), "installations")) {
            var installation = raw.getAsJsonObject();
            try {
                // 连接器的公开方法负责原生皮带状态；后续控制器发现与网络传播仍照常运行。
                if (plan.installations().get(installationIndex++).matches(world)) continue;
                NativeApi.call(null, "com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem", "createBelts", world,
                        plan.anchor().offset(position(installation.getAsJsonArray("first"))),
                        plan.anchor().offset(position(installation.getAsJsonArray("second"))));
            } catch (RuntimeException failure) { errors.add("installation: " + failure); }
        }
        for (BlockPos at : plan.positions()) {
            var state = world.getBlockState(at);
            state.updateNeighbourShapes(world, at, Block.UPDATE_ALL);
            // 批量生成时延后的间接邻接也必须补齐；Create 正是在这里清旧网络并登记下一刻的动力重算。
            if (deferredUpdates.contains(at)) state.updateIndirectNeighbourShapes(world, at, Block.UPDATE_ALL);
            world.updateNeighborsAt(at, state.getBlock());
            if (!state.getFluidState().isEmpty()) world.scheduleTick(at, state.getFluidState().getType(), state.getFluidState().getType().getTickDelay(world));
        }
        result.addProperty("changed_block_targets", changed); result.addProperty("declared_targets", plan.positions().size());
        result.add("generation_errors", errors); return result;
    }

    static JsonObject fixtures(ServerLevel world, BlockPos anchor, JsonObject request) {
        var result = new JsonObject(); var inputs = new JsonArray(); var configurations = new JsonArray();
        for (var raw : array(request, "inputs")) {
            var input = raw.getAsJsonObject(); var row = input.deepCopy(); inputs.add(row);
            try {
                BlockPos at = anchor.offset(position(input.getAsJsonArray("offset")));
                var id = ResourceLocation.parse(input.get("item_id").getAsString());
                if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("unknown input item");
                // 测试可以提供充足原料，但成品不能作为夹具注入再冒充运行产出。
                if (id.toString().equals("create:precision_mechanism") || id.toString().equals("create:incomplete_precision_mechanism"))
                    throw new IllegalArgumentException("test output cannot be injected as an input fixture");
                var handler = world.getCapability(Capabilities.ItemHandler.BLOCK, at, null);
                if (handler == null) throw new IllegalArgumentException("no native item inventory at input offset");
                int desired = input.get("count").getAsInt(); if (desired < 0 || desired > 32768) throw new IllegalArgumentException("input count out of range");
                int existing = 0;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    var stack = handler.getStackInSlot(slot); if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) existing += stack.getCount();
                }
                int remaining = Math.max(0, desired - existing), added = 0;
                for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
                    var stack = new ItemStack(BuiltInRegistries.ITEM.get(id), Math.min(remaining, BuiltInRegistries.ITEM.get(id).getDefaultMaxStackSize()));
                    int amount = stack.getCount() - handler.insertItem(slot, stack, false).getCount(); remaining -= amount; added += amount;
                }
                row.addProperty("inserted", added); row.addProperty("remaining_uninserted", remaining);
            } catch (RuntimeException failure) { row.addProperty("error", failure.toString()); }
        }
        for (var raw : array(request, "configurations")) {
            var config = raw.getAsJsonObject(); var row = config.deepCopy(); configurations.add(row);
            try {
                var entity = world.getBlockEntity(anchor.offset(position(config.getAsJsonArray("offset"))));
                String action = config.get("action").getAsString();
                if (action.equals("creative_motor.speed") || action.equals("create.speed")) {
                    var setting = NativeApi.field(entity, null, action.equals("creative_motor.speed") ? "generatedSpeed" : "targetSpeed");
                    NativeApi.call(setting, null, "setValue", config.get("value").getAsInt());
                    row.addProperty("actual_value", ((Number) NativeApi.call(setting, null, "getValue")).intValue());
                } else if (action.equals("create.filter")) {
                    String type = "com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour";
                    var filter = NativeApi.call(entity, null, "getBehaviour", NativeApi.constant(type, "TYPE"));
                    var item = config.has("item_id") ? new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(config.get("item_id").getAsString()))) : ItemStack.EMPTY;
                    row.addProperty("accepted", NativeApi.truth(NativeApi.call(filter, type, "setFilter", item)));
                } else throw new IllegalArgumentException("unsupported test configuration");
                entity.setChanged(); world.sendBlockUpdated(entity.getBlockPos(), entity.getBlockState(), entity.getBlockState(), Block.UPDATE_ALL);
            } catch (RuntimeException failure) { row.addProperty("error", failure.toString()); }
        }
        result.add("inputs", inputs); result.add("configurations", configurations); return result;
    }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) ? object.getAsJsonArray(key) : new JsonArray(); }
}
