// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 就地摆工作站的生产实现：附近没有要用的设施时，把设施方块换到主手，对着身边
 * 一块能放的地面点一下放下去，再把新设施的位置与方块类型记进世界记忆。
 *
 * <p>放哪一格以当刻的现场为准：身边找"格子空着、下面是结实的地面"的格子，
 * 离角色最近的优先；一格都找不到、或游戏拒绝放置时，动作如实失败，
 * 来源换别的路，不挪用别人的方块也不隔空放。放下的位置记成"亲眼看到的工作站"，
 * 后面到那里动手、下次找设施都用这条记忆。
 */
public final class ClientWorkstationPlacer implements SetsUpWorkstation {

    /** 找放格的范围：身边两格以内，再远放下也不好走过去用。 */
    private static final int REACH_BLOCKS = 2;

    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> context;

    public ClientWorkstationPlacer(Interactions interactions, ClientMovesToMainhand toMainhand,
            WorldMemory memory, Supplier<PlayerContext> context) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<Action> placeNearby(String blockType) {
        // 方块类型在注册表里查不到：这个设施放不了，让来源如实换路。
        return blockOf(blockType).isEmpty()
                ? Optional.empty()
                : Optional.of(new PlaceAction(blockType));
    }

    // 放置的动作：找格 → 换到主手 → 对着支撑面点一下 → 确认后记进世界记忆，跨刻推进。
    private final class PlaceAction implements Action {

        private final String blockType;
        private Action moving;
        private AimAndInteract placing;
        private Problem failure;
        /** 挑好的放格：准备阶段定下，确认阶段读它记进世界记忆。 */
        private BlockPos spot;

        PlaceAction(String blockType) {
            this.blockType = blockType;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            if (failure != null) {
                return ActionStatus.failed(failure);
            }
            PlayerContext current = context.get();
            if (current == null || current.level() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "不在世界里，放不了" + displayName(), null));
            }
            if (placing == null) {
                return prepare(current, tick);
            }
            return confirmPlaced(current, tick);
        }

        // 先挑好放格、把设施方块换到主手，都齐了才对支撑面出手。
        private ActionStatus prepare(PlayerContext current, TickContext tick) {
            BlockPos spot = findSpot(current);
            if (spot == null) {
                failure = Problem.of(Problem.Kind.UNREACHABLE,
                        "身边没有能放" + displayName() + "的空位（要空格与结实的地面），不硬放", null);
                return ActionStatus.failed(failure);
            }
            if (moving == null) {
                Optional<Action> move = toMainhand.actionToMainhand(blockType);
                if (move.isEmpty()) {
                    failure = Problem.of(Problem.Kind.NEED_ITEM,
                            "身上没有" + displayName() + "，放不了", null);
                    return ActionStatus.failed(failure);
                }
                moving = move.get();
                return ActionStatus.running();
            }
            ActionStatus status = moving.tick(tick);
            if (status instanceof ActionStatus.Running) {
                return status;
            }
            if (status instanceof ActionStatus.Failed failed) {
                failure = failed.problem();
                return ActionStatus.failed(failure);
            }
            // 主手已是设施方块：对着支撑方块的顶面点一下，落格就是挑好的那格。
            BlockState expected = blockOf(blockType).orElseThrow().defaultBlockState();
            BlockState before = current.level().getBlockState(spot);
            placing = interactions.useBlock(spot.below(),
                    InteractionConfirmation.blockState(spot, before, expected));
            return ActionStatus.progressed();
        }

        // 放置结果与记忆一起收尾：真放下了才记，没放下不冒充记得一台新设施。
        private ActionStatus confirmPlaced(PlayerContext current, TickContext tick) {
            ActionStatus status = placing.tick(tick);
            if (!(status instanceof ActionStatus.Done)) {
                return status;
            }
            BlockPos spot = placingTarget();
            memory.workstationSeen(new WorldPosition(spot.getX(), spot.getY(), spot.getZ(), null),
                    blockType, Instant.now());
            return ActionStatus.done();
        }

        // 挑放格：身边一格空着（或可被替换）、下面是顶面结实的地面，离角色最近的优先。
        private BlockPos findSpot(PlayerContext current) {
            BlockPos base = current.localPlayer().blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -REACH_BLOCKS; dx <= REACH_BLOCKS; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -REACH_BLOCKS; dz <= REACH_BLOCKS; dz++) {
                        BlockPos cell = base.offset(dx, dy, dz);
                        if (!placeableAt(current, cell)) {
                            continue;
                        }
                        double distance = cell.distSqr(base);
                        if (distance < bestDistance) {
                            best = cell;
                            bestDistance = distance;
                        }
                    }
                }
            }
            return this.spot = best;
        }

        private boolean placeableAt(PlayerContext current, BlockPos cell) {
            BlockState state = current.level().getBlockState(cell);
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
            BlockPos below = cell.below();
            return current.level().getBlockState(below).isFaceSturdy(current.level(), below, Direction.UP);
        }

        // 确认阶段读放格：放在哪个格是挑格时定下的。
        private BlockPos placingTarget() {
            return spot;
        }

        private String displayName() {
            int nameStart = blockType.indexOf(':') + 1;
            return blockType.substring(nameStart);
        }

        @Override
        public String describe() {
            return "在身边放下" + displayName();
        }
    }

    private static Optional<Block> blockOf(String blockTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockTypeId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.BLOCK.get(id));
    }
}
