// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 接住一掉的一种做法：放东西接住（水、蛛网、黏液块、干草……），或放船坐进去。
 * 走路和生存反射只经这几个口子推进它：出发前备料、离地后每刻推进、空中往哪推、被打断时收手、收场后读经过。
 */
public interface CatchesFall {

    /** 改了哪一格：放下时 before 是原来的样子，收回时 after 是收完的样子。 */
    record Change(BlockPos position, BlockState before, BlockState after) {}

    /** 站在出发点每刻调一次：备好了（东西在手上、落点还接得住）返回 true，可以离地。 */
    boolean prepare(PlayerContext context);

    /** 离地以后（意外掉下去时从第一刻起）每刻调一次。 */
    void tick(PlayerContext context);

    boolean complete();

    /** 没接住：放不成、落到别处、或说是免伤却掉了血。 */
    boolean failed();

    /** 进行到哪一步、为什么没成，说给 LLM 听的一句话。 */
    String detail();

    /** 用什么接（物品 ID）。 */
    String itemId();

    /** 计划落在哪一格。 */
    BlockPos landingFeet();

    /** 离过地、现在又停住了（落地、落进水里、坐进船里）：之后的站稳、下船、收回占着身体。 */
    boolean touchedDown(PlayerContext context);

    /** 这一刻的按键：空中往落点推，落地后站定。 */
    PlayerInput.Movement steering(PlayerContext context, float yaw);

    /** 被打断（任务结束、换世界）：没确认的交互交还等待、打开的背包关上。 */
    void stop(PlayerContext context, String reason);

    /** 取走到现在为止改了的格子。 */
    List<Change> drainChanges();
}
