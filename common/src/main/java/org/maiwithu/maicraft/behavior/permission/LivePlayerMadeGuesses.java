// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 玩家放置推断的生产判断：服务端的归属记录里没有这一格时，按方块本身猜"是不是玩家放的"。
 *
 * <p>一放就是玩家手笔的方块——门、活板门、栅栏门这类要人手成对安放、拆下来还能捡走再用的结构件——
 * 野地里孤零零立着一扇，几乎只能是玩家或命令放的，按玩家的东西对待，宁可少动一格。
 * 村庄里生成的门也会被这条推断保护起来：开关、走门不受影响，只是不拆不改。
 * 归属记录查得到的格子轮不到推断说话：玩家放的、角色自己垫的，都以记录为准。
 */
public final class LivePlayerMadeGuesses implements GuessesPlayerMade {

    /** 现场读数：这一格此刻是什么方块；读不到给空，不猜。 */
    private final Function<WorldPosition, BlockState> states;

    public LivePlayerMadeGuesses(Function<WorldPosition, BlockState> states) {
        this.states = Objects.requireNonNull(states, "states");
    }

    @Override public boolean likelyPlayerMade(WorldPosition position, String blockType) {
        BlockState state = states.apply(position);
        // 现场这一格现在是什么，比调用方报的类型可信：按当刻的世界读，读不到就当不是玩家放的。
        return state != null && looksPlacedByPlayer(state);
    }

    /** 这一格像不像玩家安放的：门、活板门、栅栏门这类结构件，天然生成不会孤零零出现。 */
    static boolean looksPlacedByPlayer(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock;
    }
}
