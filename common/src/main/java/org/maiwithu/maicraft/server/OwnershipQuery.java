// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端可查询的只读操作"一格方块是谁放的"：按请求里的位置查本服务器的方块归属记录。
 * 只回答记录里有什么；记录还没登记（服务器刚启动的第一刻之前）或没记到都答"没有归属"，不猜。
 */
public final class OwnershipQuery implements BiFunction<ServerPlayer, JsonObject, JsonObject> {

    /** 这个操作在客户端与服务端之间的编号。 */
    public static final String OPERATION = "ownership.query";

    @Override
    public JsonObject apply(ServerPlayer player, JsonObject body) {
        JsonObject result = new JsonObject();
        BlockOwnershipRecord record = ServerLinkServices.ownership(player.serverLevel().getServer());
        if (record == null) {
            result.addProperty("owned", false);
            return result;
        }
        JsonObject position = body.getAsJsonObject("position");
        var owner = record.ownerOf(player.serverLevel().dimension().location().toString(),
                new BlockPos(position.get("x").getAsInt(), position.get("y").getAsInt(), position.get("z").getAsInt()));
        if (owner.isEmpty()) {
            result.addProperty("owned", false);
            return result;
        }
        result.addProperty("owned", true);
        result.addProperty("owner", owner.get().playerId().toString());
        result.addProperty("placedTick", owner.get().tick());
        return result;
    }
}
