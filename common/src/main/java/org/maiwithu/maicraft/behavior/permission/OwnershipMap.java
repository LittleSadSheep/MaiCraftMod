// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;

/**
 * 方块归属的读端：按区块向服务端 MaiCraft 问"这个区块里哪些格是谁放的"，问到的整块记下来。
 *
 * <p>角色走到哪，就先把周围几个区块问好；任务或寻路问到还没问过的区块，记下要问，下一刻补问。
 * 一个区块问过了才算"知道"：问过的格子没有记录就是没人放的；还没问过就说不知道，
 * 由保护判断按"拿不准就按受保护处理"。记下的答案过一阵会重新问，别人新盖的也认得出来。
 *
 * <p>读可以在任何线程（寻路在后台线程上算路时也要问）；发请求、收回答只在客户端刻里做。
 */
public final class OwnershipMap implements ReadsBlockOwnership {

    /** 角色周围预先问好的区块半径：寻路与采集多在这个范围里动手。 */
    static final int PREFETCH_RADIUS_CHUNKS = 4;
    /** 有人等着要的区块，离角色多远以内才去问：再远的等走近了再说。 */
    static final int WANTED_RADIUS_CHUNKS = 12;
    /** 同时在途的请求上限：不一口气压服务端。 */
    static final int MAX_IN_FLIGHT = 6;
    /** 问过的区块多久重问一次：别人新盖的房子过一阵就认得出来。 */
    static final long REFRESH_AFTER_MILLIS = 30_000L;
    /** 请求失败后多久再试。 */
    static final long RETRY_AFTER_MILLIS = 5_000L;

    /** 一个区块：归属按维度分开记。 */
    record ChunkKey(String dimension, int x, int z) {}

    /** 一个区块里有主的格子：位置 → 放置人编号；问到的时刻。 */
    record ChunkOwners(Map<Long, String> owners, long fetchedAtMillis) {}

    private final ServerLinkSession session;
    /** 角色此刻所在的维度：每刻刷新；位置没写维度（"角色当前维度"的坐标）时按它查。 */
    private volatile String currentDimension;
    private final Map<ChunkKey, ChunkOwners> known = new ConcurrentHashMap<>();
    private final Set<ChunkKey> wanted = ConcurrentHashMap.newKeySet();
    /** 在途的请求与失败时刻：只在客户端刻里读写。 */
    private final Map<ChunkKey, UUID> pending = new HashMap<>();
    private final Map<ChunkKey, Long> failedAt = new HashMap<>();

    public OwnershipMap(ServerLinkSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
        ChunkKey key = keyOf(dimension, x, z);
        ChunkOwners chunk = key == null ? null : known.get(key);
        if (chunk == null) return Optional.empty();
        return Optional.ofNullable(chunk.owners().get(BlockPos.asLong(x, y, z))).map(PlacedBy::new);
    }

    @Override
    public boolean known(String dimension, int x, int y, int z) {
        ChunkKey key = keyOf(dimension, x, z);
        // 连角色在哪个维度都还不知道（刚进世界还没走过一刻）：说不知道，也没法记下要问哪块。
        if (key == null) return false;
        if (known.containsKey(key)) return true;
        // 还没问过：记下要问，下一刻在客户端刻里补问。
        wanted.add(key);
        return false;
    }

    /**
     * 每个客户端刻推进一次（客户端线程）：收回已结算的回答，再补问有人等着要的、角色周围还不知道或太旧的区块。
     *
     * @param dimension 角色此刻所在的维度；服务端只答角色所在维度的区块
     * @param feet      角色脚下那一格
     */
    public void tick(String dimension, BlockPos feet) {
        standingIn(dimension);
        if (!session.serverConfirmed()) return;
        settle();
        long now = System.currentTimeMillis();
        int centerX = SectionPos.blockToSectionCoord(feet.getX());
        int centerZ = SectionPos.blockToSectionCoord(feet.getZ());
        // 有人等着要的先问；别的维度的、离得太远的先不问，走近了再说。
        for (Iterator<ChunkKey> it = wanted.iterator(); it.hasNext() && pending.size() < MAX_IN_FLIGHT; ) {
            ChunkKey key = it.next();
            it.remove();
            if (key.dimension().equals(dimension) && Math.abs(key.x() - centerX) <= WANTED_RADIUS_CHUNKS
                    && Math.abs(key.z() - centerZ) <= WANTED_RADIUS_CHUNKS && due(key, now)) {
                submit(key);
            }
        }
        // 再按由近及远补问角色周围的区块。
        for (int ring = 0; ring <= PREFETCH_RADIUS_CHUNKS && pending.size() < MAX_IN_FLIGHT; ring++) {
            for (int dx = -ring; dx <= ring && pending.size() < MAX_IN_FLIGHT; dx++) {
                for (int dz = -ring; dz <= ring && pending.size() < MAX_IN_FLIGHT; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    ChunkKey key = new ChunkKey(dimension, centerX + dx, centerZ + dz);
                    if (due(key, now)) submit(key);
                }
            }
        }
    }

