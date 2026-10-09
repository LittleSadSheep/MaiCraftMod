// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端可查询的只读操作"这个区块里哪些格是谁放的"：按请求里的区块查本服务器的方块归属记录。
 *
 * <p>一次答一整个区块：角色走到哪、寻路要挖哪，客户端都按区块先问好记下，不逐格问。
 * 只回答记录里有什么；记录还没登记（服务器刚启动的第一刻之前）就答"这个区块一格都没记到"，不猜。
 * 回答里放置人单列一张表，每一格只带它在表里的序号：同一个人盖的房子不重复写他的编号。
 */
public final class OwnershipQuery implements BiFunction<ServerPlayer, JsonObject, JsonObject> {

    /** 这个操作在客户端与服务端之间的编号。 */
    public static final String OPERATION = "ownership.chunk";

    @Override
    public JsonObject apply(ServerPlayer player, JsonObject body) {
        JsonObject result = new JsonObject();
        JsonArray owners = new JsonArray();
        JsonArray cells = new JsonArray();
        result.add("owners", owners);
        result.add("cells", cells);
        BlockOwnershipRecord record = ServerLinkServices.ownership(player.serverLevel().getServer());
        if (record == null) {
            return result;
        }
        var owned = record.ownedInChunk(player.serverLevel().dimension().location().toString(),
                body.get("chunk_x").getAsInt(), body.get("chunk_z").getAsInt());
        // 每一格写成四个数：x、y、z、放置人在表里的序号。
        List<UUID> people = new ArrayList<>();
        Map<UUID, Integer> index = new HashMap<>();
        owned.forEach((position, owner) -> {
            int person = index.computeIfAbsent(owner.playerId(), id -> {
                people.add(id);
                return people.size() - 1;
            });
            cells.add(position.getX());
            cells.add(position.getY());
            cells.add(position.getZ());
            cells.add(person);
        });
        people.forEach(person -> owners.add(person.toString()));
        return result;
    }
}
