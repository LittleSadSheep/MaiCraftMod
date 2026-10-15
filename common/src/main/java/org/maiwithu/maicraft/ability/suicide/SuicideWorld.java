// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 寻死看地形与敌对生物的只读接口：挑死法、选点火倒岩浆的格子都只问它。
 * 生产实现读当刻的客户端世界，测试给一张格子表。没加载的格子一律答"不知道"那一边（不能站、不是岩浆）。
 */
interface SuicideWorld {

    /** 这一格所在的区块加载着。 */
    boolean loaded(BlockPos cell);

    /** 这一格在世界底下：落到这里就是虚空。 */
    boolean belowWorld(BlockPos cell);

    /** 脚放在这一格能站住：脚下顶面结实，脚与头两格都过得去、没有流体（共用的能站判断）。 */
    boolean standable(BlockPos feet);

    /** 这一格没有碰撞，身体进得去，往下掉也不会被它接住。 */
    boolean passable(BlockPos cell);

    /** 落到这一格不摔伤：梯子、藤蔓这类能攀的，蜘蛛网、细雪、黏液块。 */
    boolean breaksFall(BlockPos cell);

    /** 这一格有流体（水、岩浆，含流动的）。 */
    boolean fluid(BlockPos cell);

    /** 这一格是岩浆（含流动的）。 */
    boolean lava(BlockPos cell);

    /** 这一格是仙人掌。 */
    boolean cactus(BlockPos cell);

    /** 这一格是空气。 */
    boolean air(BlockPos cell);

    /** 这一格方块的顶面结实，火与岩浆能落在它上面。 */
    boolean sturdyTop(BlockPos cell);

    /** 这一格此刻烧着火。 */
    boolean fireAt(BlockPos cell);

    /** 这一格的方块会被岩浆、火点着（木头、羊毛、树叶这类）。 */
    boolean flammable(BlockPos cell);

    /** 这一格不是空气、而且受保护（玩家的房子、记住的区域、额外保护的地标）。 */
    boolean protectedBlock(BlockPos cell);

    /** 这次任务的许可让不让在这一格放方块（火、岩浆）。 */
    boolean mayPlaceAt(BlockPos cell);

    /** 此刻在超热维度（下界）：岩浆流得更远。 */
    boolean hotDimension();

    /** 落在这一格方块上摔伤怎么算（干草块、蜂蜜块减伤，床减半落差）。 */
    FallDamage.Landing landingOn(BlockPos support);

    /** 离起点半径内此刻会主动打人的敌对生物；玩家与没惹毛的中立生物不在里面。 */
    List<HostileMob> hostileMobs(BlockPos origin, int radius);

    /** 这只生物此刻在哪；它死了、不见了、编号已经换成别的种类时为空。 */
    Optional<Vec3> mobPosition(int id, String type);

    /**
     * 一只敌对生物。
     *
     * @param id       实体编号
     * @param type     实体类型，例如 minecraft:zombie
     * @param position 此刻的位置
     */
    record HostileMob(int id, String type, Vec3 position) {}
}
