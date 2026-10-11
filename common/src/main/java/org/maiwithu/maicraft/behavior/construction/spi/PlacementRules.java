// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;

/**
 * 放置规则：模组方块"拿什么放、对着哪一格放、要等哪几格先放好、放下以后自己变成什么"和原版默认不一样时，由联动回答。
 * 例如 Create 的带漏斗没有自己的物品，是拿漏斗放到传送带上变成的，得等下面的带子先在；
 * 动力辊放在实心面上会被抬高一格，想让它落在某格就要对着它下面那一格放。
 *
 * <p>只回答事实，不放方块、不改世界。不是自己的方块就给空（或原样返回），施工引擎按原版默认做；
 * 几份规则都能回答同一格时，引擎取第一个非空的答案。
 */
public interface PlacementRules {

    /**
     * 想让 state 最终落在 at：拿什么物品，对着哪一格放（原版放置时以为在放的那一格，多数就是 at）。
     * 不是这份规则管的方块为空。
     *
     * @param world 这一刻的现场；算材料清单时没有现场，传一个什么都没加载的读口，这时只看物品
     */
    Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world);

    /** 这一格要等哪些格先放好，写相对这一格的偏移；蓝图里没写的格不等。不用等为空表。 */
    List<BlockPos> placeAfter(BlockState state);

    /**
     * 原版物品放下这一下以后自己再改的样子：Create 的竖直齿轮箱先按齿轮箱放下，再按旁边伸过来的轴定轴向。
     * 放置预测拿它和蓝图比；不改就原样返回 placed。
     *
     * @param item         拿来放的物品（同一种方块，竖直齿轮箱与齿轮箱物品放下以后不一样）
     * @param playerFacing 出手那一刻角色面朝的水平方向
     */
    default BlockState afterPlacing(Item item, BlockState placed, ClientLevel level, BlockPos at, Direction playerFacing) {
        return placed;
    }

    /**
     * 一种放法。
     *
     * @param item    拿这个物品放
     * @param placeAt 对着放的那一格：原版放置以为在放这一格，物品自己再把它挪到目标格（动力辊抬高一格）；多数就是目标格
     */
    record Placement(Item item, BlockPos placeAt) {
        public Placement {
            Objects.requireNonNull(item, "item");
            placeAt = Objects.requireNonNull(placeAt, "placeAt").immutable();
        }
    }
}
