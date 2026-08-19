package org.maiwithu.maicraft.core.pathing.execute;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;

import java.util.EnumMap;

import org.maiwithu.maicraft.core.pathing.moves.Input;
import org.maiwithu.maicraft.core.pathing.moves.Movement;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.MovementState;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.act.ToolSelect;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import net.minecraft.client.player.LocalPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 第一人称执行边界:把移动原语每 tick 的视角、按键和点击意图交给客户端 actor。
 *
 * <p>移动与视角使用一刻租约;挖掘、使用、热栏选择和菜单换位均保留跨 tick
 * 回执,只有客户端可观察事实确认后才记账或继续依赖该动作。执行器不直接写位置、
 * 速度、视角、按键字段或世界方块。
 * <ol>
 *   <li>准星尚未命中目标时只转头,不提交点击;</li>
 *   <li>同 tick 破坏优先于使用,原生动作串行化;</li>
 *   <li>深层背包材料先经菜单回执换入热栏,再经热栏回执选中。</li>
 * </ol>
 */
public final class ExecHarness implements Movement.ExecutionDelegate {

    private final LocalPlayer player;
    private final AimProcessor aim;
    /** 这次导航对地形的许可(与搜索/执行上下文同一来源:PlayerNav 的 ContextProvider)。 */
    private final TerrainPermit permit;
    /**
     * 这次导航真挖了什么、真放了什么。执行器是唯一动手的地方,账也只记在这儿:
     * 任务回执末尾如实相告(en route: broke …; placed …),模型事后至少知道自己干过什么。
     */
    private final TerrainBill ledger = new TerrainBill();

    /** 本 tick 的按键表(跨 tick 保留,移动原语每 tick 清空重设)。 */
    private final EnumMap<Input, Boolean> keys = new EnumMap<>(Input.class);

    /** 本 tick 的期望视角;commit 后即失效。 */
    private MovementState.MovementTarget target;

    /** 距下一次允许右键的 tick 数。 */
    private int rightClickCooldown;

    private NativeActionReceipt breakingReceipt;
    private BlockPos breakingTarget;
    private BlockState breakingBefore;

    private NativeActionReceipt useReceipt;
    private InteractionHand useHand;
    private BlockPos usePlaceAt;
    private BlockState usePlaceBefore;
    private BlockPos useClickedAt;
    private BlockState useClickedBefore;
    private NativeActionReceipt hotbarReceipt;
    private int hotbarTarget = -1;
    private MenuReceipt stagingReceipt;
    private int stagingHotbar = -1;


    /** Sprint is folded into the same one-tick body lease as the movement axes. */
    private boolean sprinting;

    /** 本 tick 是否有任何记录待落地。 */
    private boolean dirty;
    /** forceCancel is a terminal owner boundary; cleanup may invoke it again in the same tick. */
    private boolean taskBoundaryStopped;

    public ExecHarness(LocalPlayer player, TerrainPermit permit) {
        this.player = player;
        this.permit = permit;
        this.aim = new AimProcessor();
    }

    @Override
    public TerrainPermit permit() {
        return permit;
    }

    /** 这次导航至今真动过的地形(只读视图;空账 = 一块没动)。 */
    public TerrainBill ledger() {
        return ledger;
    }

    // ==================== ExecutionDelegate 四钩子 ====================

