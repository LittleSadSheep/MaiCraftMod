package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.PlayerInv;

import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.entity.InputDriver;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.act.PressReceipt;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.base.GoToThenDoTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.integration.create.CreateManualInput;
import org.maiwithu.maicraft.core.integration.create.CreateInteractionSurface;
import org.maiwithu.maicraft.core.inventory.CarriedItemVariants;
import org.maiwithu.maicraft.core.integration.create.CreateDeployerHandEvidence;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuHandParking;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 在当前位置对准方块、液体或前方按键。先选物品、等镜头真正对准，再按实际射线命中的目标执行。
 * 语义请求可让本任务自行比较站位并走近；原地点击仍沿用原行为。目标身份和原生点击效果分别核对。
 */
public final class InteractAtCompanionTask extends GoToThenDoTask<InteractAtTaskRecord> {

    private static final double REACH = 4.5;
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;
    /**
     * 当前这次点击或持续按住的执行过程。
     */

    private Interaction interaction;
    private final FirstPersonActionGate selection =
            new FirstPersonActionGate();
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    private int surfaceAimCorrections;
    private boolean surfaceStillVisible;
    private boolean surfaceCheckAttempted;
    private boolean itemSelected;
    /**
     * 在目标可见轮廓上挑出的瞄准点，避免只瞄方块中心而被部分遮挡。
     */
    private Vec3 aimPoint;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"(见 {@link PressReceipt})。 */
    private PressReceipt receipt;
    private AbstractContainerMenu menuBeforeUse;
    private boolean menuOpened;
    private int menuObservationTicks;
    private List<String> changes = List.of();
    // 下面保存持续按住的结束时间和结果文字；这个类当前没有重新寻路的过程。
    /**
     * 固定时长的按住动作在这个游戏刻松开；-1 表示没有固定结束刻。
     */
    private long holdUntil = -1;       // 固定时长按住（holdTicks > 0）时的松开游戏 tick。
    private String successMsg = "done";
    // 右键实际激活的方块（例如打开工作台界面）会被记录，供结果报告并让智能体循环写入 <known_blocks>。
    private BlockPos activatedBlock;
    private String activatedBlockId;
    private boolean heldUseStarted, heldUseCompleted, heldUseHandResolved;
    private InteractionHand heldUseHand = InteractionHand.MAIN_HAND;
    private int expectedItemBefore = -1, outputWaitTicks;
    private boolean manualCrank;
    private final CreateManualInput.UsageEvidence manualUsage = new CreateManualInput.UsageEvidence();
    private MachineMenuHandParking manualHandParking = new MachineMenuHandParking();
    private NativeActionReceipt manualHandSelection;
    private Map<String,Object> deployerHandBefore = Map.of();
    private final Set<Long> rejectedStances = new HashSet<>();
    private boolean terrainApproach;
    private int approachAttempts, approachCandidates;

    public InteractAtCompanionTask(LocalPlayer player, InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        // 若请求指定了要使用的物品，必须先确认背包确实持有，否则立即失败。
        return List.of(() -> r.item == null || PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                : new Precondition.Failure(
                        "don't have " + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use",
                        FailureType.NO_MATERIAL));
    }

    @Override
    protected PlayerNav buildNav() {
        if (!r.approachTarget || r.aim == null) return null;
        var stances = FirstPersonInteractionTargeting.visibleInteractionStands(player, r.aim,
                rejectedStances, this::visibleFrom);
        approachCandidates = stances.size(); approachAttempts++;
        var goal = GoalCompiler.interactionStances(r.aim, stances);
        // 先走现成路线；确实走不通且请求允许时再准备通路，目标方块始终受保护。
        return PlayerNav.to(player, () -> goal, WALK_SPEED, () -> bodySettled() && visibleFrom(player.getEyePosition()),
                terrainApproach ? PlayerNav.ContextProvider.TERRAFORM : PlayerNav.ContextProvider.DEFAULT).withTerrainProbe();
    }

