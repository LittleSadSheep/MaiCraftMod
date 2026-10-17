// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 就地摆工作站的生产实现：附近没有要用的设施时，把设施方块换到主手，对着身边
 * 一块能放的地面点一下放下去，再把新设施的位置与方块类型记进世界记忆。
 *
 * <p>放哪一格以当刻的现场为准：身边找"格子空着、没有人或生物挡着、下面是结实的地面、头顶也空着"的格子——
 * 不塞在树叶、原木、天花板底下（放进去用起来别扭，观众看着也怪）；和角色同一层、在眼前的优先，脚下、身后的往后排。
 * 一格放不下（游戏拒绝）就换下一格，最多试三格；一格都找不到或都不行时动作如实失败，
 * 来源换别的路，不挪用别人的方块也不隔空放。对着支撑方块点一下，方块落在哪一格由点到的面决定，
 * 所以按实际落下的那一格记成"亲眼看到的工作站"，后面到那里动手、下次找设施都用这条记忆。
 *
 * <p>收回用的是把一格方块收进包的那套动作：走到跟前、挖下来、捡起这一下掉出来的，和收回野外自己那张床一样。
 */
public final class ClientWorkstationPlacer implements SetsUpWorkstation, ReadsBlockTypes {

    /** 找放格的范围：身边两格以内，再远放下也不好走过去用。 */
    private static final int REACH_BLOCKS = 2;
    /** 一格放不下最多换几格再试。 */
    private static final int SPOT_TRIES = 3;

    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> context;
    /** 收回台子：走到跟前、挖下来、捡起掉落物。 */
    private final CollectsBlocks collects;