    /**
     * 开挖一格:找该格的可视瞄点(形状中心 + 六面心逐一试射线),把
     * 视角目标设过去;实际视角的射线已命中该格(或角度已贴住目标)
     * 才按左键——视线步进决定挖掘的起始时机。完全不可视时直接瞄方块
     * 中心强挖(射线打中什么破什么,遮挡回退由挖掘器处理)。
     */
    @Override
    public void beginBreaking(MovementState state, BlockPos pos) {
        dirty = true;
        // A route may have to clear terrain before the mining task reaches its
        // own target.  Stage/select the best carried implement through the same
        // receipt-backed boundary before the first native break tick.  Once a
        // break receipt is live, keep advancing it without trying to transact
        // inventory every tick.
        if (breakingReceipt == null || breakingReceipt.terminal()) {
            settleBreakingReceipt(ClientRuntime.requireContext(player));
            int bestSlot = ToolSelect.bestSlot(player, player.level().getBlockState(pos));
            if (bestSlot >= 0) {
                ItemStack best = player.getInventory().getItem(bestSlot).copy();
                Movement.ItemSelection selection = ensureItem(
                        stack -> ItemStack.isSameItemSameComponents(stack, best), true, false);
                if (selection == Movement.ItemSelection.WAITING) {
                    state.setInput(Input.CLICK_LEFT, false);
                    return;
                }
            }
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aimPoint = reachableAimPoint(pos);
        if (aimPoint != null) {
            state.setTarget(new MovementState.MovementTarget(
                    AimGeometry.yawTo(eye, aimPoint),
                    AimGeometry.pitchTo(eye, aimPoint), true));
            if (isLookingAt(pos) || isFacingTarget(state.getTarget())) {
                state.setInput(Input.CLICK_LEFT, true);
            }
        } else {
            // 完全不可视:瞄整格中心强按左键——实际挖到的是准星命中的
            // 遮挡物(左键落地始终以准星射线为准)
            Vec3 center = AimGeometry.blockCenter(pos);
            state.setTarget(new MovementState.MovementTarget(
                    AimGeometry.yawTo(eye, center),
                    AimGeometry.pitchTo(eye, center), true));
            state.setInput(Input.CLICK_LEFT, true);
        }
    }

    @Override
    public void applyRotation(MovementState.MovementTarget target) {
        this.target = target;
        dirty = true;
    }

    @Override
    public void clearInputs() {
        keys.clear();
        dirty = true;
    }

    @Override
    public void applyInput(Input input, boolean held) {
        keys.put(input, held);
        dirty = true;
    }

    // ==================== 执行器覆写面 ====================

    /** 某键当前是否被请求按下。 */
    public boolean isKeyRequested(Input input) {
        return keys.getOrDefault(input, false);
    }

    /** 强制设键(执行器的疾跑接管、直跳强按等)。 */
    public void forceKey(Input input, boolean held) {
        keys.put(input, held);
        dirty = true;
    }

    /**
     * 清空全部按键并立即停住身体(输入字段清零、松疾跑、松潜行)。
     * 取消/暂停路径时用;不打断进行中的挖掘(那是 {@link #stopBreaking})。
     */
    public void clearAllKeys() {
        keys.clear();
        target = null;
        InputDriver.halt(player);
        InputDriver.sneak(player, false);
        sprinting = false;
    }

    /** 中止进行中的原生挖掘;回执不跨身体 epoch 复用。 */
    public void stopBreaking() {
        NativeActionReceipt receipt = breakingReceipt;
        if (receipt != null && !receipt.terminal()) {
            try {
                LocalPlayerContext context = ClientRuntime.requireContext(player);
                context.actions().cancelBreaking(context, receipt);
            } catch (IllegalStateException ignored) {
                // Body/control epoch ended; the actor boundary marks the receipt uncertain.
            }
        }
        clearBreakingReceipt();
    }

    /**
     * Hand native-action ownership to another first-person primitive without discarding the route.
     *
     * <p>A navigator can make its goal reachable while its final terrain-clearing break receipt is
     * still waiting for synchronized world facts.  Clearing movement keys alone does not release
     * that serialized actor slot.  This method settles or retires every native/menu receipt owned
     * by the harness, while leaving {@code PathingCore}'s current path, next path and searches
     * untouched.  The caller may submit another mutation only when the returned value is true.
     */
    public boolean yieldForExternalAction() {
        clearAllKeys();
        return releaseOwnedReceipts("navigation yielded to another first-person action");
    }

    /**
     * Release every actor/menu receipt owned by a navigation that is being discarded.
     *
     * <p>Route execution can be between motions while a hotbar selection, placement use, or
     * inventory staging transaction is still waiting for synchronized facts.  A terminal
     * {@code PlayerNav.stop()} must not drop those Java references while the actor boundary keeps
     * the receipt active: the next semantic child legitimately needs the same serialized slot.
     */
    public void stopForTaskBoundary() {
        clearAllKeys();
        if (taskBoundaryStopped) return;
        taskBoundaryStopped = true;

        releaseOwnedReceipts("navigation ended");
    }

    /** Settle all task-local actor/menu references and report whether this tick may mutate again. */
    private boolean releaseOwnedReceipts(String boundary) {

        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (breakingReceipt != null) {
            if (!breakingReceipt.terminal()) {
                context.actions().cancelBreakingForTaskBoundary(
                        context,
                        breakingReceipt,
                        boundary + " before its native break was confirmed");
            } else if (breakingReceipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                    && breakingTarget != null && breakingBefore != null) {
                ledger.addBreak(breakingTarget, breakingBefore);
            }
            clearBreakingReceipt();
        }

        if (useReceipt != null && !useReceipt.terminal()) {
            useReceipt = context.actions().retireOneShotForTaskBoundary(
                    context,
                    useReceipt,
                    boundary + " while a block-use effect was awaiting confirmation");
        }
        settleUseReceipt(context);

        if (hotbarReceipt != null && !hotbarReceipt.terminal()) {
            hotbarReceipt = context.actions().retireOneShotForTaskBoundary(
                    context,
                    hotbarReceipt,
                    boundary + " while hotbar selection was awaiting confirmation");
        }
        settleHotbarReceipt(context);

        if (stagingReceipt != null && !stagingReceipt.terminal()) {
            context.menus().closeForTaskBoundary(
                    context,
                    20,
                    boundary + " while inventory staging was awaiting confirmation");
        }
        stagingReceipt = null;
        stagingHotbar = -1;

        // A confirmed block use may have opened a container.  The navigation owns that UI and
        // cannot leave it in front of the next task after its own terminal boundary.
        if (player.containerMenu != player.inventoryMenu && context.mutationAvailable()) {
            context.menus().closeForTaskBoundary(
                    context, 20, boundary + " with a container menu still open");
        }
        return context.mutationAvailable();
    }

    /** 是否有进行中的挖掘(liveness 记账:挖硬方块也是真实推进)。 */
    public boolean isDigging() {
        return breakingReceipt != null && !breakingReceipt.terminal();
    }

    public void setSprinting(boolean sprinting) {
        if (this.sprinting != sprinting) {
            this.sprinting = sprinting;
            dirty = true;
        }
    }

    /** 视角步进量化器(执行器做放置预判时共用同一套数学)。 */
    public AimProcessor aimProcessor() {
        return aim;
    }

    // ==================== 提交 ====================

    /** 有记录待落地时提交一次;无记录只递减右键冷却(冷却按游戏刻走)。 */
    public void commitIfDirty() {
        if (dirty || breakingReceipt != null || useReceipt != null) {
            commit();
        } else {
            if (rightClickCooldown > 0) {
                rightClickCooldown--;
            }
        }
    }

    /**
     * 提交本 tick 的身体意图与至多一个原生动作。原生动作只经 actor 端口
     * 发起并跨 tick 等待回执;这里不把本地立即返回值当成功。
     */
    public void commit() {
        dirty = false;
        MovementState.MovementTarget t = target;
        target = null;
        float startYaw = player.getYRot();
        float startPitch = player.getXRot();
        LocalPlayerContext context = ClientRuntime.requireContext(player);

        settleUseReceipt(context);
        settleBreakingReceipt(context);
        settleHotbarReceipt(context);
        settleStagingReceipt(context);
        boolean itemTransactionPending = hotbarReceipt != null || stagingReceipt != null;
        if (rightClickCooldown > 0) {
            rightClickCooldown--;
        }

        if (isKeyRequested(Input.CLICK_LEFT)) {
            keys.put(Input.CLICK_RIGHT, false);
            if (useReceipt == null && !itemTransactionPending) {
                breakingTick(context, pickAlongView());
            }
        } else if (breakingReceipt != null) {
            cancelBreaking(context);
        } else if (useReceipt == null && !itemTransactionPending
                && rightClickCooldown == 0 && isKeyRequested(Input.CLICK_RIGHT)) {
            rightClickTick(context);
        }

        if (t != null && t.hasRotation()) {
            AimProcessor.Rotation stepped = aim.step(startYaw, startPitch, t.getYaw(), t.getPitch());
            applyLook(stepped);
        }

        float forward = (isKeyRequested(Input.MOVE_FORWARD) ? 1.0f : 0.0f)
                + (isKeyRequested(Input.MOVE_BACK) ? -1.0f : 0.0f);
        float strafe = (isKeyRequested(Input.MOVE_LEFT) ? 1.0f : 0.0f)
                + (isKeyRequested(Input.MOVE_RIGHT) ? -1.0f : 0.0f);
        boolean sneak = isKeyRequested(Input.SNEAK);
        if (sneak) {
            float sneakSpeed = (float) player.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
            forward *= sneakSpeed;
            strafe *= sneakSpeed;
        }
        float bodyYaw = player.getYRot();
        float pathYaw = t != null && t.hasRotation() ? t.getYaw() : bodyYaw;
        float[] impulse = AimProcessor.remapInput(strafe, forward, pathYaw, bodyYaw);
        InputDriver.applyMovement(player, impulse[1], impulse[0],
                isKeyRequested(Input.JUMP), sneak, sprinting);
    }

    private void applyLook(AimProcessor.Rotation rotation) {
        InputDriver.look(player, rotation.yaw(), rotation.pitch());
    }

    private void settleBreakingReceipt(LocalPlayerContext context) {
        if (breakingReceipt == null) {
            return;
        }
        if (!breakingReceipt.terminal()) {
            return;
        }
        if (breakingReceipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                && breakingTarget != null && breakingBefore != null) {
            ledger.addBreak(breakingTarget, breakingBefore);
        }
        clearBreakingReceipt();
    }

    private void clearBreakingReceipt() {
        breakingReceipt = null;
        breakingTarget = null;
        breakingBefore = null;
    }

    private void breakingTick(LocalPlayerContext context, BlockHitResult hit) {
        if (breakingReceipt != null) {
            if (hit == null) {
                cancelBreaking(context);
                return;
            }
            BlockPos hitPos = hit.getBlockPos();
            if (!hitPos.equals(breakingTarget)
                    && (breakingTarget == null
                    || hitPos.distManhattan(breakingTarget) > 2
                    || player.level().getBlockState(breakingTarget).isAir())) {
                cancelBreaking(context);
                return;
            }
            breakingReceipt = context.actions().continueBreaking(context, breakingReceipt);
            settleBreakingReceipt(context);
            return;
        }
        if (hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        breakingTarget = pos;
        breakingBefore = player.level().getBlockState(pos);
        breakingReceipt = context.actions().startBreaking(context, hit, 600);
        settleBreakingReceipt(context);
    }

    private void cancelBreaking(LocalPlayerContext context) {
        if (breakingReceipt != null && !breakingReceipt.terminal()) {
            breakingReceipt = context.actions().cancelBreaking(context, breakingReceipt);
        }
        settleBreakingReceipt(context);
    }

    private void settleUseReceipt(LocalPlayerContext context) {
        if (useReceipt == null) {
            return;
        }
        if (!useReceipt.terminal()) {
            return;
        }
        if (useReceipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            recordConfirmedPlacement(usePlaceAt, usePlaceBefore);
            if (useClickedAt == null || !useClickedAt.equals(usePlaceAt)) {
                recordConfirmedPlacement(useClickedAt, useClickedBefore);
            }
        }
        useReceipt = null;
        useHand = null;
        usePlaceAt = null;
        usePlaceBefore = null;
        useClickedAt = null;
        useClickedBefore = null;
    }

    private void recordConfirmedPlacement(BlockPos pos, BlockState before) {
        if (pos == null || before == null || !before.canBeReplaced() || !player.level().isLoaded(pos)) {
            return;
        }
        BlockState now = player.level().getBlockState(pos);
        if (!now.canBeReplaced()) {
            ledger.addPlace(pos, now.getBlock());
        }
    }

    // ==================== 右键 ====================

    /** 沿当前准星提交一次原生方块使用,结果由后续客户端事实确认。 */
    private void rightClickTick(LocalPlayerContext context) {
        if (player.getControlledVehicle() instanceof net.minecraft.world.entity.vehicle.Boat
                && (isKeyRequested(Input.MOVE_FORWARD) || isKeyRequested(Input.MOVE_BACK)
                        || isKeyRequested(Input.MOVE_LEFT) || isKeyRequested(Input.MOVE_RIGHT))) {
            return; // 驾船且本 tick 有移动输入(手在桨上)不右键;副座/静坐可点击
        }
        BlockHitResult hit = pickAlongView();
        if (hit == null) {
            return; // 未命中方块或被实体遮挡:不对空挥右键,也不扣冷却
        }
        rightClickCooldown = NavSettings.get().rightClickSpeed - 1;
        useClickedAt = hit.getBlockPos().immutable();
        useClickedBefore = player.level().getBlockState(useClickedAt);
        usePlaceAt = (useClickedBefore.canBeReplaced()
                ? useClickedAt : useClickedAt.relative(hit.getDirection())).immutable();
        usePlaceBefore = player.level().getBlockState(usePlaceAt);
        useHand = chooseUseHand();
        ItemStack beforeHand = player.getItemInHand(useHand).copy();
        NativeConfirmation confirmation = NativeConfirmation.anyOf(
                NativeConfirmation.blockChanged(useClickedAt, useClickedBefore),
                NativeConfirmation.blockChanged(usePlaceAt, usePlaceBefore),
                NativeConfirmation.menuChanged(player.containerMenu.containerId),
                NativeConfirmation.heldItemChanged(useHand, beforeHand));
        useReceipt = context.actions().useBlock(context, useHand, hit, confirmation, 20);
        settleUseReceipt(context);
    }

    private InteractionHand chooseUseHand() {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && safeForOffhand(main)) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    // ==================== 视线判定 ====================

    /** 实际视角的射线是否命中该格(实体遮挡视为未命中)。 */
    public boolean isLookingAt(BlockPos pos) {
        BlockHitResult hit = pickAlongView();
        return hit != null && hit.getBlockPos().equals(pos);
    }

    /**
     * 视线拾取:方块射线命中、且眼与命中点之间没有可拾取实体遮挡时
     * 返回命中;否则 null(实体挡视线的 tick 点击不落地)。
     */
    private BlockHitResult pickAlongView() {
        BlockHitResult hit = clipAlongView();
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = hit.getLocation();
        var entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, eye, end, new net.minecraft.world.phys.AABB(eye, end).inflate(1.0),
                e -> !e.isSpectator() && e.isPickable(), end.distanceToSqr(eye));
        return entityHit == null ? hit : null;
    }

    /** 实际视角是否已贴住目标转角(角度容差 0.01°;量化残差下很少成立,主判据是射线)。 */
    public boolean isFacingTarget(MovementState.MovementTarget target) {
        if (!target.hasRotation()) {
            return false;
        }
        return Math.abs(AimProcessor.normalizeDelta(player.getYRot() - target.getYaw())) < 0.01
                && Math.abs(player.getXRot() - target.getPitch()) < 0.01;
    }

    /** 准星此刻命中的方块;未命中或被实体遮挡返回 null。 */
    public BlockPos crosshairBlock() {
        BlockHitResult hit = pickAlongView();
        return hit != null ? hit.getBlockPos() : null;
    }

    /** 沿实体当前视角的轮廓射线(不穿流体);触及距离创造 5.0/生存按设置。 */
    private BlockHitResult clipAlongView() {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f)
                .scale(AimGeometry.blockReachDistance(player)));
        return player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点(形状中心优先,再六面心);
     * 全部被挡返回 null。
     */
    private Vec3 reachableAimPoint(BlockPos pos) {
        return AimGeometry.reachableAimPoint(player, pos);
    }

