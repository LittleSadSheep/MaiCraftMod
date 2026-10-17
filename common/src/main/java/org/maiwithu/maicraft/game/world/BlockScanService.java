// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 分几刻做完的方块扫描服务：费时的“找附近这些方块”都从这里走，一刻只花小预算，
 * 结果永远带着“是否扫完”。没扫完的部分不能当成“没有”。
 *
 * <p>它集中三件事：目标索引（登记关心的方块种类后按需增量查询）、
 * 大范围的分刻扫描（登记一次请求，之后每刻推进一点，完成时回调）、
 * 以及最近同步方块变化的位置（供读侧优先核查刚发生的事实）。
 * 实例在启动时创建并传给使用者；服务自身不认识任何任务或能力。
 */
public final class BlockScanService {
    /** 目标索引：缓存已加载方块里登记种类的位置，查询跨刻续进。 */
    private final TargetIndex index = new TargetIndex();
    /** 大范围扫描共享的每刻预算。 */
    private final SearchBudget budget = new SearchBudget();
    /** 等待中的分刻扫描；每刻按登记顺序推进，前面的可能先用完预算。 */
    private final List<BlockSearch> searches = new ArrayList<>();
    /** 客户端最近同步的方块变化位置。 */
    private final RecentBlockWrites recentWrites = new RecentBlockWrites();
    private int nextSearchId = 1;

    /** 任务开始时登记它关心的目标方块（计数式，可重入）。 */
    public void register(ClientLevel level, Collection<Block> blocks) {
        index.register(level, blocks);
    }

    /** 释放登记；索引暂留热缓存，整个维度闲置后按清扫周期回收。 */
    public void unregister(ClientLevel level, Collection<Block> blocks) {
        index.unregister(level, blocks);
    }

    /**
     * 从索引里取已登记目标的最近位置：一刻最多花小预算，未完成时返回已找到的部分和
     * {@code complete=false}，调用方下一刻再来问。
     */
    public TargetIndex.Result query(ClientLevel level, BlockPos center, Collection<Block> targets,
                                    int want, int maxChunkRadius, int buildBudget, Set<BlockPos> excluded,
                                    boolean sourcesOnly) {
        return index.query(level, center, targets, want, maxChunkRadius, buildBudget, excluded, sourcesOnly);
    }

    /** 同 {@link #query}，不带排除位置与水源过滤。 */
    public TargetIndex.Result query(ClientLevel level, BlockPos center, Collection<Block> targets,
                                    int want, int maxChunkRadius, int buildBudget) {
        return index.query(level, center, targets, want, maxChunkRadius, buildBudget);
    }

    /**
     * 登记一次大范围球形扫描，围绕发起时的位置分几刻走完，完成时通过回调给结果与覆盖账本。
     *
     * @param want 调用方实际需要的最近命中数量，也是停止规则的配额；配额越大扫描越远。
     * @return 供 {@link #cancel(int)} 使用的搜索编号；每次搜索单独编号，取消其中一个不影响另一个。
     */
    public int start(ClientLevel level, BlockPos center, int radius, int want,
                     Set<Block> targets, Consumer<BlockSearch.ScanResult> onDone) {
        int id = nextSearchId++;
        searches.add(new BlockSearch(budget, id, UUID.randomUUID(), level, center, radius, want, targets, onDone));
        return id;
    }

    /** 放弃指定扫描且不再触发回调；未知或已完成的编号不产生任何操作。 */
    public void cancel(int id) {
        searches.removeIf(search -> search.id == id);
    }

    /** 角色或世界退出时，放弃所有等待中的扫描并清空索引与记录。 */
    public void dropAll() {
        searches.clear();
        index.dropAll();
        recentWrites.clear();
    }

    /** 每刻推进：刷新预算、给每个等待中的扫描一段推进机会，再定期回收索引的过期条目。 */
    public void tick(ClientLevel level) {
        budget.refresh(level.getGameTime());
        Iterator<BlockSearch> iterator = searches.iterator();
        while (iterator.hasNext()) {
            BlockSearch search = iterator.next();
            if (search.tickOne(level)) {
                // 回调已执行完毕才移出；回调里新登记的扫描不受影响。
                iterator.remove();
            }
        }
        index.clientTick(level);
    }

    /** 客户端方块状态每次同步变化时调用；只记位置，不区分放置、破坏或状态改写。 */
    public void onBlockChange(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        index.onBlockChange(level, pos, oldState, newState);
        recentWrites.record(level, pos);
    }

    /** 最新的方块变化位置排在前面；是否真是目标、能否看见仍由读侧按现场状态裁决。 */
    public List<BlockPos> recentWrites(ClientLevel level) {
        return recentWrites.recent(level);
    }
}