    public ClientWorkstationPlacer(Interactions interactions, ClientMovesToMainhand toMainhand,
            WorldMemory memory, Supplier<PlayerContext> context, CollectsBlocks collects) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.context = Objects.requireNonNull(context, "context");
        this.collects = Objects.requireNonNull(collects, "collects");
    }

    @Override
    public boolean gone(WorldPosition at, String blockType) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return false;
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        // 只认同一维度、已加载的那一格：读不到的不下结论。
        if (at.dimension() != null && !at.dimension().equals(current.level().dimension().location().toString())) return false;
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        if (!current.level().isLoaded(pos)) return false;
        return !blockType.equals(BuiltInRegistries.BLOCK.getKey(current.level().getBlockState(pos).getBlock()).toString());
    }

    @Override
    public Optional<String> blockTypeAt(WorldPosition at) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return Optional.empty();
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        // 只读同一维度、真加载着的那一格：读不到的不下结论，空气就是"那里没东西了"。
        if (at.dimension() != null && !at.dimension().equals(current.level().dimension().location().toString())) {
            return Optional.empty();
        }
        if (!current.level().isLoaded(pos)) return Optional.empty();
        BlockState state = current.level().getBlockState(pos);
        if (state.isAir()) return Optional.empty();
        return Optional.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    @Override
    public Optional<Action> placeNearby(String blockType, Consumer<WorldPosition> placedAt) {
        // 方块类型在注册表里查不到：这个设施放不了，让来源如实换路。
        return blockOf(blockType).isEmpty()
                ? Optional.empty()
                : Optional.of(new PlaceAction(blockType, Objects.requireNonNull(placedAt, "placedAt")));
    }

    // 做完合成收回刚摆的台子：走到跟前挖下来、捡起这一下掉出来的；挖不下来、捡不到由收进包的动作如实失败。
    @Override
    public Optional<Action> takeBack(WorldPosition at, Permissions permissions) {
        return collects.collect(new BlockPos(at.x(), at.y(), at.z()), permissions);
    }

    // 放置的动作：找格 → 换到主手 → 对着支撑面点一下 → 确认后记进世界记忆，跨刻推进。
    private final class PlaceAction implements Action {

        private final String blockType;
        /** 真放下了才告诉来源落在哪一格：做完合成按这一格收回。 */
        private final Consumer<WorldPosition> placedAt;
        private Action moving;
        private AimAndInteract placing;
        private Problem failure;
        /** 挑好的放格：准备阶段定下；点的是它下面那格支撑方块。 */
        private BlockPos spot;
        /** 点下去之前支撑格四周已经是这种设施的格子：确认时只认新落下的那一格。 */
        private Set<BlockPos> alreadyThere = Set.of();
        /** 试过、放不下的格子：换格时跳过。 */
        private final Set<BlockPos> tried = new HashSet<>();

        PlaceAction(String blockType, Consumer<WorldPosition> placedAt) {
            this.blockType = blockType;
            this.placedAt = placedAt;
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
            // 刚做好的设施常直接落在选中的那一格、已经拿在主手：不用换（换手对已在主手的给空）。
            // 实机 10-14 现做的熔炉就在主手上，被当成"身上没有熔炉，放不了"，烤肉白做了一台熔炉。
            boolean inHand = blockType.equals(BuiltInRegistries.ITEM.getKey(
                    current.localPlayer().getMainHandItem().getItem()).toString());
            if (moving == null && !inHand) {
                Optional<Action> move = toMainhand.actionToMainhand(blockType);
                if (move.isEmpty()) {
                    failure = Problem.of(Problem.Kind.NEED_ITEM,
                            "身上没有" + displayName() + "，放不了", null);
                    return ActionStatus.failed(failure);
                }
                moving = move.get();
                return ActionStatus.running();
            }
            ActionStatus status = moving == null ? ActionStatus.done() : moving.tick(tick);
            if (status instanceof ActionStatus.Running) {
                return status;
            }
            if (status instanceof ActionStatus.Failed failed) {
                failure = failed.problem();
                return ActionStatus.failed(failure);
            }
            // 主手已是设施方块：对着挑好那格下面的支撑方块点一下。熔炉、石切台放下时带朝向，
            // 只认方块种类；落在支撑格哪一面旁边由点到的面决定，六面都认。
            Block block = blockOf(blockType).orElseThrow();
            BlockPos support = spot.below();
            alreadyThere = holding(current, support, block);
            // 放格里长着草、花这类一碰就换掉的：像人一样直接点那丛草，东西就顶替它放下。点下面的地面会被草挡住，
            // 实机 10-14 河边草地上放工作台，回回"看不到方块的任何一面"，鱼竿一直做不出来。
            BlockPos clickAt = current.level().getBlockState(spot).isAir() ? support : spot;
            placing = interactions.useBlock(clickAt, placedAround(support, block, alreadyThere));
            return ActionStatus.progressed();
        }

        // 放置结果与记忆一起收尾：真放下了才记，按实际落下的那一格记；没放下不冒充记得一台新设施。
        // 这一格放不下（游戏拒绝、点不到）就换下一格再试，试满三格才如实失败。
        private ActionStatus confirmPlaced(PlayerContext current, TickContext tick) {
            ActionStatus status = placing.tick(tick);
            if (status instanceof ActionStatus.Failed failed) {
                placing.close();
                placing = null;
                tried.add(spot);
                if (tried.size() < SPOT_TRIES) return ActionStatus.progressed();
                failure = failed.problem();
                return ActionStatus.failed(failure);
            }
            if (!(status instanceof ActionStatus.Done)) {
                return status;
            }
            Block block = blockOf(blockType).orElseThrow();
            Set<BlockPos> now = holding(current, spot.below(), block);
            now.removeAll(alreadyThere);
            BlockPos landed = now.stream().findFirst().orElse(spot);
            // 记进世界记忆的那一格同时告诉来源：做完合成按同一格收回，不靠回头去记忆里猜哪台是自己刚摆的。
            WorldPosition at = new WorldPosition(landed.getX(), landed.getY(), landed.getZ(), null);
            memory.workstationSeen(at, blockType, Instant.now());
            placedAt.accept(at);
            return ActionStatus.done();
        }

        // 挑放格：身边一格空着（或可被替换）、没有人或生物挡着、下面是顶面结实的地面、头顶也空着；
        // 分数越小越好：离得近，和角色同一层，在眼前（不在身后）。试过放不下的跳过。
        private BlockPos findSpot(PlayerContext current) {
            var self = current.localPlayer();
            BlockPos base = self.blockPosition();
            var look = self.getViewVector(1.0f);
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (int dx = -REACH_BLOCKS; dx <= REACH_BLOCKS; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -REACH_BLOCKS; dz <= REACH_BLOCKS; dz++) {
                        BlockPos cell = base.offset(dx, dy, dz);
                        if (tried.contains(cell) || !placeableAt(current, cell)) {
                            continue;
                        }
                        double score = spotScore(dx, dy, dz, look.x, look.z);
                        if (score < bestScore) {
                            best = cell;
                            bestScore = score;
                        }
                    }
                }
            }
            return this.spot = best;
        }

        private boolean placeableAt(PlayerContext current, BlockPos cell) {
            BlockState state = current.level().getBlockState(cell);
            // 空着，或长着草、花这类可被顶替的；水、岩浆里不放（点不到，放下去也泡着）。
            if (!state.isAir() && !state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                return false;
            }
            // 角色自己站的两格、别的生物占着的格子，原版不让把方块放进去。
            BlockState placed = blockOf(blockType).orElseThrow().defaultBlockState();
            if (!current.level().isUnobstructed(placed, cell, CollisionContext.empty())) {
                return false;
            }
            BlockPos below = cell.below();
            if (!current.level().getBlockState(below).isFaceSturdy(current.level(), below, Direction.UP)) return false;
            // 头顶那一格要空着：树叶、原木、天花板底下不放，压在底下用起来别扭。
            BlockPos above = cell.above();
            BlockState over = current.level().getBlockState(above);
            return over.isAir() || over.canBeReplaced() && over.getCollisionShape(current.level(), above).isEmpty();
        }

        // 被生存需求打断：换手、放置先停住，恢复后接着推进。
        @Override
        public void pause() {
            if (moving != null) moving.pause();
            if (placing != null) placing.pause();
        }

        // 收尾：没做完的换手与放置一并收尾。
        @Override
        public void close() {
            if (moving != null) moving.close();
            if (placing != null) placing.close();
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

    /**
     * 放格的分数（越小越好）：水平距离，加上不同层（脚下一层、头那一层）的罚分，再加上在身后的罚分——
     * 放在眼前、同一层，角色一转身就能用，观众也看得见它被放下。
     */
    static double spotScore(int dx, int dy, int dz, double lookX, double lookZ) {
        double flat = Math.hypot(dx, dz);
        double facing = flat < 1.0E-6 ? 0 : (dx * lookX + dz * lookZ) / flat;
        return flat + (dy == 0 ? 0 : 1.5) + (facing < 0 ? 1.0 : 0) - facing * 0.25;
    }

    // 支撑格六个邻格里此刻是这种方块的格子。
    private static Set<BlockPos> holding(PlayerContext current, BlockPos support, Block block) {
        Set<BlockPos> cells = new HashSet<>();
        for (Direction face : Direction.values()) {
            BlockPos cell = support.relative(face);
            if (current.level().getBlockState(cell).is(block)) {
                cells.add(cell.immutable());
            }
        }
        return cells;
    }

    // 放下的确认条件：支撑格四周原来不是这种方块的一格变成了它，并等服务端确认这次放置。
    private static InteractionConfirmation placedAround(BlockPos support, Block block, Set<BlockPos> before) {
        BlockPos frozen = support.immutable();
        return new InteractionConfirmation() {
            @Override
            public Verdict observe(PlayerContext context) {
                for (Direction face : Direction.values()) {
                    BlockPos cell = frozen.relative(face);
                    if (!context.level().isLoaded(cell)) return Verdict.PENDING;
                    if (!before.contains(cell) && context.level().getBlockState(cell).is(block)) {
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

    private static Optional<Block> blockOf(String blockTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockTypeId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.BLOCK.get(id));
    }
}
