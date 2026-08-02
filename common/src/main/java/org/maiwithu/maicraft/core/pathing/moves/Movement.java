package org.maiwithu.maicraft.core.pathing.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 移动原语抽象基类:一条"从 src 到 dest"的最小可执行动作,
 * 自带成本计算(规划期)与逐 tick 状态机(执行期)。
 *
 * <p>执行侧通用框架在 {@link #update()}:先由子类推进状态机,再叠加
 * 水中上浮强跳与卡墙自救,最后把本 tick 的按键表交给执行层钩子。
 * 准备阶段({@link #prepared}):等待落沙实体落定、把仍挡路的
 * toBreak 逐个交给 {@link #beginBreaking} 钩子挖掉。
 */
public abstract class Movement {

    /**
     * Null while a worker assembles the path; bound exactly once when the path reaches the client
     * thread for execution.
     */
    protected LocalPlayer player;

    protected final BlockPos src;
    protected final BlockPos dest;

    /** 本动作开跑前需要挖穿的格。 */
    protected final BlockPos[] positionsToBreak;

    /** 本动作开跑前需要放上方块的格(无则 null)。 */
    protected final BlockPos positionToPlace;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    /** 缓存成本;override 机制允许外部钉入更严的估价。 */
    private Double cost;

    private Set<BlockPos> validPositionsCached;

    /** 仍需挖穿的格(懒算缓存,{@link #resetBlockCache()} 后按当前世界重算)。 */
    protected List<BlockPos> toBreakCached;
    /** 仍需放上方块的格(懒算缓存)。 */
    protected List<BlockPos> toPlaceCached;
    /** 会用身体挤进去的格(懒算缓存;仅对角移动产出非空)。 */
    protected List<BlockPos> toWalkIntoCached;

    /** 成本是否是在 dest 所在 chunk 已加载时算出的(执行期涨价豁免用)。 */
    private Boolean calculatedWhileLoaded;

    protected Movement(LocalPlayer player, BlockPos src, BlockPos dest,
                       BlockPos[] toBreak, BlockPos toPlace) {
        this.player = player;
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(LocalPlayer player, BlockPos src, BlockPos dest, BlockPos[] toBreak) {
        this(player, src, dest, toBreak, null);
    }

    public final void bindPlayer(LocalPlayer activePlayer) {
        Objects.requireNonNull(activePlayer, "activePlayer");
        if (player != null && player != activePlayer) {
            throw new IllegalStateException("movement is already bound to a different LocalPlayer");
        }
        player = activePlayer;
    }

    protected final LocalPlayer requirePlayer() {
        return Objects.requireNonNull(player,
                "movement execution attempted before binding the active LocalPlayer");
    }

    // ==================== 成本 ====================

    /** 已算出的成本;未算先抛(调用方须先走 getCost(context))。 */
    public double getCost() {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            MutableMoveResult result = new MutableMoveResult();
            cost = calculateCost(context, result);
        }
        return cost;
    }

    /** 重算成本(丢弃缓存与 override)。 */
    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    /** 外部钉入成本(取"算时成本与节点差的较严者"时用)。 */
    public void override(double cost) {
        this.cost = cost;
    }

    /**
     * 计算本动作成本;动态落点的动作把实际落点写进 result。
     * 不可行返回 {@link ActionCosts#COST_INF}。
     */
    public abstract double calculateCost(CalculationContext context, MutableMoveResult result);

    // ==================== 合法过程位 ====================

    /** 执行期允许身体出现的格集合(重定位锚)。 */
    protected abstract Set<BlockPos> calculateValidPositions();

    public Set<BlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    /**
     * 当前身位是否属于本动作的合法过程位集合。脚下不在集合里时,
     * 再按 {@link #pathStart(LocalPlayer)} 算一个假起点(脚下不可站时
     * 取 3×3 邻格/下一格的支撑点)——重算/回退后,整体路径起点不一定
     * 属于本移动自身的 {src,dest} 集合,假起点兜住这种情形。
     */
    /**
     * 脚位约定:实体坐标 y 加 0.1251(灵魂沙/农田顶面矮一截仍归上格),
     * 落在台阶格里再上抬一格。执行器重定位、validPositions 与本类状态
     * 机的到达/失败判定**全部**用这一把尺,避免半砖/灵魂沙顶面错位一格
     * 导致 SUCCESS 推进与回退扫循环。
     */
    public static BlockPos feet(LocalPlayer player) {
        BlockPos f = BlockPos.containing(
                player.position().x, player.position().y + 0.1251, player.position().z);
        BlockState at = player.level().getBlockState(f);
        // 楼梯与半砖同理:踩在矮的那半格上时,实体 y 只比格底高半格,加完偏移
        // 仍落在该格自身里,而寻路模型认定人站在它<b>上面</b>那一格。两把尺不
        // 一致,执行器就会认为"人不在本动作的合法位上",前后重定位都对不上,
        // 动作硬撑到超时——而这栋房子越往高层楼梯越密,正好卡在爬升的关口。
        if (at.getBlock() instanceof SlabBlock || at.getBlock() instanceof StairBlock) {
            return f.above();
        }
        return f;
    }

    protected boolean playerInValidPosition() {
        BlockPos feet = feet(player);
        if (getValidPositions().contains(feet)) {
            return true;
        }
        BlockPos fakeStart = pathStart(player);
        return getValidPositions().contains(fakeStart);
    }

    /**
     * 脚下不可站时的假起点:在地面 → 3×3 邻格按水平距离取最近四个,
     * 第一个下可站、本格与上格可穿的格;空中 → 再下一格可站则用脚下格。
     * 其余情况用脚位。与 PathingCore.pathStart 同一语义,提取为基类静态
     * 助手供 Movement 子类(如 Downward 的 UNREACHABLE 判定)复用。
     */
    public static BlockPos pathStart(LocalPlayer player) {
        BlockPos feet = feet(player);
        var level = player.level();
        if (MovementHelper.canWalkOn(level, feet.below())) {
            return feet;
        }
        if (player.onGround()) {
            double playerX = player.position().x;
            double playerZ = player.position().z;
            java.util.List<BlockPos> closest = new java.util.ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    closest.add(new BlockPos(feet.getX() + dx, feet.getY(), feet.getZ() + dz));
                }
            }
            closest.sort(java.util.Comparator.comparingDouble(pos ->
                    ((pos.getX() + 0.5) - playerX) * ((pos.getX() + 0.5) - playerX)
                    + ((pos.getZ() + 0.5) - playerZ) * ((pos.getZ() + 0.5) - playerZ)));
            for (int i = 0; i < 4; i++) {
                BlockPos possibleSupport = closest.get(i);
                double xDist = Math.abs((possibleSupport.getX() + 0.5) - playerX);
                double zDist = Math.abs((possibleSupport.getZ() + 0.5) - playerZ);
                if (xDist > 0.8 && zDist > 0.8) {
                    continue;
                }
                if (MovementHelper.canWalkOn(level, possibleSupport.below())
                        && MovementHelper.canWalkThrough(level, possibleSupport)
                        && MovementHelper.canWalkThrough(level, possibleSupport.above())) {
                    return possibleSupport;
                }
            }
        } else {
            if (MovementHelper.canWalkOn(level, feet.below().below())) {
                return feet.below();
            }
        }
        return feet;
    }

    // ==================== 执行状态机 ====================

    /**
     * 每 tick 推进一次。通用框架:强制关闭飞行能力(走地面物理)→
     * 子类状态机 → 水中且低于目标高度时强按跳(上浮)→ 卡墙时先换上
     * 对该方块最优工具再按左键 → 视角与按键交执行层钩子,按键先清
     * 后设、终态清空。
     */
    public MovementStatus update() {
        currentState = updateState(currentState);
        BlockPos feet = feet(player);
        if (MovementHelper.isLiquid(player.level().getBlockState(feet))
                && player.getY() < dest.getY() + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }
        if (player.isInWall()) {
            BlockState hitState = crosshairBlockState();
            ItemStack best = hitState == null ? ItemStack.EMPTY : bestToolFor(hitState);
            ItemSelection selection = best.isEmpty()
                    ? ItemSelection.READY
                    : selectItem(stack -> ItemStack.isSameItemSameComponents(stack, best));
            if (selection == ItemSelection.READY) {
                currentState.setInput(Input.CLICK_LEFT, true);
            }
        }

        if (currentState.getTarget().hasRotation()) {
            applyRotation(currentState.getTarget());
        }
        clearInputs();
        currentState.getInputStates().forEach(this::applyInput);
        currentState.getInputStates().clear();

        if (currentState.getStatus().isComplete()) {
            clearInputs();
        }
        return currentState.getStatus();
    }

    /** 玩家准星当前命中的方块状态;未命中返回 null。 */
    private BlockState crosshairBlockState() {
        double reach = NavSettings.get().blockReachDistance;
        HitResult hit = player.pick(reach, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return player.level().getBlockState(((BlockHitResult) hit).getBlockPos());
        }
        return null;
    }
    private ItemStack bestToolFor(BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1.0f;
        var inventory = player.getInventory();
        int upper = NavSettings.get().allowInventory
                ? Math.min(36, inventory.items.size()) : 9;
        for (int slot = 0; slot < upper; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            float speed = candidate.getDestroySpeed(state);
            if (!candidate.isEmpty() && speed > bestSpeed) {
                best = candidate.copy();
                bestSpeed = speed;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && offhand.getDestroySpeed(state) > bestSpeed) {
            return offhand.copy();
        }
        return best;
    }

    /**
     * 准备阶段:toBreak 里任一格上有下坠方块实体(且开了等待开关)
     * → 等待,不挖不动;任一格仍不可穿行 → 交给 {@link #beginBreaking}
     * 挖,返回未就绪。全部通透即就绪(WAITING 后不再重查)。
     */
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        for (BlockPos pos : positionsToBreak) {
            if (NavSettings.get().pauseMiningForFallingBlocks
                    && !player.level().getEntitiesOfClass(FallingBlockEntity.class,
                            new AABB(0, 0, 0, 1, 1.1, 1).move(pos)).isEmpty()) {
                return false;
            }
