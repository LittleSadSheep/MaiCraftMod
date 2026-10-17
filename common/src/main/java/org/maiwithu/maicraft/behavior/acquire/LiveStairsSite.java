// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.navigation.ScaffoldBlocks;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖楼梯读的现场：角色站在哪、脸朝哪、一格是什么样，都读当刻的客户端世界；
 * 走到下一级（往下、往上都一样）交给走到，只走不改地形（这一级已经挖通了，不该再挖别的、垫别的）。
 * 往下挖到下一级脚下是空洞时，在那一格垫一块身上的废料：换到主手，对着旁边露出来的实地那一面点一下。
 */
public final class LiveStairsSite implements ClientStairsDown.Site {

    private final WalkTo walks;
    /** 点方块、换手：只读现场时为空，这时不垫。 */
    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;

    /** 只读现场、只走不垫（往上挖楼梯用）。 */
    public LiveStairsSite(WalkTo walks) {
        this(walks, null, null);
    }

    /** 往下挖楼梯用：下一级脚下空着时能垫一块。 */
    public LiveStairsSite(WalkTo walks, Interactions interactions, ClientMovesToMainhand toMainhand) {
        this.walks = Objects.requireNonNull(walks, "walks");
        this.interactions = interactions;
        this.toMainhand = toMainhand;
    }

    @Override
    public Optional<BlockPos> feet(TickContext context) {
        LocalPlayer self = self(context);
        return self == null ? Optional.empty() : Optional.of(self.blockPosition());
    }

    @Override
    public boolean onGround(TickContext context) {
        LocalPlayer self = self(context);
        return self != null && self.onGround();
    }

    @Override
    public Direction facing(TickContext context) {
        LocalPlayer self = self(context);
        return self == null ? Direction.NORTH : self.getDirection();
    }

    @Override
    public ClientStairsDown.Ground ground(TickContext context, BlockPos cell) {
        ClientLevel level = level(context);
        // 没加载、看不到的格子不当成空的：按挖不动处理，楼梯停在这里。
        // 客户端世界的 hasChunkAt 永远答“加载着”，没加载的格读出来是空气、地表落在世界最底层；按区块真加载没加载判。
        if (level == null || !level.isLoaded(cell)) return ClientStairsDown.Ground.UNBREAKABLE;
        BlockState state = level.getBlockState(cell);
        if (!state.getFluidState().isEmpty()) return ClientStairsDown.Ground.FLUID;
        if (state.getCollisionShape(level, cell).isEmpty()) return ClientStairsDown.Ground.OPEN;
        if (state.getDestroySpeed(level, cell) < 0) return ClientStairsDown.Ground.UNBREAKABLE;
        // 工作台、箱子、熔炉、床这类设施不为挖楼梯拆掉：刚做完东西脸正对着工作台，第一级楼梯会把它挖走。
        if (state.hasBlockEntity() || state.getMenuProvider(level, cell) != null
                || state.getBlock() instanceof BedBlock) {
            return ClientStairsDown.Ground.FACILITY;
        }
        // 搭出来的（平台、地板、机器垫着的那层）不为挖楼梯拆：站在主城平台上要石头，不从平台往下挖穿。
        if (!NaturalBlocks.grown(state, cell, level, level::isLoaded, new HashMap<>())) {
            return ClientStairsDown.Ground.BUILT;
        }
        return ClientStairsDown.Ground.DIGGABLE;
    }

