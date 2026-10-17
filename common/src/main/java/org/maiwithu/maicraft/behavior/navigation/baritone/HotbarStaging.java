// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.Optional;

import baritone.api.IBaritone;
import baritone.behavior.PathingBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走路途中往快捷栏挪东西：寻路引擎垫方块、挖方块都只从快捷栏拿，主背包里的垫料、镐它够不着。
 * 路线这一两步要垫、要挖时，像玩家一样先打开背包把要用的换到快捷栏；挪的这几刻角色站定、引擎不推进。
 */
final class HotbarStaging {

    /** 这一趟走路给挪东西的几样：结束没有、挪不成怎么收场、站定的这几刻不算没进展。 */
    interface Run {
        boolean done();

        void fail(Problem problem, BlockPos feet);

        void standingStill(long clientTick);
    }

    private final BaritoneInternals owner;
    private final ClientMovesToMainhand hands;
    private final Run run;
    /** 正在把垫料从主背包挪到快捷栏；没在挪为 null。 */
    private Action scaffoldMove;
    /** 正在把挖路的工具从主背包挪到快捷栏；没在挪为 null。 */
    private Action toolMove;
    /** 这一趟挪工具没挪成过：不再反复开背包，引擎拿快捷栏里的照挖。 */
    private boolean toolStagingFailed;

    HotbarStaging(BaritoneInternals owner, ClientMovesToMainhand hands, Run run) {
        this.owner = owner;
        this.hands = hands;
        this.run = run;
    }

    /** 正在挪东西。 */
    boolean busy() {
        return scaffoldMove != null || toolMove != null;
    }

    /** 人接回角色：挪到一半的也放下，背包界面要是还开着人自己关；交回后要用时重新看一遍再挪。 */
    void drop() {
        scaffoldMove = null;
        toolMove = null;
    }

    // 挪垫料：没在挪时，站在地上、路线这一两步要垫方块、垫料只在主背包才开始挪；挪的这几刻引擎不推进，角色站定。
    // 挪不成（界面没确认）就如实走不通，写明是哪一步没确认；不在没挪成时让引擎去垫一块够不着的料。
    boolean scaffold(PlayerContext context, LocalPlayer player, PathingBehavior pathing, IBaritone engine) {
        if (scaffoldMove == null) {
            if (run.done() || !player.onGround()) return false;
            var swap = owner.scaffoldStaging(player);
            if (swap.isEmpty() || !nextStepsPlace(engine, pathing)) return false;
            scaffoldMove = hands.actionToHotbar(swap.get().sourceSlot(), swap.get().hotbarSlot(),
                    "把垫脚的 " + swap.get().itemId() + " 挪到快捷栏").orElse(null);
            if (scaffoldMove == null) return false;
            engine.getInputOverrideHandler().clearAllKeys();
        }
        context.input().applyNavigationMovement(PlayerInput.Movement.STOPPED, context.clientTick());
        ActionStatus status = scaffoldMove.tick(TickContext.of(context.level().getGameTime(), context));
        if (status instanceof ActionStatus.Running) return true;
        scaffoldMove = null;
        // 开背包挪料这几刻站着不动是正常的，不算进"长时间没有进展"。
        run.standingStill(context.clientTick());
        if (status instanceof ActionStatus.Failed failed) {
            run.fail(Problem.of(Problem.Kind.STUCK, "要垫方块而垫料在主背包里，挪到快捷栏时"
                    + failed.problem().message(), null), player.blockPosition());
        }
        return false;
    }

    // 挪挖路的工具：路线这一两步要挖方块、主背包里有比快捷栏里更顺手的（镐、原子拆解机），先换到快捷栏再挖；
    // 引擎只从快捷栏挑工具，不挪就拿手边的扳手、火把硬挖（实机 10-13 用户看到拿扳手挖石头）。
    // 挪的这几刻站定；挪不成不拦路，引擎照旧拿快捷栏里的挖，这一趟也不再反复开背包。
    boolean tool(PlayerContext context, LocalPlayer player, PathingBehavior pathing, IBaritone engine) {
        if (toolMove == null) {
            if (toolStagingFailed || run.done() || !player.onGround()) return false;
            Optional<BlockState> breaking = nextStepsBreak(engine, pathing);
            if (breaking.isEmpty()) return false;
            var swap = owner.toolStaging(player, breaking.get());
            if (swap.isEmpty()) return false;
            toolMove = hands.actionToHotbar(swap.get().sourceSlot(), swap.get().hotbarSlot(),
                    "把挖路的 " + swap.get().itemId() + " 挪到快捷栏").orElse(null);
            if (toolMove == null) {
                toolStagingFailed = true;
                return false;
            }
            engine.getInputOverrideHandler().clearAllKeys();
        }
        context.input().applyNavigationMovement(PlayerInput.Movement.STOPPED, context.clientTick());
        ActionStatus status = toolMove.tick(TickContext.of(context.level().getGameTime(), context));
        if (status instanceof ActionStatus.Running) return true;
        toolMove = null;
        if (status instanceof ActionStatus.Failed) toolStagingFailed = true;
        run.standingStill(context.clientTick());
        return false;
    }

    // 路线当前这一步和下一步要挖的第一格是什么方块；不用挖为空。
    private static Optional<BlockState> nextStepsBreak(IBaritone engine, PathingBehavior pathing) {
        PathExecutor executor = pathing.getCurrent();
        if (executor == null) return Optional.empty();
        var movements = executor.getPath().movements();
        var blocks = new BlockStateInterface(engine.getPlayerContext());
        int from = Math.max(0, executor.getPosition());
        for (int index = from; index < Math.min(movements.size(), from + 2); index++) {
            if (movements.get(index) instanceof Movement movement) {
                movement.resetBlockCache();
                for (var pos : movement.toBreak(blocks)) {
                    BlockState state = blocks.get0(pos.getX(), pos.getY(), pos.getZ());
                    if (!state.isAir()) return Optional.of(state);
                }
            }
        }
        return Optional.empty();
    }

    // 路线当前这一步和下一步要不要垫方块：引擎走完一步会在同一刻接着走下一步，只看当前一步会晚一刻。
    private static boolean nextStepsPlace(IBaritone engine, PathingBehavior pathing) {
        PathExecutor executor = pathing.getCurrent();
        if (executor == null) return false;
        var movements = executor.getPath().movements();
        var blocks = new BlockStateInterface(engine.getPlayerContext());
        int from = Math.max(0, executor.getPosition());
        for (int index = from; index < Math.min(movements.size(), from + 2); index++) {
            if (movements.get(index) instanceof Movement movement) {
                movement.resetBlockCache();
                if (!movement.toPlace(blocks).isEmpty()) return true;
            }
        }
        return false;
    }
}