    /** 角色此刻在哪个维度：没写维度的位置按它查。 */
    void standingIn(String dimension) {
        currentDimension = dimension;
    }

    /** 记下一块问到的归属：位置 → 放置人编号。 */
    void remember(ChunkKey key, Map<Long, String> owners, long fetchedAtMillis) {
        known.put(key, new ChunkOwners(owners, fetchedAtMillis));
        failedAt.remove(key);
    }

    /** 离开世界或换了服务器：记下的归属全部作废，在途的请求不再等。 */
    public void forgetAll() {
        currentDimension = null;
        known.clear();
        wanted.clear();
        pending.clear();
        failedAt.clear();
    }

    // 该不该问：在途的不重问；刚失败的过一阵再问；问过的太旧了才重问。
    private boolean due(ChunkKey key, long now) {
        if (pending.containsKey(key)) return false;
        Long failed = failedAt.get(key);
        if (failed != null && now - failed < RETRY_AFTER_MILLIS) return false;
        ChunkOwners chunk = known.get(key);
        return chunk == null || now - chunk.fetchedAtMillis() >= REFRESH_AFTER_MILLIS;
    }

    private void submit(ChunkKey key) {
        JsonObject body = new JsonObject();
        body.addProperty("chunk_x", key.x());
        body.addProperty("chunk_z", key.z());
        // 只读请求：不改世界。
        ClientRequest request = session.router().submit("ownership.chunk", body, false);
        pending.put(key, request.id());
    }

    // 收回已结算的回答：成功的整块记下，失败或请求已退场的记下失败时刻，过一阵再问。
    private void settle() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<ChunkKey, UUID>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ChunkKey, UUID> entry = it.next();
            Optional<ClientRequest.Snapshot> snapshot = session.router().poll(entry.getValue());
            if (snapshot.isPresent() && !snapshot.get().settled()) continue;
            it.remove();
            Optional<Map<Long, String>> owners = snapshot.flatMap(OwnershipMap::parse);
            if (owners.isPresent()) {
                remember(entry.getKey(), owners.get(), now);
            } else {
                failedAt.put(entry.getKey(), now);
            }
        }
    }

    /** 回答换成位置 → 放置人：每格四个数（x、y、z、放置人在表里的序号）；请求失败或回答缺字段给空。 */
    static Optional<Map<Long, String>> parse(ClientRequest.Snapshot snapshot) {
        if (snapshot.status() != ClientRequest.Status.SUCCEEDED) return Optional.empty();
        JsonObject result = snapshot.result();
        if (!result.has("owners") || !result.has("cells")) return Optional.empty();
        JsonArray owners = result.getAsJsonArray("owners");
        JsonArray cells = result.getAsJsonArray("cells");
        Map<Long, String> map = new HashMap<>();
        for (int i = 0; i + 3 < cells.size(); i += 4) {
            int person = cells.get(i + 3).getAsInt();
            if (person < 0 || person >= owners.size()) continue;
            map.put(BlockPos.asLong(cells.get(i).getAsInt(), cells.get(i + 1).getAsInt(), cells.get(i + 2).getAsInt()),
                    owners.get(person).getAsString());
        }
        return Optional.of(Map.copyOf(map));
    }

    // 位置所在的区块；没写维度就是角色此刻所在的维度，那也不知道时给空。
    private ChunkKey keyOf(String dimension, int x, int z) {
        String resolved = dimension != null ? dimension : currentDimension;
        if (resolved == null) return null;
        return new ChunkKey(resolved, SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }
}
