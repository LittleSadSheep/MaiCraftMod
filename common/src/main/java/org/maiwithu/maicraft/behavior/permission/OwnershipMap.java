// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.game.serverlink.ReceivedConfirmations;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;

/**
 * 方块归属的读端：按区块向服务端 MaiCraft 问"这个区块里哪些格是谁放的"，问到的整块记下来。
 *
 * <p>角色走到哪，就先把周围几个区块问好；任务或寻路问到还没问过的区块，记下要问，下一刻补问。
 * 一个区块问过了才算"知道"：问过的格子没有记录就是没人放的；还没问过就说不知道，
 * 由保护判断按"拿不准就按受保护处理"。记下的答案过一阵会重新问，别人新盖的也认得出来。
 *
 * <p>角色自己刚放下的格不等重问：服务端每放下一格都会推一条确认给角色，这里当刻记成"角色自己放的"，
 * 直到比它晚发出的询问有了回答，再以回答为准。不先记下，刚装好的门在下一次重问之前查不到记录，
 * 只能靠"像玩家放的门"去猜，被当成别人的东西不许动。
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

    /** 一个区块里有主的格子：位置 → 放置人编号；发出这次询问的时刻（比它早放下的格，回答里都有了）。 */
    record ChunkOwners(Map<Long, String> owners, long fetchedAtMillis) {}

    /** 一次在途的询问：请求编号与发出的时刻。 */
    private record Asked(UUID id, long atMillis) {}

    private final ServerLinkSession session;
    /** 角色自己的玩家编号：服务端推来的放置确认都是角色自己的动作，按这个编号记。 */
    private final String selfPlayerId;
    /** 角色此刻所在的维度：每刻刷新；位置没写维度（"角色当前维度"的坐标）时按它查。 */
    private volatile String currentDimension;
    private final Map<ChunkKey, ChunkOwners> known = new ConcurrentHashMap<>();
    private final Set<ChunkKey> wanted = ConcurrentHashMap.newKeySet();
    /** 角色自己刚放下、服务端已经确认、还没等到新回答的格：区块 → 位置 → 记下的时刻。 */
    private final Map<ChunkKey, Map<Long, Long>> ownPlacements = new ConcurrentHashMap<>();
    /** 在途的请求与失败时刻：只在客户端刻里读写。 */
    private final Map<ChunkKey, Asked> pending = new HashMap<>();
    private final Map<ChunkKey, Long> failedAt = new HashMap<>();
    /** 服务端确认读到了第几条：每刻只看新来的。 */
    private long confirmationsRead;

    /**
     * @param session      服务端链路：按区块问归属，也从这里收角色自己放下、拆掉方块的确认
     * @param selfPlayerId 角色自己的玩家编号
     */
    public OwnershipMap(ServerLinkSession session, String selfPlayerId) {
        this.session = Objects.requireNonNull(session, "session");
        this.selfPlayerId = Objects.requireNonNull(selfPlayerId, "selfPlayerId");
        this.confirmationsRead = session.confirmations().total();
    }

    @Override
    public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
        ChunkKey key = keyOf(dimension, x, z);
        if (key == null) return Optional.empty();
        // 自己刚放下的先认：问到的回答比它早，回答里还没有这一格。
        if (ownPlaced(key, x, y, z)) return Optional.of(new PlacedBy(selfPlayerId));
        ChunkOwners chunk = known.get(key);
        if (chunk == null) return Optional.empty();
        return Optional.ofNullable(chunk.owners().get(BlockPos.asLong(x, y, z))).map(PlacedBy::new);
    }

    @Override
    public boolean known(String dimension, int x, int y, int z) {
        ChunkKey key = keyOf(dimension, x, z);
        // 连角色在哪个维度都还不知道（刚进世界还没走过一刻）：说不知道，也没法记下要问哪块。
        if (key == null) return false;
        if (known.containsKey(key)) return true;
        // 区块还没问过，但这一格是自己刚放下的：服务端已经确认过，不算拿不准。
        if (ownPlaced(key, x, y, z)) return true;
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
        long now = System.currentTimeMillis();
        readOwnChanges(now);
        settle();
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

    /**
     * 记下一块问到的归属：位置 → 放置人编号。
     *
     * @param fetchedAtMillis 发出这次询问的时刻：比它早记下的自己放的格，服务端回答时已经记着了，以回答为准
     */
    void remember(ChunkKey key, Map<Long, String> owners, long fetchedAtMillis) {
        known.put(key, new ChunkOwners(owners, fetchedAtMillis));
        failedAt.remove(key);
        Map<Long, Long> own = ownPlacements.get(key);
        if (own == null) return;
        own.values().removeIf(notedAt -> notedAt < fetchedAtMillis);
        if (own.isEmpty()) ownPlacements.remove(key, own);
    }

    /**
     * 服务端推来的一条角色自己的放置或拆除：放下的格记成角色自己放的，两格高的（门、高花）连上半一起记；
     * 拆掉的去掉这一笔，回到以问到的回答为准。
     */
    void ownChange(ReceivedConfirmations.ServerConfirmation change, long nowMillis) {
        ChunkKey key = keyOf(change.dimension(), change.x(), change.z());
        if (key == null) return;
        long pos = BlockPos.asLong(change.x(), change.y(), change.z());
        switch (change.action()) {
            case "place" -> {
                Map<Long, Long> own = ownPlacements.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
                own.put(pos, nowMillis);
                // 门的上半是原版顺带放的，服务端不另推一条：按方块本身认出两格高，上半一并记成自己的。
                if (twoTall(change.blockId())) own.put(BlockPos.asLong(change.x(), change.y() + 1, change.z()), nowMillis);
            }
            case "break" -> {
                Map<Long, Long> own = ownPlacements.get(key);
                if (own != null) own.remove(pos);
            }
            default -> { }
        }
    }

    // 这一格是不是自己刚放下、还没等到新回答的。
    private boolean ownPlaced(ChunkKey key, int x, int y, int z) {
        Map<Long, Long> own = ownPlacements.get(key);
        return own != null && own.containsKey(BlockPos.asLong(x, y, z));
    }

    // 每刻读一遍新来的服务端确认：只看上一刻之后到的，旧的已经记过。
    private void readOwnChanges(long now) {
        ReceivedConfirmations received = session.confirmations();
        long arrived = received.total() - confirmationsRead;
        confirmationsRead = received.total();
        if (arrived <= 0) return;
        List<ReceivedConfirmations.ServerConfirmation> recent = received.recent();
        for (int i = (int) Math.max(0, recent.size() - arrived); i < recent.size(); i++) ownChange(recent.get(i), now);
    }

    // 放下去占两格高、上半由原版顺带放的方块：门、高花这类有上下半的。
    private static boolean twoTall(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        return id != null && BuiltInRegistries.BLOCK.getOptional(id)
                .map(block -> block.defaultBlockState().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
                .orElse(false);
    }

    /** 离开世界或换了服务器：记下的归属全部作废，在途的请求不再等。 */
    public void forgetAll() {
        currentDimension = null;
        known.clear();
        ownPlacements.clear();
        confirmationsRead = session.confirmations().total();
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
        pending.put(key, new Asked(request.id(), System.currentTimeMillis()));
    }

    // 收回已结算的回答：成功的整块记下，失败或请求已退场的记下失败时刻，过一阵再问。
    private void settle() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<ChunkKey, Asked>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ChunkKey, Asked> entry = it.next();
            Optional<ClientRequest.Snapshot> snapshot = session.router().poll(entry.getValue().id());
            if (snapshot.isPresent() && !snapshot.get().settled()) continue;
            it.remove();
            Optional<Map<Long, String>> owners = snapshot.flatMap(OwnershipMap::parse);
            if (owners.isPresent()) {
                // 按发问的时刻记：在途期间自己又放下的格，这份回答里还没有，留着自己记的那一笔。
                remember(entry.getKey(), owners.get(), entry.getValue().atMillis());
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
