package org.maiwithu.maicraft.core.task.build;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * 旧摆设统计的保留实现，目前没有生产代码创建它。spawnAll 不生成实体，只把请求计为跳过。
 */
final class BuildFixtures {
    private final BuildTaskRecord record;
    private int skippedFixtures;
    private int skippedPayloads;

    BuildFixtures(LocalPlayer player, BuildTaskRecord record, BuildInventory inventory) {
        this.record = record;
    }
    int skippedFixtures() { return skippedFixtures; }
    int skippedPayloads() { return skippedPayloads; }

    // 把全部摆设计入跳过数量；带 Item 字段的再计一个跳过的随身物品。重复调用会继续累加。
    void spawnAll() {
        skippedFixtures += record.entities.size();
        for (BuildTaskRecord.EntitySpawn spawn : record.entities) {
            try {
                skippedPayloads += spawn.nbt().contains("Item") ? 1 : 0;
            } catch (RuntimeException ignored) { }
        }
    }

    boolean alreadyThere(BuildTaskRecord.EntitySpawn spawn) {
        return false; // 未实现“摆设已经在那里”的识别，所以始终返回没有。
    }

    void nudgeSurroundingWater(BlockPos siteMin, BlockPos siteMax) {
        // 此方法为空：客户端任务不主动安排服务端流体更新。
    }
}
