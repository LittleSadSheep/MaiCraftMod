// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.maiwithu.maicraft.behavior.navigation.baritone.ClearanceWhitelist;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.BlockDigger;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 站位补救的生产实现：候选站位全不过时，挑一个站得稳、够得着、只是视线被一两格挡住的位置，
 * 走过去把挡在视线上的那一两格（树叶、草、天然的土石）挖开，再让靠近重新核对。
 * 要挖的格受保护、超出这次任务的许可、比深板岩还硬，或要挖三格以上，就不救。
 * 原先这里没有接上：砍树时上面的原木被一片树叶挡住，所有站位都按"看不见"拒掉，那几格永远砍不下来。
 */
final class LiveStandOpener implements StandOpener {

    /** 一个站位最多挖开几格遮挡：再多就不是"差一点"了。 */
    private static final int MAX_OCCLUDERS = 2;
    /** 比这还硬的不挖（深板岩是 3）：黑曜石、矿石块这类不为开视线去挖。 */
    private static final float MAX_HARDNESS = 3.0f;
    /** 站到位后这么多刻都打不着要挖的那一格，就放弃这次补救。 */
    private static final int NO_SHOT_LIMIT_TICKS = 40;

    private final Supplier<PlayerContext> context;
    private final PermissionCheck permission;
    private final Permissions permissions;
    private final ReachRules reach;
    private final WalksToSpot walks;
    private final ProtectedCells guarded;

    LiveStandOpener(Supplier<PlayerContext> context, PermissionCheck permission, Permissions permissions,
                    ReachRules reach, WalksToSpot walks, ProtectedCells guarded) {
        this.context = Objects.requireNonNull(context, "context");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.reach = Objects.requireNonNull(reach, "reach");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.guarded = Objects.requireNonNull(guarded, "guarded");
    }

    @Override
    public Optional<Action> rescue(RejectedSpot blocked, ApproachTarget target) {
        if (!"看不见".equals(blocked.failedItem())) return Optional.empty();
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) return Optional.empty();
        Level level = current.level();
        BlockPos feet = blocked.feet();
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + reach.eyeHeight(), feet.getZ() + 0.5);
        List<BlockPos> best = null;
        for (Vec3 point : aimPoints(target)) {
            List<BlockPos> cells = occluders(level, current.localPlayer(), eye, point, target);
            if (cells == null || cells.isEmpty()) continue;
            if (best == null || cells.size() < best.size()) best = cells;
        }
        if (best == null || best.size() > MAX_OCCLUDERS) return Optional.empty();
        return Optional.of(new ClearSight(feet, best));
    }

    // 往哪几点看：方块试中心与六个面内侧，实体试包围盒中心。
    private static List<Vec3> aimPoints(ApproachTarget target) {
        List<Vec3> points = new ArrayList<>();
        AABB box = target.bounds();
        points.add(box.getCenter());
        if (target.kind() == InteractionKind.ENTITY) return points;
        for (Direction face : Direction.values()) {
            points.add(box.getCenter().add(face.getStepX() * (box.getXsize() / 2 - .01),
                    face.getStepY() * (box.getYsize() / 2 - .01), face.getStepZ() * (box.getZsize() / 2 - .01)));
        }
        return points;
    }

    // 沿视线逐格看：挡在眼睛与目标之间、选取外形被视线穿过的格子都是遮挡；有一格挖不得就整条视线作废（null）。
    private List<BlockPos> occluders(Level level, Entity viewer, Vec3 eye, Vec3 point, ApproachTarget target) {
        List<BlockPos> cells = new ArrayList<>();
        BlockPos eyeCell = BlockPos.containing(eye);
        Boolean usable = BlockGetter.traverseBlocks(eye, point, level, (world, cell) -> {
            if (cell.equals(eyeCell) || target.block() != null && cell.equals(target.block())) return null;
            if (!world.isLoaded(cell)) return Boolean.FALSE;
            BlockState state = world.getBlockState(cell);
            var shape = state.getShape(world, cell, CollisionContext.of(viewer));
            if (shape.isEmpty() || shape.clip(eye, point, cell) == null) return null;
            if (!breakable(world, cell, state)) return Boolean.FALSE;
            cells.add(cell.immutable());
            return cells.size() > MAX_OCCLUDERS ? Boolean.FALSE : null;
        }, world -> Boolean.TRUE);
        return Boolean.TRUE.equals(usable) ? cells : null;
    }

    // 挖得：不在不许踩的格里、硬度在石头一类以内、许可检查点放行（受保护的、玩家的、超出档位的都拒）。
    private boolean breakable(Level level, BlockPos cell, BlockState state) {
        if (guarded.contains(cell)) return false;
        // 只挖地形一类（与寻路挖路、施工清场同一份白名单：树叶、草、天然土石）：机器、箱子、溜槽这些搭出来的东西
        // 挡着视线也不挖。原先许可开到 natural 时自己搭的机器零件也算能挖，靠近大齿轮机械手时把旁边的机械手、
        // 溜槽、补料箱当遮挡挖掉了。
        if (state.hasBlockEntity() || !ClearanceWhitelist.allows(state)) return false;
        float hardness = state.getDestroySpeed(level, cell);
        if (hardness < 0 || hardness > MAX_HARDNESS) return false;
        var position = new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), level.dimension().location().toString());
        String type = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK, position, type).isEmpty();
    }

    /** 走到那个站位，从近到远把挡住视线的格子挖开。 */
    private final class ClearSight implements Action {
        private final BlockPos feet;
        private final List<BlockPos> cells;
        private int index;
        private boolean walkStarted;
        private boolean arrived;
        private int noShotTicks;
        private BlockDigger digger;
        private PlayerContext last;

        ClearSight(BlockPos feet, List<BlockPos> cells) {
            this.feet = feet;
            this.cells = cells;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            last = player;
            if (!arrived) {
                if (!walkStarted) {
                    walks.begin(feet);
                    walkStarted = true;
                }
                ActionStatus walking = walks.tick(tick);
                if (!(walking instanceof ActionStatus.Done)) return walking;
                arrived = true;
            }
            while (index < cells.size() && cleared(player, cells.get(index))) index++;
            if (index >= cells.size()) return ActionStatus.done();
            if (digger == null) {
                digger = new BlockDigger(player.localPlayer(), player.interactionSender(), player.menuActions(),
                        player.input());
            }
            return switch (digger.digTargetStep(player, cells.get(index))) {
                case BROKE_TARGET, BROKE_OCCLUDER -> {
                    noShotTicks = 0;
                    yield ActionStatus.progressed();
                }
                case NO_SHOT -> ++noShotTicks > NO_SHOT_LIMIT_TICKS
                        ? ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "站到 " + feet.toShortString() + " 后打不着要挖开的 " + cells.get(index).toShortString()))
                        : ActionStatus.running();
                case PROGRESSING -> ActionStatus.running();
            };
        }

        private boolean cleared(PlayerContext player, BlockPos cell) {
            return player.level().getBlockState(cell).getShape(player.level(), cell).isEmpty();
        }

        @Override
        public void pause() {
            walks.pause();
            if (digger != null && last != null) digger.cancel(last);
        }

        @Override
        public void close() {
            walks.stop();
            if (digger != null) digger.cancel(last);
        }

        @Override
        public Interruptibility interruptibility() {
            return Interruptibility.WORKING;
        }

        @Override
        public String describe() {
            return arrived && index < cells.size() ? "挖开挡住视线的 " + cells.get(index).toShortString()
                    : "走到 " + feet.toShortString() + " 去挖开挡住视线的一两格";
        }
    }
}