    @Override protected TaskState handleNavFailure(FailureType type, String reason) {
        if (!r.approachTarget || interaction != null) return super.handleNavFailure(type, reason);
        stopNav();
        if (type == FailureType.STANCE_DUD && approachAttempts < 4) {
            // 走到后视线已变化，只排除这个实际无效脚位，重新比较其他位置，不重复点击未知效果。
            rejectedStances.add(player.blockPosition().asLong()); nav = buildNav(); return TaskState.RUNNING;
        }
        if (r.mayAlterTerrain && !terrainApproach) {
            terrainApproach = true; nav = buildNav(); return TaskState.RUNNING;
        }
        return super.handleNavFailure(type, reason);
    }

    private boolean visibleFrom(Vec3 eyes) {
        var item = r.emptyHand ? Items.AIR : r.item == null ? player.getMainHandItem().getItem() : r.item;
        if (FirstPersonInteractionTargeting.usesBucketRay(item))
            return FirstPersonInteractionTargeting.visibleBucketHit(player.level(), player, eyes, r.aim, player.blockInteractionRange(), item) != null;
        var surface = CreateInteractionSurface.forUse(player.level().getBlockState(r.aim), item);
        return surface.constrained() ? surface.visibleHit(player.level(), player, eyes, r.aim, REACH) != null
                : FirstPersonInteractionTargeting.hasLoadedReachLine(player.level(), player, eyes, r.aim, REACH);
    }

    @Override
    protected BlockPos gotoFirstTarget() {
        return r.aim;
    }

    @Override
    protected boolean reached() {
        // 收回水或岩浆后源格已经消失，仍须继续结算刚才的原生使用；出手前的射线检查不能截断待确认回执。
        if (interaction != null || r.aim == null) return true;
        if (!r.approachTarget) return withinReach();
        boolean ready = bodySettled() && visibleFrom(player.getEyePosition());
        // 反击或补食可能把角色带离工位，仍在同一目标内恢复接近，不要求模型再开一次导航任务。
        if (!ready && nav == null) nav = buildNav();
        return ready;
    }

