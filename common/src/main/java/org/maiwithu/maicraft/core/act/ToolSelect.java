package org.maiwithu.maicraft.core.act;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 只帮挖掘任务挑工具，不动背包。
 * 例如挖铁矿时，优先选能挖出材料的镐；不合等级但速度更快的工具排在后面。
 * “最佳”只按这里读取的物品挖掘速度比较，没有计算剩余耐久或玩家所有效果带来的实际速度。
 */
public final class ToolSelect {

    private ToolSelect() {}

    /**
     * 保留的旧名字。实际只返回建议槽位，不会把工具拿到手里。
     */
    public static int holdBestTool(LocalPlayer p, BlockState state) {
        return bestSlot(p, state);
    }

    /**
     * 从背包和快捷栏的前 36 格中挑工具，不检查盔甲或副手。
     * 背包里的工具选出来以后，调用方仍需把它搬到快捷栏并等游戏确认。
     */
    public static int bestSlot(LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        boolean tierGated = state.requiresCorrectToolForDrops();
        int harvest = -1, any = -1;
        // 只考虑物品自身挖掘速度大于一的工具。分别记住“能掉落材料的最快工具”和“只能挖坏的最快工具”。
        float harvestSpeed = 1.0f, anySpeed = 1.0f;
        int usableSlots = Math.min(36, inv.getContainerSize());
        for (int i = 0; i < usableSlots; i++) {
            ItemStack s = inv.getItem(i);
            float spd = s.getDestroySpeed(state);
            if (spd <= 1.0f) continue;
            if (!tierGated || s.isCorrectToolForDrops(state)) {
                if (spd > harvestSpeed) { harvestSpeed = spd; harvest = i; }
            } else if (spd > anySpeed) {
                anySpeed = spd;
                any = i;
            }
        }
        // 优先保住掉落物；没有合格工具时才返回仅能加速破坏的工具。都没有就返回 -1。
        // 因此调用方若是为了取材料，仍要另查能否掉落，不能把找到槽位等同于能收获。
        return harvest >= 0 ? harvest : any;
    }
}
