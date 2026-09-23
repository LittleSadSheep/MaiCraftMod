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
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/**
 * 在当前位置对准方块、液体或前方按键。先选物品、等镜头真正对准，再按实际射线命中的目标执行。
 * 它当前不会自己走过去；超出工作距离就失败，让上层先移动。若规定了目标原来的方块和操作后的方块，会在对应阶段检查。
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
    private boolean itemSelected;
    /**
     * 在目标可见轮廓上挑出的瞄准点，避免只瞄方块中心而被部分遮挡。
     */
    private Vec3 aimPoint;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"(见 {@link PressReceipt})。 */
    private PressReceipt receipt;
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
        // 本任务不自带到场导航:身体须已在触及距离内(基座在 reached()==false
        // 且无导航时直接教学失败,旅行归 goto)。
        return null;
    }

    @Override
    protected BlockPos gotoFirstTarget() {
        return r.aim;
    }

    @Override
    protected boolean reached() {
        return r.aim == null || withinReach();
    }

    @Override
    protected TaskState act() {
        // 到达交互位置后再解析准星命中，并据此执行动作。
        if (interaction == null) {
            if (r.requiredBlock != null && (!player.level().isLoaded(r.aim)
                    || !player.level().getBlockState(r.aim).is(r.requiredBlock))) {
                fail("the required interaction target changed or unloaded before aiming", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            if (r.item != null && !itemSelected) {
                var selected = selection.select(player, PlayerInv.findSlot(player.getInventory(), r.item));
                if (selected == FirstPersonActionGate.Status.RUNNING) {
                    return TaskState.RUNNING;
                }
                if (selected == FirstPersonActionGate.Status.FAILED) {
                    fail("couldn't select the requested item: " + selection.failure(), FailureType.UNKNOWN);
                    return TaskState.FAILED;
                }
                itemSelected = true;
            }
            var useItem = player.getMainHandItem().getItem();
            boolean bucket = button() == Interaction.Button.USE
                    && FirstPersonInteractionTargeting.usesBucketRay(useItem);
            if (r.aim != null) {
                if (aimPoint == null) {
                    var state = player.level().getBlockState(r.aim);
                    var visible = bucket
                            ? FirstPersonInteractionTargeting.visibleBucketHit(
                                    player.level(), player, player.getEyePosition(), r.aim, player.blockInteractionRange(), useItem)
                            : state.isAir()
                            ? null
                            : FirstPersonInteractionTargeting.visibleBlockHit(
                                    player.level(), player, player.getEyePosition(), r.aim, REACH);
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
            // 当前操作对象是真实 LocalPlayer；保留原版处理：方块或实体未响应使用操作时，继续使用手持物品，包括食物、珍珠和模组物品。
            interaction = bucket ? Interaction.useInAir(player, InteractionHand.MAIN_HAND,
                    r.holdTicks == 0 ? Interaction.Timing.once()
                            : r.holdTicks > 0 ? Interaction.Timing.hold(r.holdTicks) : Interaction.Timing.hold())
                    : Interaction.forHit(player, hit, button(), r.holdTicks, true);
            if (interaction != null) interaction.requireBlock(r.aim, r.requiredBlock);
            if (interaction == null) {       // 左键点击空气只会挥击，没有后续动作。
                successMsg = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // 固定时长的按住操作到期后松开按键。
        // 当前实现到按住时限就直接停止并结算，不再等 interaction 内尚未确认的点击。
        // 只有另外设置了 expectedBlock 才会核对目标方块，未设置时可能把仍在等待的动作报成成功（A30）。
        if (holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone() + settle();
            return verifiedOutcome();
        }
        return switch (interaction.tick()) {
            case DONE -> {
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
        var item = r.item == null ? player.getMainHandItem().getItem() : r.item;
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
        if (changes.isEmpty()) {
            return " — but nothing visibly changed (hands, aimed block, nearby entities all "
                    + "as before). If you expected an effect, reposition or rethink.";
        }
        return " — " + String.join("; ", changes);
    }

    /** 释放交互，再释放导航和目标覆盖层（父类默认清理）。 */
    @Override
    protected void cleanup() {
        if (interaction != null) interaction.stop();
        selection.reset();
        aimConvergence.reset();
        aimPoint = null;
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == MouseButton.LEFT ? "left" : "right");
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
