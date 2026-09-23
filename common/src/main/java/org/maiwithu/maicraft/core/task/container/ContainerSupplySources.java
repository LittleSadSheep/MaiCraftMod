// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Nameable;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.intent.IntentRuntime;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** 只寻找已加载的木桶、箱子和潜影盒；箱内数量必须实际打开界面看过，不能从客户端方块实体猜。 */
public final class ContainerSupplySources {
    public static final int MAX_ATTEMPTS = 8, MAX_SCANNED = 4096;
    public record Candidate(BlockPos position, ResourceLocation blockId, List<BlockPos> footprint, int stockRank) {
        public Candidate { position = position.immutable(); footprint = List.copyOf(footprint); }
    }
    private static final Cache CACHE = new Cache();
    private ContainerSupplySources() {}
    public static List<Candidate> candidates(LocalPlayer player, BlockPos center, int radius,
            List<ResourceLocation> items, Set<BlockPos> visited, List<String> protectedLabels) {
        // 缺料不是探索箱子的许可：只去最近实际看见目标材料的容器，未知、空箱或过期线索都跳过。
        List<Candidate> found = new ArrayList<>(); Set<BlockPos> seen = new HashSet<>(); int examined = 0;
        int chunks = (radius + 15) / 16;
        outer: for (int x = -chunks; x <= chunks; x++) for (int z = -chunks; z <= chunks; z++) {
            var chunk = player.clientLevel.getChunkSource().getChunk((center.getX() >> 4) + x, (center.getZ() >> 4) + z,
                    ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (BlockPos raw : List.copyOf(chunk.getBlockEntities().keySet())) {
                if (++examined > MAX_SCANNED) break outer;
                BlockPos at = raw.immutable();
                if (at.distSqr(center) > (long) radius * radius || seen.contains(at) || visited.contains(at) || !allowed(player, at, protectedLabels)) continue;
                List<BlockPos> footprint = footprint(player.level(), at); seen.addAll(footprint);
                if (footprint.stream().anyMatch(visited::contains)) continue;
                Map<BlockPos, Object> identities = identities(player.level(), footprint);
                var stock = CACHE.latest(player, player.level(), at, identities, player.level().getGameTime());
                if (stock == null || items.stream().noneMatch(id -> stock.storedCount(id) > 0)) continue;
                found.add(new Candidate(at, BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(at).getBlock()), footprint, 0));
            }
        }
        return found.stream().sorted(Comparator.comparingInt(Candidate::stockRank)
                .thenComparingDouble(value -> value.position().distSqr(center)).thenComparingLong(value -> value.position().asLong())).limit(MAX_ATTEMPTS).toList();
    }
    public static List<BlockPos> footprint(Level level, BlockPos at) {
        // 大箱子的两半是一份库存；两边都仍在、且互相连接，才允许把它当作同一个取料来源。
        if (!level.isLoaded(at) || !ordinary(level.getBlockEntity(at))) return List.of();
        var state = level.getBlockState(at);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return List.of(at.immutable());
        BlockPos peer = at.relative(ChestBlock.getConnectedDirection(state));
        if (!level.isLoaded(peer) || !(level.getBlockEntity(peer) instanceof ChestBlockEntity)) return List.of();
        var other = level.getBlockState(peer);
        if (other.getBlock() != state.getBlock() || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || !peer.relative(ChestBlock.getConnectedDirection(other)).equals(at)) return List.of();
        return List.of(at.immutable(), peer.immutable());
    }
    /** 自动取料开箱前重新核对线索；玩家指明的定向存取仍由公开容器能力单独执行。 */
    public static boolean hasObservedItems(LocalPlayer player, BlockPos at, List<ResourceLocation> items) {
        List<BlockPos> footprint = footprint(player.level(), at);
        if (footprint.isEmpty()) return false;
        var stock = CACHE.latest(player, player.level(), at, identities(player.level(), footprint), player.level().getGameTime());
        return stock != null && items.stream().anyMatch(id -> stock.storedCount(id) > 0);
    }
    public static boolean allowed(LocalPlayer player, BlockPos at, List<String> protectedLabels) {
        // 普通仓库仍只认箱、桶、潜影盒；任一半不允许访问就整箱不碰，不能把 AE 终端当普通槽位扫描。
        List<BlockPos> footprint = footprint(player.level(), at); if (footprint.isEmpty()) return false;
        for (BlockPos cell : footprint) if (!accessAllowed(player, cell, protectedLabels)) return false;
        return true;
    }
    /** 通用访问保护只回答这一个位置能否使用；调用者仍须核对自己的机器类型、身份和可见界面。 */
    public static boolean accessAllowed(LocalPlayer player, BlockPos at, List<String> protectedLabels) {
        if (player == null || at == null || !player.level().isLoaded(at) || NavigationSafetyContext.protectsUse(at)) return false;
        var entity = player.level().getBlockEntity(at);
        // 保护地标缺失时拒绝访问；只读当前保护范围和原生命名，不开界面、不读库存，也不缓存旧许可。
        for (String label : protectedLabels == null ? List.<String>of() : protectedLabels) {
            var landmark = IntentRuntime.get().landmark(label); if (landmark == null || landmark.position() == null) return false;
            var position = landmark.position();
            if ((position.dimension() == null || position.dimension().equals(player.level().dimension().location().toString()))
                    && at.distSqr(new BlockPos(position.x(), position.y(), position.z())) <= 12 * 12) return false;
            if (entity instanceof Nameable named && named.getCustomName() != null
                    && label.equalsIgnoreCase(named.getCustomName().getString())) return false;
        }
        return true;
    }
    public static void rememberVisible(LocalPlayer player, BlockPos at, AbstractContainerMenu menu, List<Integer> slots) {
        // 只有当前真实显示、且服务器已同步内容的菜单能更新库存线索；取存后用新数量覆盖旧观察。
        if (!StockEvidence.isContainerSynchronized(player, menu) || !MenuVisibility.matches(ClientRuntime.requireContext(player).minecraft(), menu)) return;
        var footprint = footprint(player.level(), at); if (footprint.isEmpty()) return;
        Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
        for (int slot : slots) { var stack = menu.getSlot(slot).getItem(); StockEvidence.add(counts, stack, stack.getCount()); }
        CACHE.record(player, player.level(), identities(player.level(), footprint), new StockEvidence.Snapshot(
                StockEvidence.Source.CONTAINER, counts, Set.of(), player.level().getGameTime()));
    }
    public static void reset() { CACHE.clear(); }
    /** 汇总附近最近实际看过的库存，供选工具或备料参考；这里不开箱、不扫描区域，也不保证货还在。 */
    public static Map<ResourceLocation, Long> observedCounts(LocalPlayer player, BlockPos center, int radius, List<String> protectedLabels) {
        if (player == null || center == null || radius < 0) return Map.of();
        List<String> labels = protectedLabels == null ? List.of() : protectedLabels;
        return CACHE.observed(player, player.level(), center, radius, player.level().getGameTime(),
                at -> player.level().isLoaded(at) && allowed(player, at, labels),
                at -> identities(player.level(), footprint(player.level(), at)));
    }
    private static boolean ordinary(BlockEntity entity) { return entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity || entity instanceof ShulkerBoxBlockEntity; }
    private static Map<BlockPos, Object> identities(Level level, List<BlockPos> positions) {
        Map<BlockPos, Object> result = new LinkedHashMap<>(); positions.forEach(pos -> result.put(pos, level.getBlockEntity(pos))); return result;
    }
    static final class Cache {
        private record Entry(Map<BlockPos, Object> identities, StockEvidence.Snapshot stock) {}
        private Object player, world;
        private final Map<BlockPos, Entry> entries = new LinkedHashMap<>();
        void record(Object owner, Object level, Map<BlockPos, Object> identities, StockEvidence.Snapshot stock) {
            bind(owner, level); Entry entry = new Entry(Map.copyOf(identities), stock);
            for (BlockPos at : identities.keySet()) { entries.remove(at); entries.put(at.immutable(), entry); }
            while (entries.size() > 64) entries.remove(entries.keySet().iterator().next());
        }
        StockEvidence.Snapshot latest(Object owner, Object level, BlockPos at, Map<BlockPos, Object> identities, long tick) {
            // 箱子任一半被换掉、观察过期或换了存档，就作废旧数量，不能拿上一只箱子的货继续算。
            bind(owner, level); Entry entry = entries.get(at); if (entry == null) return null;
            if (tick < entry.stock.observedGameTick() || tick - entry.stock.observedGameTick() > StockEvidence.MAX_AGE_TICKS
                    || !entry.identities.keySet().equals(identities.keySet())
                    || entry.identities.entrySet().stream().anyMatch(value -> identities.get(value.getKey()) != value.getValue())) {
                entries.remove(at); return null;
            }
            return entry.stock;
        }
        Map<ResourceLocation, Long> observed(Object owner, Object level, BlockPos center, int radius, long tick,
                Predicate<BlockPos> usable,
                Function<BlockPos, Map<BlockPos, Object>> currentFootprint) {
            bind(owner, level); Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
            // 一个大箱子虽然登记两个坐标，汇总备料时只计算一次里面的物品。
            Set<Entry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Entry entry : List.copyOf(entries.values())) {
                if (!seen.add(entry)) continue;
                long age = tick - entry.stock.observedGameTick();
                if (age < 0 || age > StockEvidence.MAX_AGE_TICKS) {
                    entries.entrySet().removeIf(row -> row.getValue() == entry); continue;
                }
                if (entry.identities.isEmpty() || entry.identities.keySet().stream()
                        .anyMatch(at -> at.distSqr(center) > (long) radius * radius || !usable.test(at))) continue;
                Map<BlockPos, Object> current = currentFootprint.apply(entry.identities.keySet().iterator().next());
                if (current == null || !entry.identities.keySet().equals(current.keySet())
                        || entry.identities.entrySet().stream().anyMatch(row -> current.get(row.getKey()) != row.getValue())) {
                    entries.entrySet().removeIf(row -> row.getValue() == entry); continue;
                }
                entry.stock.stored().forEach((item, amount) -> {
                    if (amount > 0) counts.merge(item, amount, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
                });
            }
            return Map.copyOf(counts);
        }
        private void bind(Object owner, Object level) { if (player != owner || world != level) { clear(); player = owner; world = level; } }
        void clear() { entries.clear(); player = world = null; }
    }
}