    // ==================== 换手 / 备货 ====================

    @Override
    public Movement.ItemSelection selectItem(java.util.function.Predicate<ItemStack> desired) {
        return ensureItem(desired, true);
    }

    /** Ensure a scaffold stack is staged before entering a placement movement. */
    public Movement.ItemSelection ensureThrowawayInHotbar() {
        var acceptable = ScaffoldMaterials.of(player);
        return ensureItem(
                stack -> !stack.isEmpty() && acceptable.contains(stack.getItem()), false);
    }

    /** Ensure a water bucket is staged before entering a guarded fall. */
    public Movement.ItemSelection ensureWaterBucketInHotbar() {
        return ensureItem(stack -> stack.is(Items.WATER_BUCKET), false);
    }

    private Movement.ItemSelection ensureItem(
            java.util.function.Predicate<ItemStack> desired, boolean select) {
        return ensureItem(desired, select, true);
    }

    private Movement.ItemSelection ensureItem(
            java.util.function.Predicate<ItemStack> desired, boolean select,
            boolean allowOffhand) {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        settleUseReceipt(context);
        settleBreakingReceipt(context);
        Movement.ItemSelection selected = settleHotbarReceipt(context);
        if (selected == Movement.ItemSelection.WAITING) {
            return selected;
        }
        Movement.ItemSelection staged = settleStagingReceipt(context);
        if (staged == Movement.ItemSelection.WAITING) {
            return staged;
        }
        if (breakingReceipt != null || useReceipt != null) {
            return Movement.ItemSelection.WAITING;
        }

        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (desired.test(inv.getItem(i))) {
                return select ? selectHotbar(context, i) : Movement.ItemSelection.READY;
            }
        }