    @Override
    protected TaskState act() {
        // 已到点击距离也要先让交通完成落地与装备恢复；同刻打开腾手背包会反过来挡住交通收尾，造成互相等待。
        if (r.approachTarget && nav != null) { stopNav(); return TaskState.RUNNING; }
        if (r.heldItemUseOnly) return useHeldItem();
        manualCrank = r.item == null && button() == Interaction.Button.USE && CreateManualInput.supported(player.level(), r.aim);
        // 从第一笔已确认原生使用开始记录实际转速和应力，持续操作结束后仍能说明驱动期间是否卡在超载。
        if (manualCrank && interaction != null) manualUsage.observe(player.level(), r.aim, interaction.confirmedUses());
        // 未指定道具的语义交互先收好遗留工具；反击或补食中断后每次新点击都恢复空手，已经发出的点击先结算。
        if ((r.emptyHand || manualCrank) && (interaction == null || !interaction.awaitingReceipt())) {
            TaskState hand = prepareEmptyHand(); if (hand != null) return hand;
        }
        // 到达交互位置后再解析准星命中，并据此执行动作。
        if (interaction == null) {
            if (r.requiredBlock != null && (!player.level().isLoaded(r.aim)
                    || !player.level().getBlockState(r.aim).is(r.requiredBlock))) {
                fail("the required interaction target changed or unloaded before aiming", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            // 反击等插入动作换过主手后重新选回目标；半成品按完整组件身份匹配，避免从第十四步切到第一步。
            if (r.item != null && itemSelected && !CarriedItemVariants.matches(player.getMainHandItem(), r.item, r.itemResourceId, player.registryAccess())) {
                selection.reset(); itemSelected = false; aimPoint = null; aimConvergence.reset();
            }
            if (r.item != null && !itemSelected) {
                // 选择过程中固定同一笔原生交换，先结清在途回执；准备完成后再核对组件，不能因移槽而另发选择。
                int source = selection.started() ? selection.requestedSlot()
                        : CarriedItemVariants.find(player.getInventory(), r.item, r.itemResourceId, player.registryAccess());
                if (source < 0) { fail("the requested carried item variant is missing or changed; observe inventory components before choosing again", FailureType.NO_MATERIAL); return TaskState.FAILED; }
                var selected = selection.select(player, source);
                if (selected == FirstPersonActionGate.Status.RUNNING) {
                    return TaskState.RUNNING;
                }
                if (selected == FirstPersonActionGate.Status.FAILED) {
                    fail("couldn't select the requested item: " + selection.failure(), FailureType.UNKNOWN);
                    return TaskState.FAILED;
                }
                itemSelected = true;
                if (!CarriedItemVariants.matches(player.getMainHandItem(), r.item, r.itemResourceId, player.registryAccess())) {
                    fail("the selected item variant changed before native use", FailureType.TARGET_LOST); return TaskState.FAILED;
                }
            }
            var useItem = player.getMainHandItem().getItem();
            boolean bucket = !manualCrank && button() == Interaction.Button.USE
                    && FirstPersonInteractionTargeting.usesBucketRay(useItem);
            // 普通取放要点中原生允许的面；桶仍按自己的取水或倒水射线走，不能混用台面规则。
            var useSurface = r.aim == null || bucket || button() != Interaction.Button.USE ? null
                    : CreateInteractionSurface.forUse(player.level().getBlockState(r.aim), useItem);
            if (r.aim != null) {
                if (aimPoint == null) {
                    var state = player.level().getBlockState(r.aim);
                    var visible = bucket
                            ? FirstPersonInteractionTargeting.visibleBucketHit(
                                    player.level(), player, player.getEyePosition(), r.aim, player.blockInteractionRange(), useItem)
                            : state.isAir()
                            ? null
                            : useSurface == null ? FirstPersonInteractionTargeting.visibleBlockHit(
                                    player.level(), player, player.getEyePosition(), r.aim, REACH)
                            : useSurface.visibleHit(player.level(), player, player.getEyePosition(), r.aim, REACH);
                    aimPoint = visible == null ? Vec3.atCenterOf(r.aim) : visible.getLocation();
                }
                // 使用交互不能继承前一施工任务留下的潜行放置状态。
                InputDriver.halt(player);
                InputDriver.lookAt(player, aimPoint);
                // requestLook 会在 endTick 应用并限速。必须跨过该边界，等真实镜头方向收敛后再信任 nativeRaytrace；
                // 否则旧视角会把正常的镜头延迟误判为遮挡。
                if (!aimConvergence.ready(player, aimPoint.subtract(player.getEyePosition()))) {
                    return TaskState.RUNNING;
                }
            }
            Vec3 rayEnd = player.getEyePosition().add(player.getViewVector(1.0F)
                    .scale(bucket ? player.blockInteractionRange() : REACH));
            HitResult hit = bucket
                    ? FirstPersonInteractionTargeting.bucketRay(player.level(), player,
                            player.getEyePosition(), rayEnd, useItem)
                    : Interaction.nativeRaytrace(player, REACH);
            // 镜头到位后仍核对真实命中点；台座侧面和机械手机壳都不能替代它们的原生取放入口。
            if (useSurface != null && useSurface.constrained()
                    && (!(hit instanceof BlockHitResult surface) || !surface.getBlockPos().equals(r.aim)
                    || !useSurface.accepts(surface))) {
                // 一度以内只说明镜头接近目标，机械手前端或置物台边缘仍可能尚未命中；未出手时先继续精确瞄准。
                if (refineSurfaceAim(useSurface)) return TaskState.RUNNING;
                fail(surfaceStillVisible
                        ? "the machine surface remains visible, but the actual view did not reach it after bounded aiming"
                        : "the required machine interaction surface is no longer visible", FailureType.OCCLUDED);
                return TaskState.FAILED;
            }
            // 与语义预检共用同一套遮挡口径:普通目标命中更近的别块就是被挡住;
            // 桶使用物品自己的源流体/放置面判据，不把水后方的机器当作点击目标。
            if (r.aim != null
                    && (bucket ? !FirstPersonInteractionTargeting.acceptsBucketHit(
                            player.level(), r.aim, useItem, (BlockHitResult) hit)
                    : FirstPersonInteractionTargeting.blockedByWorld(
                            player.level(), player.getEyePosition(), r.aim,
                            rayEnd, hit))) {
                String landing;
                if (hit instanceof BlockHitResult blockedHit
                        && hit.getType() == HitResult.Type.BLOCK) {
                    var blocker = blockedHit.getBlockPos();
                    String blockerId = BuiltInRegistries.BLOCK
                            .getKey(player.level().getBlockState(blocker).getBlock()).getPath();
                    landing = blockerId + " at " + blocker.getX() + "," + blocker.getY()
                            + "," + blocker.getZ();
                } else if (hit instanceof EntityHitResult entityHit) {
                    landing = "an entity at " + entityHit.getEntity().blockPosition().toShortString();
                } else {
                    landing = "empty space before reaching the target";
                }
                fail("aim " + aimLabel() + " is blocked from here — the crosshair lands on "
                        + landing + " instead. Reposition to the target's open side, then retry.",
                        FailureType.OCCLUDED);
                return TaskState.FAILED;
            }
            // 右键命中方块会激活它，例如打开工作台界面或切换开关。记录交互过的方块，让 <known_blocks> 除了已放置工作台外，也能带角色返回用过的工作站。
            // 后续收录仅保留受跟踪的工作站类型，门和按钮会被过滤掉。
            if (!bucket && button() == Interaction.Button.USE && hit instanceof BlockHitResult bhr) {
                activatedBlock = bhr.getBlockPos();
                activatedBlockId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(activatedBlock).getBlock()).getPath();
            }
            receipt = PressReceipt.before(player, r.aim);
            // 保存点击前的菜单身份，后续只给这次原生点击新打开的界面登记来源。
            menuBeforeUse = player.containerMenu;
            // 捕获这次交换前已有的持料，避免同种同数互换未产生数量差时只剩一条超时信息。
            deployerHandBefore = CreateDeployerHandEvidence.capture(player.level(),r.aim);
            // 点名道具时保留原生物品使用；空手操作目标时禁止回退，避免中断遗留的无线终端或工具抢走点击。
            interaction = bucket ? Interaction.useInAir(player, InteractionHand.MAIN_HAND,
                    r.holdTicks == 0 ? Interaction.Timing.once()
                            : r.holdTicks > 0 ? Interaction.Timing.hold(r.holdTicks) : Interaction.Timing.hold())
                    : Interaction.forHit(player, hit, button(), r.holdTicks, !manualCrank && !r.emptyHand);
            if (interaction != null) interaction.requireBlock(r.aim, r.requiredBlock);
            if (interaction == null) {       // 左键点击空气只会挥击，没有后续动作。
                successMsg = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // 到期只禁止下一次点击；已经发出的原生操作必须先结算，不能把持续时间走完冒充操作成功。
        if (holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.finishRepeating();
        }
        return switch (interaction.tick()) {
            case DONE -> {
                // 右键回执可能早于服务端菜单包；只等这次点击的界面，不因暂未开箱而重复右键。
                menuOpened = player.containerMenu != menuBeforeUse && player.containerMenu != player.inventoryMenu
                        && MenuVisibility.matches(ClientRuntime.requireContext(player).minecraft(), player.containerMenu);
                if (r.observeMenu && !menuOpened && ++menuObservationTicks < 40) yield TaskState.RUNNING;
                // use_container 与机器菜单共享已确认来源，后续检查、存取和关闭无需再右击一次或重启客户端。
                if (activatedBlock != null && interaction.confirmedUses() > 0 && menuOpened)
                    MachineMenu.rememberNativeOpened(player, player.containerMenu, activatedBlock);
                successMsg = describeDone() + settle();
                yield verifiedOutcome();
            }
            case FAILED -> {
                fail(interaction.failReason(), interaction.failType());
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    private TaskState prepareEmptyHand() {
        var context = ClientRuntime.requireContext(player);
        if (manualHandParking.started()) {
            var state = manualHandParking.tick(context);
            if (state == MachineMenuHandParking.Status.FAILED) {
                fail("empty-hand preparation failed: " + manualHandParking.failure(), FailureType.NO_SPACE); return TaskState.FAILED;
            }
            if (state != MachineMenuHandParking.Status.READY) return TaskState.RUNNING;
            // 自动反击后来又占用了主手时重新整理；已经完成的旧交换不能一直假装这次主手仍为空。
            if (!player.getMainHandItem().isEmpty()) manualHandParking = new MachineMenuHandParking();
        }
        if (manualHandSelection != null) {
            manualHandSelection = context.actions().poll(context, manualHandSelection);
            if (!manualHandSelection.terminal()) return TaskState.RUNNING;
            if (manualHandSelection.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                fail("empty-hand selection unconfirmed", FailureType.UNKNOWN); return TaskState.FAILED;
            }
            manualHandSelection = null; return TaskState.RUNNING;
        }
        if (player.getMainHandItem().isEmpty()) return null;
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).isEmpty()) {
            if (context.mutationAvailable()) manualHandSelection = context.actions().selectHotbar(context, slot, 20);
            return TaskState.RUNNING;
        }
        var state = manualHandParking.tick(context);
        if (state == MachineMenuHandParking.Status.FAILED) {
            fail("empty-hand preparation failed: " + manualHandParking.failure(), FailureType.NO_SPACE); return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    private TaskState useHeldItem() {
        // 选物也不能打断其他已开始的持用；本次尚未创建交互时，任何正在使用的物品都属于外部流程。
        if (interaction == null && player.isUsingItem()) {
            fail("another native item use is already active", FailureType.UNKNOWN); return TaskState.FAILED;
        }
        // 砂纸可以握在任意一只手；先使用当前已握住的指定物品，保留另一只手的加工原料。
        // 仅在选物前读取手别；背包交换的客户端预测可能已让砂纸出现在手里，此时仍必须等原交换确认并关好菜单。
        if (!heldUseHandResolved) {
            heldUseHandResolved = true;
            if (player.getMainHandItem().is(r.item)) itemSelected = true;
            else if (player.getOffhandItem().is(r.item)) { heldUseHand = InteractionHand.OFF_HAND; itemSelected = true; }
        }
        if (!itemSelected) {
            var status = selection.select(player, PlayerInv.findSlot(player.getInventory(), r.item));
            if (status == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
            if (status == FirstPersonActionGate.Status.FAILED) {
                fail("could not select the carried item: " + selection.failure(), FailureType.UNKNOWN); return TaskState.FAILED;
            }
            itemSelected = true;
        }
        if (interaction == null) {
            // 砂纸等物品要由原生use入口读取副手或自身目标；不制造一个不可选中的掉落物实体点击。
            receipt = PressReceipt.before(player, null);
            if (r.expectedOutputItem != null) expectedItemBefore = PlayerInv.count(player.getInventory(), r.expectedOutputItem);
            interaction = Interaction.useInAir(player, heldUseHand, Interaction.Timing.hold());
            heldUseStarted = true;
        }
        var state = interaction.tick();
        if (state == Interaction.Status.FAILED) { fail(interaction.failReason(), interaction.failType()); return TaskState.FAILED; }
        if (state != Interaction.Status.DONE) return TaskState.RUNNING;
        heldUseCompleted = true;
        // 使用动画结束与服务端产物槽更新可能分包到达；只等一小段同步窗口，绝不再自动使用一次物品。
        if (r.expectedOutputItem != null && PlayerInv.count(player.getInventory(), r.expectedOutputItem) <= expectedItemBefore) {
            if (++outputWaitTicks <= 40) return TaskState.RUNNING;
            fail("native item use completed but the expected carried output did not increase", FailureType.TARGET_LOST); return TaskState.FAILED;
        }
        successMsg = "native held item use completed" + settle(); return TaskState.SUCCESS;
    }

    // 如果调用方要求操作后出现某种方块，再检查一次；未提供这项要求时，这里直接接受执行结束。
    private TaskState verifiedOutcome() {
        if (r.expectedBlock != null && (r.aim == null || !player.level().getBlockState(r.aim).is(r.expectedBlock))) {
            fail("interaction completed without producing the required block "
                    + BuiltInRegistries.BLOCK.getKey(r.expectedBlock), FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        return TaskState.SUCCESS;
    }

    /**
     * 旧的换站位失败分类，目前本类没有调用它；实体交互类中另有仍在使用的版本。
     */
    private static boolean repositionable(FailureType type) {
        return type == FailureType.NO_PATH || type == FailureType.TERRAIN_BLOCKED
                || type == FailureType.BOXED_IN
                || type == FailureType.OUT_OF_REACH || type == FailureType.STANCE_DUD;
    }

    private Interaction.Button button() {
        return r.button == MouseButton.LEFT
                ? Interaction.Button.ATTACK : Interaction.Button.USE;
    }

    // 先要求身体站稳、在水中或坐在载具上。水桶按实际射线和玩家触及范围检查，普通点击仍按固定距离比较方块中心。
    private boolean withinReach() {
        // 空手任务不能沿用先前拿着的水桶射线，否则尚未整理背包就被错误的取水可达性拦住。
        var item = r.emptyHand ? Items.AIR : r.item == null ? player.getMainHandItem().getItem() : r.item;
        if (button() == Interaction.Button.USE && FirstPersonInteractionTargeting.usesBucketRay(item)) {
            return bodySettled() && FirstPersonInteractionTargeting.visibleBucketHit(
                    player.level(), player, player.getEyePosition(), r.aim, player.blockInteractionRange(), item) != null;
        }
        return bodySettled() && player.distanceToSqr(Vec3.atCenterOf(r.aim)) <= REACH_SQR;
    }

    private String aimLabel() {
        return r.aim.getX() + "," + r.aim.getY() + "," + r.aim.getZ();
    }

    private String describeDone() {
        String verb = r.button == MouseButton.LEFT ? "left-clicked" : "right-clicked";
        return verb + (r.aim != null ? " " + aimLabel() : " (forward)");
    }

    /**
     * 比较按键前后观察到的变化，并写进结果。没有变化时明确说明，但这份差异本身不决定动作成功与否。
     */
    private String settle() {
        changes = receipt == null ? List.of() : receipt.diff(player);
        // 选择起点本来不会改变方块外观；用专门的原生证据告诉规划者当前处于接线的哪一步。
        if (interaction!=null && interaction.chainUse()!=null) return " — "+interaction.chainUse().summary();
        if (manualCrank && manualUsage.overstressed())
            return " — native manual-generator activity was observed; the connected network was overstressed during use; machine production remains unverified.";
        if (changes.isEmpty()) {
            if (manualCrank && interaction != null && interaction.confirmedUses() > 0)
                return " — native manual-generator activity was observed; machine production remains unverified.";
            return " — but nothing visibly changed (hands, aimed block, nearby entities all "
                    + "as before). If you expected an effect, reposition or rethink.";
        }
        return " — " + String.join("; ", changes);
    }

    private boolean refineSurfaceAim(CreateInteractionSurface.Rule surface) {
        surfaceCheckAttempted = true;
        var visible = surface.visibleHit(player.level(), player, player.getEyePosition(), r.aim, REACH);
        surfaceStillVisible = visible != null;
        // 只重做尚未提交的瞄准，至多十次；真正被挡立即返回，也不会重复发送可能已经换物的点击。
        if (visible == null || surfaceAimCorrections >= 10) return false;
        surfaceAimCorrections++;
        aimPoint = visible.getLocation();
        aimConvergence.reset();
        return true;
    }

    /** 释放交互，再释放导航和目标覆盖层（父类默认清理）。 */
    @Override
    protected void cleanup() {
        if (interaction != null) interaction.stop();
        manualHandParking.cleanup(player);
        if (manualHandSelection != null && !manualHandSelection.terminal()) {
            var context = ClientRuntime.actor().activeContext().filter(value -> value.player() == player && value.isCurrent());
            if (context.isPresent()) context.get().actions().retireOneShotForTaskBoundary(context.get(), manualHandSelection, "manual hand selection ended");
        }
        selection.reset();
        aimConvergence.reset();
        aimPoint = null;
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == MouseButton.LEFT ? "left" : "right");
        if (r.observeMenu) {
            // 点击执行和开箱观察分别返回；空光标与存取仍由后续原生容器流程核验，不宣称已经搬过物品。
            data.put("menu_open_verified", menuOpened);
            data.put("menu_observation_ticks", menuObservationTicks);
            data.put("menu_class", player.containerMenu.getClass().getSimpleName());
            data.put("menu_cursor_empty", player.containerMenu.getCarried().isEmpty());
            data.put("inventory_transfer_verified", false);
        }
        if (r.approachTarget) data.put("interaction_approach", Map.of("route_attempts", approachAttempts,
                "stance_candidates", approachCandidates, "rejected_stances", rejectedStances.size(),
                "terrain_preparation_attempted", terrainApproach, "player_feet", player.blockPosition().toShortString()));
        if (surfaceCheckAttempted) {
            // 只读回执分别说明几何可见性与已尝试的镜头修正，便于区分机壳遮挡和真实射线仍未对准。
            data.put("surface_aim_corrections", surfaceAimCorrections);
            data.put("interaction_surface_visible_at_last_check", surfaceStillVisible);
        }
        // 普通机械交互超时也可能已经交换物品；完成通知不能把缺少确认改写为可直接重试。
        if (interaction != null) data.putAll(interaction.useEvidence());
        if (!deployerHandBefore.isEmpty()) {
            var hand = new HashMap<String,Object>(); hand.put("before",deployerHandBefore);
            var after = CreateDeployerHandEvidence.capture(player.level(),r.aim);
            hand.put("after",after.isEmpty()?Map.of("observation_status","target_unavailable"):after);
            if (interaction != null && interaction.submittedBlockHit() != null)
                hand.put("submitted_face",interaction.submittedBlockHit().getDirection().getName());
            data.put("deployer_hand_observation",hand);
        }
        // 缺料失败和成功选点都保留链条账，等待任务通知的模型也能自主决定下一步取材或连接。
        if (interaction!=null && interaction.chainUse()!=null) data.put("chain_conveyor_use",interaction.chainUse().evidence());
        if (manualCrank) {
            var generator = new HashMap<>(CreateManualInput.evidence(player.level(), r.aim,
                    interaction == null ? 0 : interaction.confirmedUses()));
            generator.put("during_use", manualUsage.data()); data.put("manual_generator", generator);
        }
        if (r.heldItemUseOnly) {
            data.put("held_item_use_started", heldUseStarted); data.put("native_use_completed", heldUseCompleted);
            data.put("used_hand", heldUseHand.name().toLowerCase());
            data.put("outcome_uncertain", heldUseStarted && !heldUseCompleted);
            if (expectedItemBefore >= 0) {
                int after = PlayerInv.count(player.getInventory(), r.expectedOutputItem);
                data.put("expected_output", Map.of("item_id", BuiltInRegistries.ITEM.getKey(r.expectedOutputItem).toString(),
                        "before", expectedItemBefore, "after", after, "observed_increase", after - expectedItemBefore));
                if (after <= expectedItemBefore) data.put("mechanical_retry_allowed", false);
            }
            if (heldUseStarted && !heldUseCompleted) data.put("mechanical_retry_allowed", false);
        }
        if (r.aim != null) {
            data.put("x", r.aim.getX());
            data.put("y", r.aim.getY());
            data.put("z", r.aim.getZ());
        }
        // 报告已激活的工作站和确切位置；位置以实际命中为准，不使用原始瞄准点，供智能体循环收录到 <known_blocks>。
        if (activatedBlock != null) {
            data.put("block", activatedBlockId);
            data.put("x", activatedBlock.getX());
            data.put("y", activatedBlock.getY());
            data.put("z", activatedBlock.getZ());
        }
        if (!changes.isEmpty()) {
            data.put("changes", changes);
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before interacting at " + (r.aim != null ? aimLabel() : "forward");
    }

    @Override
    protected String cancelledMessage() {
        return "interact_at interrupted";
    }
}