    @Override
    public String blockType(TickContext context, BlockPos cell) {
        ClientLevel level = level(context);
        return level == null ? "minecraft:air"
                : BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock()).toString();
    }

    @Override
    public String dimension(TickContext context) {
        ClientLevel level = level(context);
        return level == null ? null : level.dimension().location().toString();
    }

    // 能拿去垫的废料：和走路垫脚同一份先后（泥土、废石先，圆石最后），沙砾这类会往下掉的不要——垫在空洞上面会掉下去。
    @Override
    public List<String> fillers(TickContext context) {
        LocalPlayer self = self(context);
        if (self == null || interactions == null) return List.of();
        Map<String, Integer> carried = new HashMap<>();
        for (ItemStack stack : self.getInventory().items) {
            if (!stack.isEmpty()) {
                carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        List<String> fillers = new ArrayList<>();
        for (String itemId : ScaffoldBlocks.usable(id -> carried.getOrDefault(id, 0), ItemsToKeep.NOTHING)) {
            if (!(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(itemId)) instanceof FallingBlock)) fillers.add(itemId);
        }
        return List.copyOf(fillers);
    }

    // 对着哪一面垫：空洞那一格的前、左、右、后四个邻格里顶得住的实地；正下方也是空的不算，
    // 后面那格压在脚下那级底下、多半看不见，排最后。一个邻格都没有就垫不了。
    @Override
    public Optional<Action> placeFiller(TickContext context, BlockPos cell, String filler) {
        ClientLevel level = level(context);
        LocalPlayer self = self(context);
        if (level == null || self == null || interactions == null || toMainhand == null) return Optional.empty();
        Direction ahead = Direction.getNearest(cell.getX() + 0.5 - self.getX(), 0, cell.getZ() + 0.5 - self.getZ());
        List<BlockPos> supports = new ArrayList<>();
        for (Direction side : List.of(ahead, ahead.getClockWise(), ahead.getCounterClockWise(), ahead.getOpposite())) {
            BlockPos support = cell.relative(side);
            if (level.isLoaded(support) && level.getBlockState(support).isFaceSturdy(level, support, side.getOpposite())) {
                supports.add(support);
            }
        }
        return supports.isEmpty() ? Optional.empty() : Optional.of(new FillerPlacement(cell, filler, supports));
    }

    @Override
    public Action stepTo(BlockPos feet) {
        return walks.start(GoalCompiler.standOn(feet), TerrainPermit.WALK_ONLY);
    }

    /** 垫一块：废料换到主手，挨个对着能用的邻格那一面点，空洞那一格有了实心方块才算；都点不成如实失败。 */
    private final class FillerPlacement implements Action {

        private final BlockPos cell;
        private final String filler;
        private final List<BlockPos> supports;
        private Action moving;
        private AimAndInteract clicking;
        private int tried;
        private Problem lastProblem;

        FillerPlacement(BlockPos cell, String filler, List<BlockPos> supports) {
            this.cell = cell.immutable();
            this.filler = filler;
            this.supports = supports;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            if (player == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "不在世界里，垫不了"));
            }
            // 先换手：废料已经在主手就不换。
            String held = BuiltInRegistries.ITEM.getKey(player.localPlayer().getMainHandItem().getItem()).toString();
            if (!filler.equals(held) && moving == null && clicking == null) {
                Optional<Action> move = toMainhand.actionToMainhand(filler);
                if (move.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + filler + "，垫不了"));
                }
                moving = move.get();
            }
            if (moving != null) {
                ActionStatus status = moving.tick(context);
                if (!(status instanceof ActionStatus.Done)) return status;
                moving.close();
                moving = null;
            }
            if (clicking == null) {
                if (tried >= supports.size()) {
                    return ActionStatus.failed(lastProblem != null ? lastProblem
                            : Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "对着旁边的实地都没垫成"));
                }
                BlockPos support = supports.get(tried);
                Direction face = Direction.getNearest(cell.getX() - support.getX(), cell.getY() - support.getY(),
                        cell.getZ() - support.getZ());
                clicking = interactions.useBlockFace(support, face, filled(cell));
                return ActionStatus.progressed();
            }
            ActionStatus status = clicking.tick(context);
            if (status instanceof ActionStatus.Failed failed) {
                // 这一面点不到、点了没放上：换下一个邻格再点。
                clicking.close();
                clicking = null;
                tried++;
                lastProblem = failed.problem();
                return ActionStatus.progressed();
            }
            return status;
        }

        @Override
        public void pause() {
            if (moving != null) moving.pause();
            if (clicking != null) clicking.pause();
        }

        @Override
        public void close() {
            if (moving != null) moving.close();
            if (clicking != null) clicking.close();
        }

        @Override
        public String describe() {
            return "在 " + cell.toShortString() + " 垫一块 " + filler;
        }
    }

    // 垫成了：那一格有了实心方块，并等服务端确认这次放置。
    private static InteractionConfirmation filled(BlockPos cell) {
        return new InteractionConfirmation() {
            @Override
            public Verdict observe(PlayerContext context) {
                if (!context.level().isLoaded(cell)) return Verdict.PENDING;
                return context.level().getBlockState(cell).getCollisionShape(context.level(), cell).isEmpty()
                        ? Verdict.PENDING : Verdict.APPLIED;
            }

            @Override
            public boolean requiresBlockAcknowledgement() {
                return true;
            }
        };
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }

    private static ClientLevel level(TickContext context) {
        return context.player() == null ? null : context.player().level();
    }
}