        if (allowOffhand && desired.test(player.getOffhandItem())) {
            if (!select || safeForOffhand(inv.getSelected())) {
                return Movement.ItemSelection.READY;
            }
            for (int i = 0; i < 9; i++) {
                if (safeForOffhand(inv.getItem(i))) {
                    return selectHotbar(context, i);
                }
            }
            return Movement.ItemSelection.UNAVAILABLE;
        }

        if (!NavSettings.get().allowInventory) {
            return Movement.ItemSelection.UNAVAILABLE;
        }
        if (player.containerMenu != player.inventoryMenu) {
            return Movement.ItemSelection.UNAVAILABLE;
        }
        int upper = Math.min(36, inv.items.size());
        for (int i = 9; i < upper; i++) {
            if (desired.test(inv.getItem(i))) {
                int targetSlot = inv.selected;
                try {
                    stagingHotbar = targetSlot;
                    stagingReceipt = context.menus().swapInventoryToHotbar(
                            context, i, targetSlot, 20);
                    return settleStagingReceipt(context);
                } catch (RuntimeException unavailable) {
                    stagingReceipt = null;
                    stagingHotbar = -1;
                    return Movement.ItemSelection.UNAVAILABLE;
                }
            }
        }
        return Movement.ItemSelection.UNAVAILABLE;
    }

    private Movement.ItemSelection selectHotbar(LocalPlayerContext context, int slot) {
        if (player.getInventory().selected == slot) {
            return Movement.ItemSelection.READY;
        }
        if (hotbarReceipt != null) {
            return settleHotbarReceipt(context);
        }
        try {
            hotbarTarget = slot;
            hotbarReceipt = InputDriver.selectHotbar(player, slot);
            return settleHotbarReceipt(context);
        } catch (RuntimeException unavailable) {
            hotbarReceipt = null;
            hotbarTarget = -1;
            return Movement.ItemSelection.WAITING;
        }
    }

    private Movement.ItemSelection settleHotbarReceipt(LocalPlayerContext context) {
        if (hotbarReceipt == null) {
            return Movement.ItemSelection.READY;
        }
        if (!hotbarReceipt.terminal()) {
            return Movement.ItemSelection.WAITING;
        }
        boolean applied = hotbarReceipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                && player.getInventory().selected == hotbarTarget;
        hotbarReceipt = null;
        hotbarTarget = -1;
        return applied ? Movement.ItemSelection.READY : Movement.ItemSelection.UNAVAILABLE;
    }

    private Movement.ItemSelection settleStagingReceipt(LocalPlayerContext context) {
        if (stagingReceipt == null) {
            return Movement.ItemSelection.READY;
        }
        if (!stagingReceipt.terminal()) {
            return Movement.ItemSelection.WAITING;
        }
        boolean applied = stagingReceipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED
                && stagingHotbar >= 0 && stagingHotbar < 9;
        stagingReceipt = null;
        stagingHotbar = -1;
        return applied ? Movement.ItemSelection.READY : Movement.ItemSelection.UNAVAILABLE;
    }

    private static boolean safeForOffhand(ItemStack stack) {
        return stack.isEmpty()
                || stack.getItem().components().has(
                        net.minecraft.core.component.DataComponents.TOOL);
    }
}
