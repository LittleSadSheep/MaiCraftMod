// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 就地放自带床的生产实现：把床换到主手，对着身边挑好的两格地面放下，再读实际床头。
 *
 * <p>床是两格方块：挑放格时找"空着、下面结实"的一格，再要它沿角色朝向的邻格也放得下；
 * 对着支撑面点一下，床朝向哪边由点到面与角色朝向结算，放完从现场读哪格是床头，
 * 不按自己的打算冒认。放不了（身边没有相邻的两格空位、游戏拒绝）时动作如实失败。
 */
public final class ClientBedPlacer implements PlacesBed {

    /** 找放格的范围：身边两格以内，放太远走过去也就几步，但确认与睡都麻烦。 */
    private static final int REACH_BLOCKS = 2;

    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;
    private final Supplier<PlayerContext> context;

    public ClientBedPlacer(Interactions interactions, ClientMovesToMainhand toMainhand,
            Supplier<PlayerContext> context) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<BedPlacement> placeCarriedBed() {
        return Optional.of(new PlaceAction());
    }

    // 放床的动作：挑两格 → 换到主手 → 对支撑面点一下 → 从现场读床头，跨刻推进。
    private final class PlaceAction implements BedPlacement {

        private Action moving;
        private AimAndInteract placing;
        private Problem failure;
        /** 挑好的放格（床尾那格打算落在这里）；点它下面的支撑方块。 */
        private BlockPos spot;
        /** 点下去之前支撑格四周已经是床的格子：确认时只认新落下的。 */
        private Set<BlockPos> alreadyThere = Set.of();
        /** 实际放下的床的床头；还没读到为 null。 */
        private BlockPos placedHead;

        @Override
        public ActionStatus tick(TickContext tick) {
            if (failure != null) {
                return ActionStatus.failed(failure);
            }
            PlayerContext current = context.get();
            if (current == null || current.level() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "不在世界里，放不了床", null));
            }
            if (placing == null) {
                return prepare(current, tick);
            }
            return confirmPlaced(current, tick);
        }

        // 先挑好两格、把床换到主手，都齐了才对支撑面出手。
        private ActionStatus prepare(PlayerContext current, TickContext tick) {
            String bedItemId = currentBedItemId(current);
            if (bedItemId == null) {
                failure = Problem.of(Problem.Kind.NEED_ITEM, "身上没有床，放不了", null);
                return ActionStatus.failed(failure);
            }
            BlockPos chosen = findSpot(current);
            if (chosen == null) {
                failure = Problem.of(Problem.Kind.UNREACHABLE,
                        "身边没有能放床的两格空位（要空格与结实的地面），不硬放", null);
                return ActionStatus.failed(failure);
            }
            if (moving == null) {
                Optional<Action> move = toMainhand.actionToMainhand(bedItemId);
                if (move.isEmpty()) {
                    failure = Problem.of(Problem.Kind.NEED_ITEM, "把床换到主手的搬运还没接上，放不了", null);
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
            // 主手已是床：对挑好那格下面的支撑方块点一下。床落向哪边由点到的面与角色朝向结算，
            // 确认只认"四周新出现了床"，床头是哪格放完再读。
            BlockPos support = spot.below();
            alreadyThere = bedsAround(current.level(), support);
            placing = interactions.useBlock(support, bedAppearedAround(support, alreadyThere));
            return ActionStatus.progressed();
        }

        // 放置收尾：四周新出现的床里找床头那格；没找到床就不冒充放好了。
        private ActionStatus confirmPlaced(PlayerContext current, TickContext tick) {
            ActionStatus status = placing.tick(tick);
            if (!(status instanceof ActionStatus.Done)) {
                return status;
            }
            Set<BlockPos> now = bedsAround(current.level(), spot.below());
            now.removeAll(alreadyThere);
            for (BlockPos cell : now) {
                BlockState state = current.level().getBlockState(cell);
                if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    placedHead = cell.immutable();
                    return ActionStatus.done();
                }
            }
            failure = Problem.of(Problem.Kind.STUCK, "点了支撑面但没找到放下的床，不知道床头在哪", null);
            return ActionStatus.failed(failure);
        }

        // 挑放格：这一格空着（或可被替换）、下面结实、沿角色朝向的邻格也放得下，离角色近的优先。
        private BlockPos findSpot(PlayerContext current) {
            BlockPos base = current.localPlayer().blockPosition();
            Direction facing = current.localPlayer().getDirection();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -REACH_BLOCKS; dx <= REACH_BLOCKS; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -REACH_BLOCKS; dz <= REACH_BLOCKS; dz++) {
                        BlockPos cell = base.offset(dx, dy, dz);
                        if (!placeableAt(current, cell) || !placeableAt(current, cell.relative(facing))) {
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

        // 一格能不能放床的一半：空着或可替换、放进去不卡到自己或生物的身子、下面是结实的地面。
        private boolean placeableAt(PlayerContext current, BlockPos cell) {
            ClientLevel level = current.level();
            BlockState state = level.getBlockState(cell);
            if (!state.isAir() && !state.canBeReplaced()) {
                return false;
            }
            BlockState placed = Blocks.WHITE_BED.defaultBlockState();
            if (!level.isUnobstructed(placed, cell, CollisionContext.empty())) {
                return false;
            }
            BlockPos below = cell.below();
            return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        }

        // 身上（按床的种类找）现在拿着的床的物品 ID；副手不算——放床要主手拿。
        private String currentBedItemId(PlayerContext current) {
            var inventory = current.localPlayer().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                String itemId = BuiltInRegistries.ITEM.getKey(inventory.getItem(slot).getItem()).toString();
                if (itemId.endsWith("_bed")) {
                    return itemId;
                }
            }
            return null;
        }

        @Override
        public BlockPos placedHead() {
            return placedHead;
        }

        @Override
        public void pause() {
            if (moving != null) moving.pause();
            if (placing != null) placing.pause();
        }

        @Override
        public void close() {
            if (moving != null) moving.close();
            if (placing != null) placing.close();
        }

        @Override
        public String describe() {
            return "在身边放下床";
        }
    }

    // 支撑格六个邻格里此刻是床的格子（床尾与床头都算）。
    private static Set<BlockPos> bedsAround(ClientLevel level, BlockPos support) {
        Set<BlockPos> cells = new HashSet<>();
        for (Direction face : Direction.values()) {
            BlockPos cell = support.relative(face);
            if (level.getBlockState(cell).hasProperty(BedBlock.PART)) {
                cells.add(cell.immutable());
            }
        }
        return cells;
    }

    // 放下的确认条件：支撑格四周原来没有床的一格出现了床，并等服务端确认这次放置。
    private static InteractionConfirmation bedAppearedAround(BlockPos support, Set<BlockPos> before) {
        BlockPos frozen = support.immutable();
        return new InteractionConfirmation() {
            @Override
            public Verdict observe(PlayerContext context) {
                for (Direction face : Direction.values()) {
                    BlockPos cell = frozen.relative(face);
                    if (!context.level().isLoaded(cell)) {
                        return Verdict.PENDING;
                    }
                    if (!before.contains(cell) && context.level().getBlockState(cell).hasProperty(BedBlock.PART)) {
                        return Verdict.APPLIED;
                    }
                }
                return Verdict.PENDING;
            }

            @Override
            public boolean requiresBlockAcknowledgement() {
                return true;
            }
        };
    }
}
