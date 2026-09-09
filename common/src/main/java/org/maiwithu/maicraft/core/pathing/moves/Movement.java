package org.maiwithu.maicraft.core.pathing.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
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
 * 旧移动执行器的共用部分：保存起终点、计算费用、处理挡路方块，并把每次更新想按的键交给执行代理。
 * 当前没有调用 setExecutionDelegate 的地方，这套实例执行流程未接入现用输入。
 * 静态 feet 和 pathStart 仍被 PlayerNav、移动任务使用，所以不能直接把整个文件视为无用。
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

    // 第一次需要时才计算费用；目的格禁止身体进入就直接记为不可走，重新检查必须先清掉缓存。
    public double getCost(CalculationContext context) {
        if (cost == null) {
            if (context.isForbiddenBodyCell(dest.getX(), dest.getY(), dest.getZ())) {
                cost = ActionCosts.COST_INF;
            } else {
                MutableMoveResult result = new MutableMoveResult();
                cost = calculateCost(context, result);
            }
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
     * 把玩家真实脚高换成导航使用的格编号。站在半砖或楼梯里时用上方格表示，这只是编号约定，不会移动玩家。
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
     * 给贴着方块边缘或刚离地的玩家找可用的起点格：先看脚下，再查附近支撑，空中还会试低一格的位置。
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
     * 旧执行流程先推进具体动作，再补水中上浮或卡墙处理，最后交出本次按键并清空按键表；没有执行代理就不会真正按键。
     */
    public MovementStatus update() {
        currentState = updateState(currentState);
        BlockPos feet = feet(player);
        if (MovementHelper.isLiquid(player.level().getBlockState(feet))
                && dest.getY() > src.getY()
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

    /**
     * 旧水中移动规则：先让刚露出水面的角色继续换气，再按目标是否更高、更低、是否有深水选择上浮、下潜或游泳。
     */
    protected final void swimTowards(MovementState state, BlockPos target) {
        // Use the planned edge's vertical intent, not the live feet cell. Entering the real
        // swimming pose puts the feet one cell below the abstract surface node; comparing to
        // that live cell would misread every horizontal swim as an ascent and instantly bob
        // back to the surface.
        int vertical = Integer.compare(target.getY(), src.getY());
        float yaw = AimGeometry.yawTo(player.getEyePosition(), AimGeometry.blockCenter(target));
        // BreathChain owns the emergency ascent. Once the eyes clear the surface, keep that
        // surface stroke until vanilla has replenished the authoritative air value; immediately
        // sneaking under again would make navigation and the breath reflex alternate every tick.
        if (!player.isEyeInFluid(FluidTags.WATER)
                && player.isInWater()
                && player.getAirSupply() < player.getMaxAirSupply()) {
            state.setTarget(new MovementState.MovementTarget(yaw, -28.0f, false))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.JUMP, true);
            return;
        }

        BlockPos feet = feet(player);
        boolean targetWater = MovementHelper.isWater(player.level().getBlockState(target));
        boolean targetHasWaterBelow = targetWater
                && MovementHelper.isWater(player.level().getBlockState(target.below()));

        // A horizontal surface-lattice edge ending on land or one-block-deep water is a wade-out,
        // not a request to dive.  If the physical body is already in the swimming pose, rise while
        // advancing so it can regain standing height at the bank.  Every movement tick rebuilds
        // its input map, and the explicit false below also makes the release visible within this
        // tick if an earlier preparation branch requested sneak.
        boolean surfaceOrWade = vertical > 0 || (vertical == 0 && !targetHasWaterBelow);
        if (surfaceOrWade) {
            boolean submerged = player.isUnderWater() || player.isSwimming()
                    || player.isEyeInFluid(FluidTags.WATER);
            state.setTarget(new MovementState.MovementTarget(
                            yaw, submerged ? -28.0f : 0.0f, false))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SNEAK, false);
            if (vertical > 0 || submerged || !targetWater) {
                state.setInput(Input.JUMP, true);
            }
            return;
        }

        // Sneak can lower a body only when its current physical column contains at least two
        // consecutive water cells.  In two-block-deep water an upright floating body may report
        // its feet in either the upper or lower water cell, hence the symmetric above/below test.
        // A one-block-deep column has water on neither side of the feet; holding sneak there while
        // aiming at a deeper neighbour pins a crouching body at the lip (especially below a low
        // ceiling). Wade forward first, then enter the swim pose after crossing into deep water.
        boolean feetInWater = MovementHelper.isWater(player.level().getBlockState(feet));
        boolean canDescendHere = feetInWater
                && (MovementHelper.isWater(player.level().getBlockState(feet.below()))
                        || MovementHelper.isWater(player.level().getBlockState(feet.above())));
        boolean enteringSwim = targetWater
                && vertical <= 0
                && !player.isUnderWater()
                && !player.isSwimming()
                && canDescendHere;
        float pitch = vertical < 0
                ? 28.0f
                : vertical > 0
                        ? -28.0f
                        : player.isSwimming() ? 0.0f : 12.0f;
        state.setTarget(new MovementState.MovementTarget(yaw, pitch, false))
                .setInput(Input.MOVE_FORWARD, true)
                .setInput(Input.SNEAK, false);
        if (enteringSwim || (vertical < 0 && targetWater && canDescendHere)) {
            // Vanilla cancels sprint while the eyes are at the surface. Sink first; on the tick
            // isUnderWater becomes true this releases shift and the sprint request below starts
            // the real horizontal swimming pose.
            state.setInput(Input.SNEAK, true);
        } else if (player.isUnderWater() || player.isSwimming()) {
            state.setInput(Input.SPRINT, true);
        }
        if (vertical > 0) {
            state.setInput(Input.JUMP, true);
        }
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
            if (!MovementHelper.canWalkThrough(player.level(), pos)) {
                beginBreaking(state, pos);
                return false;
            }
        }
        return true;
    }

    /**
     * 状态机推进,子类覆写并先走本实现:未就绪 → PREPPING;
     * 就绪后 PREPPING → WAITING → RUNNING。
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }
        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }
        return state;
    }

    /** 当前是否可被安全中断(默认恒可;悬空放置中的子类覆写)。 */
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState state) {
        return true;
    }

    /** 重置状态机(路径回退重执行时用)。 */
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    // ==================== 执行层钩子(经注入代理落地) ====================

    /**
     * 执行代理:四个钩子的真实落点。移动原语只描述"要看哪、按什么键、
     * 挖哪格",代理负责把这些落到实体上(视角步进、输入字段、渐进挖掘)。
     */
    public enum ItemSelection {
        READY,
        WAITING,
        UNAVAILABLE
    }

    @FunctionalInterface
    public interface ItemSelector {
        ItemSelection select(Predicate<ItemStack> desired);
    }
    public interface ExecutionDelegate {

        /** 开挖一格:选可视面、转头,视线就位后按左键。 */
        void beginBreaking(MovementState state, BlockPos pos);

        /** 应用期望视角(按鼠标步进量化逼近,不瞬间对准)。 */
        void applyRotation(MovementState.MovementTarget target);

        /** 清空全部按键。 */
        void clearInputs();

        /** 应用单个按键。 */
        void applyInput(Input input, boolean held);

        /** 这次导航对地形的许可:执行期"顺手"的放置(跑酷落点补块)只在可改地形时做。 */

        /** Select or stage one matching stack through receipt-backed client ports. */
        ItemSelection selectItem(Predicate<ItemStack> desired);
        TerrainPermit permit();
    }

    // 这个代理负责实际转头、按键、选物品和挖掘；当前没有绑定入口，因此下面的实例动作只能形成旧状态描述。
    private ExecutionDelegate executionDelegate;

    /** 注入执行代理;未注入时四个钩子为空操作(纯规划用途)。 */
    public void setExecutionDelegate(ExecutionDelegate delegate) {
        this.executionDelegate = delegate;
    }

    /** 开挖一格,转发执行代理。 */
    protected void beginBreaking(MovementState state, BlockPos pos) {
        if (executionDelegate != null) {
            executionDelegate.beginBreaking(state, pos);
        }
    }

    /** 应用期望视角,转发执行代理。 */
    protected void applyRotation(MovementState.MovementTarget target) {
        if (executionDelegate != null) {
            executionDelegate.applyRotation(target);
        }
    }

    /** 清空全部按键,转发执行代理。 */
    protected void clearInputs() {
        if (executionDelegate != null) {
            executionDelegate.clearInputs();
        }
    }

    /** 应用单个按键,转发执行代理。 */
    protected void applyInput(Input input, boolean held) {
        if (executionDelegate != null) {
            executionDelegate.applyInput(input, held);
        }
    }

    /** 执行期能不能改地形;未注入代理(纯规划)按不能算——规划已由上下文成本裁决。 */
    protected boolean mayAlterTerrain() {
        return executionDelegate != null && executionDelegate.permit().mayAlter();
    }

    /** Receipt-aware material selection for movement helpers. */
    protected ItemSelection selectItem(Predicate<ItemStack> desired) {
        return executionDelegate == null
                ? ItemSelection.UNAVAILABLE
                : executionDelegate.selectItem(desired);
    }

    /** Callback form used by the shared placement helper. */
    protected ItemSelector itemSelector() {
        return this::selectItem;
    }

    // ==================== 元数据 ====================

    public BlockPos getSrc() {
        return src;
    }

    public BlockPos getDest() {
        return dest;
    }

    public BlockPos getDirection() {
        return dest.subtract(src);
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }

    /** 丢弃三类格集缓存,下次查询按当前世界重算。 */
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    /** 此刻仍不可穿行、需要挖掉的格(缓存到 {@link #resetBlockCache()})。 */
    public List<BlockPos> toBreak(net.minecraft.world.level.BlockGetter level) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(level, pos)) {
                result.add(pos);
            }
        }
        toBreakCached = result;
        return result;
    }

    /** 此刻仍不可站立、需要放上方块的格(缓存到 {@link #resetBlockCache()})。 */
    public List<BlockPos> toPlace(net.minecraft.world.level.BlockGetter level) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(level, positionToPlace)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    /** 会用身体挤进去的格(基类恒空;对角移动覆写产出切角柱)。 */
    public List<BlockPos> toWalkInto(net.minecraft.world.level.BlockGetter level) {
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos getToPlace() {
        return positionToPlace;
    }

    /** 记录成本计算时 dest 所在 chunk 是否已加载。 */
    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.isLoaded(dest.getX(), dest.getZ());
    }

    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }
}
