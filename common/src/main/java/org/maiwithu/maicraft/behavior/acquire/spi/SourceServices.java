// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * 联动模组的物品来源能用的玩家行为：和自带来源用的是同一套，走过去、点开、搬东西都像玩家一样做，
 * 不另造一套。这些都是进了世界才建得出来的，所以登记表存的是来源的建法，进世界时带着这一份建来源。
 *
 * @param context      当刻的角色；不在世界里或没有控制权时为 null，每刻取一次，不留到下一刻
 * @param approach     走到够得着、看得见目标的地方；能动多少地形按这次任务的许可
 * @param interactions 右键方块（含方块上的部件）、实体，等游戏确认
 * @param protection   这个世界的保护判断：能不能拆改、能不能取用存放（自己和自家人的可以）
 * @param itemTags     物品带哪些标签
 * @param menuLayouts  认得出哪些界面：原版加上联动模组证明过的
 * @param blockScans   在已加载的区块里按方块找东西
 */
public record SourceServices(Supplier<PlayerContext> context, BringsPlayerClose approach,
                             Interactions interactions, Protection protection, ReadsItemTags itemTags,
                             MenuLayouts menuLayouts, BlockScanService blockScans) {

    public SourceServices {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(itemTags, "itemTags");
        Objects.requireNonNull(menuLayouts, "menuLayouts");
        Objects.requireNonNull(blockScans, "blockScans");
    }
}
