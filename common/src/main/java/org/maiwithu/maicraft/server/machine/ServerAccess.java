// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.network.ServerOperationException;

/** All callers run on the game thread, with loaded chunks and the real player's authority. */
public final class ServerAccess {
    public static final int OBSERVATION_RADIUS = 16;
    private ServerAccess() {}

    public static BlockPos position(JsonObject body) {
        return new BlockPos(integer(body, "x", -30_000_000, 30_000_000),
                integer(body, "y", -2048, 2048), integer(body, "z", -30_000_000, 30_000_000));
    }

    public static int integer(JsonObject body, String name, int minimum, int maximum) {
        try {
            if (!body.has(name) || !body.get(name).isJsonPrimitive()
                    || !body.getAsJsonPrimitive(name).isNumber()) throw new IllegalArgumentException();
            int value = body.get(name).getAsBigDecimal().intValueExact();
            if (value < minimum || value > maximum) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw denied("invalid_argument", name + " outside allowed range"); }
    }

    public static String text(JsonObject body, String name) {
        if (!body.has(name) || !body.get(name).isJsonPrimitive()
                || !body.getAsJsonPrimitive(name).isString() || body.get(name).getAsString().length() > 512) {
            throw denied("invalid_argument", name + " must be a bounded string");
        }
        return body.get(name).getAsString();
    }

    public static Direction side(JsonObject body) {
        Direction result = Direction.byName(text(body, "side"));
        if (result == null) throw denied("invalid_argument", "Unknown side");
        return result;
    }

    public static boolean bool(JsonObject body, String name) {
        if (body == null || !body.has(name) || !body.get(name).isJsonPrimitive()
                || !body.getAsJsonPrimitive(name).isBoolean()) throw denied("invalid_argument", name + " must be a boolean");
        return body.get(name).getAsBoolean();
    }

    public static BlockEntity check(ServerPlayer player, BlockPos pos, boolean mutate) {
        return check(player, pos, mutate, mutate);
    }

    /** Same physical/owner preflight as a transfer, without posting a real interaction event. */
    public static BlockEntity preview(ServerPlayer player, BlockPos pos) {
        return check(player, pos, true, false);
    }

    private static BlockEntity check(ServerPlayer player, BlockPos pos, boolean interactive, boolean dispatchEvent) {
        if (player.getServer() == null || !player.getServer().isSameThread()) {
            throw denied("wrong_thread", "A server game thread is required");
        }
        var level = player.serverLevel();
        if (!player.isAlive() || player.isSpectator()) throw denied("player_unavailable", "Player cannot interact");
        if (!level.isLoaded(pos)) throw denied("unloaded", "Target chunk is not loaded");
        if (!level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(player, pos)) {
            throw denied("permission_denied", "World interaction denied");
        }
        if (interactive ? !player.canInteractWithBlock(pos, 0) :
                player.distanceToSqr(pos.getCenter()) > OBSERVATION_RADIUS * OBSERVATION_RADIUS) {
            throw denied("out_of_range", "Target is outside the permitted range");
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof BaseContainerBlockEntity container && !container.canOpen(player)) {
            throw denied("permission_denied", "Container is locked");
        }
        if (NativeApi.is(entity, "com.simibubi.create.foundation.blockEntity.SmartBlockEntity")
                && !NativeApi.truth(NativeApi.call(entity, null, "canPlayerUse", player))) {
            throw denied("permission_denied", "Create interaction denied");
        }
        if (NativeApi.present("mekanism.api.security.IBlockSecurityUtils")) {
            Object security = NativeApi.constant("mekanism.api.security.IBlockSecurityUtils", "INSTANCE");
            if (!NativeApi.truth(NativeApi.call(security, "mekanism.api.security.IBlockSecurityUtils", "canAccess",
                    player, level, pos))) throw denied("permission_denied", "Mekanism security denied access");
        }
        if (interactive) {
            if (!player.mayBuild()) throw denied("permission_denied", "Player cannot modify machines");
            if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) {
                throw denied("menu_busy", "Close the active menu before a server transaction");
            }
            BlockHitResult hit = visibleHit(player, pos);
            if (dispatchEvent) {
                checkInteractionEvent(player, pos, hit);
                if (player.serverLevel() != level || !player.isAlive() || !level.isLoaded(pos)
                        || level.getBlockEntity(pos) != entity || !player.canInteractWithBlock(pos, 0)
                        || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) {
                    // An event listener may itself change the world. Never operate on a removed target
                    // or call that event-bearing request "not applied" and authorize a replay.
                    throw new IllegalStateException("Interaction event changed player or target context; effect is uncertain");
                }
            }
        }
        return entity;
    }

    private static BlockHitResult visibleHit(ServerPlayer player, BlockPos pos) {
        var level = player.serverLevel();
        var eye = player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        // A short interaction ray must not cause chunk loads while traversing a boundary.
        for (int x = Math.min(origin.getX(), pos.getX()) >> 4; x <= (Math.max(origin.getX(), pos.getX()) >> 4); x++) {
            for (int z = Math.min(origin.getZ(), pos.getZ()) >> 4; z <= (Math.max(origin.getZ(), pos.getZ()) >> 4); z++) {
                if (!level.isLoaded(new BlockPos(x << 4, pos.getY(), z << 4))) throw denied("unloaded", "An interaction ray crosses unloaded chunks");
            }
        }
        var shape = level.getBlockState(pos).getShape(level, pos, net.minecraft.world.phys.shapes.CollisionContext.of(player));
        var candidates = shape.toAabbs().stream().map(box -> box.move(pos).getCenter())
                .sorted(java.util.Comparator.comparingDouble(eye::distanceToSqr)).limit(32).toList();
        for (var target : candidates) {
            BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit;
        }
        throw denied("occluded", "The target's actual interaction shape must be directly visible");
    }

    private static void checkInteractionEvent(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
        String hooks = "net.neoforged.neoforge.common.CommonHooks";
        if (NativeApi.present(hooks)) {
            Object event = NativeApi.call(null, hooks, "onRightClickBlock", player, InteractionHand.MAIN_HAND, pos, hit);
            if (NativeApi.truth(NativeApi.call(event, null, "isCanceled"))
                    || "FALSE".equals(String.valueOf(NativeApi.call(event, null, "getUseBlock")))
                    || "DENY".equals(String.valueOf(NativeApi.call(event, null, "getUseBlock")))) {
                throw denied("permission_denied", "A server interaction event denied access");
            }
        } else if (NativeApi.present("net.fabricmc.fabric.api.event.player.UseBlockCallback")) {
            Object event = NativeApi.constant("net.fabricmc.fabric.api.event.player.UseBlockCallback", "EVENT");
            Object callback = NativeApi.call(event, "net.fabricmc.fabric.api.event.Event", "invoker");
            Object result = NativeApi.call(callback, "net.fabricmc.fabric.api.event.player.UseBlockCallback", "interact",
                    player, player.serverLevel(), InteractionHand.MAIN_HAND, hit);
            if (!"PASS".equals(String.valueOf(result))) {
                throw denied("interaction_handled", "Another handler claimed the block interaction");
            }
        }
    }

    public static ServerOperationException denied(String code, String message) {
        return ServerOperationException.notApplied(code, message);
    }
}
