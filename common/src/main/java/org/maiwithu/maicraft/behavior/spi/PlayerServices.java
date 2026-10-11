// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.spi;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * 联动模组能用的玩家行为：物品来源、机器类型、网络读取器、任务书操作都拿这一份。
 * 和自带的能力用的是同一套，走过去、点开、换到主手、缺东西去拿都像玩家一样做，不另造一套。
 * 这些都是进了世界才建得出来的，所以联动登记表存的是建法，进世界时带着这一份建。
 *
 * @param context      当刻的角色；不在世界里或没有控制权时为 null，每刻取一次，不留到下一刻
 * @param approach     走到够得着、看得见目标的地方；能动多少地形按这次任务的许可
 * @param interactions 右键方块（含方块上的部件）、实体，等游戏确认
 * @param toMainhand   把身上的某样东西换到主手、腾出空手：手持扳手、配置器、部件去点之前，空手取置物台上的东西之前用
 * @param gear         穿上、取下装备，含放到副手：砂纸打磨要主手拿砂纸、副手拿要磨的东西
 * @param sneak        按住潜行做一件事：扳手潜行右键整块拆下机器方块
 * @param needs        缺东西时去拿：配置器、要投的料，经拿到物品的引擎，许可原样传下去
 * @param protection   这个世界的保护判断：能不能拆改、能不能取用存放（自己和自家人的可以）
 * @param itemTags     物品带哪些标签
 * @param menuLayouts  认得出哪些界面：原版加上联动模组证明过的
 * @param blockScans   在已加载的区块里按方块找东西
 * @param serverReads  向服务端问只读操作：客户端读不到的网络汇总（ME 网络的控制器、频道、能量）经它问
 */
public record PlayerServices(Supplier<PlayerContext> context, BringsPlayerClose approach,
                             Interactions interactions, MovesToMainhand toMainhand, GearChanges gear,
                             HoldsSneak sneak, ItemNeeds needs, Protection protection, ReadsItemTags itemTags,
                             MenuLayouts menuLayouts, BlockScanService blockScans, ServerReads serverReads) {

    public PlayerServices {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(toMainhand, "toMainhand");
        Objects.requireNonNull(gear, "gear");
        Objects.requireNonNull(sneak, "sneak");
        Objects.requireNonNull(needs, "needs");
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(itemTags, "itemTags");
        Objects.requireNonNull(menuLayouts, "menuLayouts");
        Objects.requireNonNull(blockScans, "blockScans");
        Objects.requireNonNull(serverReads, "serverReads");
    }
}
