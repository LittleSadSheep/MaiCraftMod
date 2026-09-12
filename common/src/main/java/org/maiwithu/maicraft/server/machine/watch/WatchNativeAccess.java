// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.watch;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.server.inventory.NativeItemPort;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import org.maiwithu.maicraft.server.machine.ServerMachineRecipe;

/** Registration uses nearby native access. The resulting finite lease permits only these loaded identities to be read. */
final class WatchNativeAccess {
    record Endpoint(BlockState state, BlockEntity entity) {}
    record Recipe(String id, boolean targetOutput) {}
    private WatchNativeAccess() {}
    static Endpoint authorize(ServerPlayer player, BlockPos position) {
        BlockEntity entity = ServerAccess.check(player,position,false);
        return new Endpoint(player.serverLevel().getBlockState(position),entity);
    }
    static Recipe recipe(ServerPlayer player, WatchGoal goal, WatchGoal.Process process) {
        BlockEntity entity = ServerAccess.check(player,process.position(),false);
        boolean press = NativeApi.is(entity,"com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity");
        BlockPos expectedOutput = press ? process.position().below(2) : process.position();
        if (!expectedOutput.equals(process.outputPosition())) throw WatchGoal.invalid("Output position does not belong to the native process");
        JsonObject body = new JsonObject(); body.add("position",position(process.position())); body.addProperty("recipe_id",process.recipeId());
        JsonObject recipe = ServerMachineRecipe.inspect(player,body);
        if (!flag(recipe,"complete") || !flag(recipe,"compatible") || !"verified".equals(text(recipe,"compatibility"))
                || flag(recipe,"truncated") || recipe.has("unknown") && !recipe.getAsJsonArray("unknown").isEmpty())
            throw WatchGoal.invalid("A complete compatible native recipe is required for " + process.id());
        boolean target = false;
        for (var raw : recipe.getAsJsonArray("outputs")) {
            JsonObject resource = raw.getAsJsonObject().getAsJsonObject("resource");
            if (resource != null && goal.identity().equals(resource.get("identity")) && goal.resourceId().equals(text(resource,"id"))) target = true;
        }
        return new Recipe(WatchGoal.text(recipe,"recipe_id",256),target);
    }

    /** No distance/menu check: registration granted this read lease; current ownership protection is still respected. */
    static String available(ServerPlayer player, ServerLevel level, Map<BlockPos,Endpoint> endpoints) {
        for (BlockPos position : endpoints.keySet()) if (!level.isLoaded(position)) return "waiting_loaded";
        for (var entry : endpoints.entrySet()) {
            BlockPos position = entry.getKey(); Endpoint expected = entry.getValue();
            if (level.getBlockState(position).getBlock() != expected.state().getBlock() || level.getBlockEntity(position) != expected.entity()) return "needs_reinspect";
            if (!level.getWorldBorder().isWithinBounds(position) || !level.mayInteract(player,position)) return "permission_changed";
            if (expected.entity() instanceof BaseContainerBlockEntity container && !container.canOpen(player)) return "permission_changed";
            if (NativeApi.present("mekanism.api.security.IBlockSecurityUtils")) {
                Object security = NativeApi.constant("mekanism.api.security.IBlockSecurityUtils","INSTANCE");
                if (!NativeApi.truth(NativeApi.call(security,"mekanism.api.security.IBlockSecurityUtils","canAccess",player,level,position))) return "permission_changed";
            }
        }
        return null;
    }
    static long sink(ServerPlayer player, WatchGoal goal) {
        var level = player.serverLevel();
        if (!level.isLoaded(goal.sink())) throw WatchGoal.invalid("sink_unloaded");
        NativeItemPort port = NativeItemPort.find(level,goal.sink(),goal.sinkSide());
        int count = port == null ? -1 : port.slots();
        if (count < 0 || count > 128) throw WatchGoal.invalid("bounded_native_sink_required");
        long amount = 0; JsonObject identity = goal.identity(); String item = identity.get("id").getAsString();
        for (int index = 0; index < count; index++) {
            var stack = port.stack(index);
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) continue;
            if (identity.equals(ResourceIdentity.item(stack,player.registryAccess()))) amount = Math.addExact(amount,stack.getCount());
        }
        return amount;
    }
    static JsonObject position(BlockPos position) {
        JsonObject result = new JsonObject(); result.addProperty("x",position.getX()); result.addProperty("y",position.getY()); result.addProperty("z",position.getZ()); return result;
    }
    private static String text(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonPrimitive() ? value.get(name).getAsString() : ""; }
    private static boolean flag(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonPrimitive() && value.getAsJsonPrimitive(name).isBoolean() && value.get(name).getAsBoolean(); }
}
