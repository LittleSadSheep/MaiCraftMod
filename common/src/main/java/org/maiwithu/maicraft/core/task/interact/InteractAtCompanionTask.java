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
    private final org.maiwithu.maicraft.core.task.FirstPersonActionGate selection =
            new org.maiwithu.maicraft.core.task.FirstPersonActionGate();
    private final ActualViewConvergenceGate aimConvergence = new ActualViewConvergenceGate();
    private boolean itemSelected;
    /**
     * 在目标可见轮廓上挑出的瞄准点，避免只瞄方块中心而被部分遮挡。
     */
    private Vec3 aimPoint;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"(见 {@link PressReceipt})。 */
    private PressReceipt receipt;
    private java.util.List<String> changes = List.of();
    // 下面保存持续按住的结束时间和结果文字；这个类当前没有重新寻路的过程。
    /**
     * 固定时长的按住动作在这个游戏刻松开；-1 表示没有固定结束刻。
     */
    private long holdUntil = -1;       // game tick to release a fixed-duration hold (holdTicks > 0)
    private String successMsg = "done";
    // A right-click that activated a real block (a station's GUI): captured so the
    // result can report it and the agent loop can remember it in <known_blocks>.
    private net.minecraft.core.BlockPos activatedBlock;
    private String activatedBlockId;

    public InteractAtCompanionTask(LocalPlayer player, InteractAtTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        // If an item to use was named, fail fast unless we actually carry it.
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
    protected net.minecraft.core.BlockPos gotoFirstTarget() {
        return r.aim;
    }

    @Override
    protected boolean reached() {
        return r.aim == null || withinReach();
    }

    @Override
    protected TaskState act() {
        // Resolve the crosshair once we're in position, then drive the action.
        if (interaction == null) {
            if (r.requiredBlock != null && (!player.level().isLoaded(r.aim)
                    || !player.level().getBlockState(r.aim).is(r.requiredBlock))) {
                fail("the required interaction target changed or unloaded before aiming", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            if (r.item != null && !itemSelected) {
                var selected = selection.select(player, PlayerInv.findSlot(player.getInventory(), r.item));
                if (selected == org.maiwithu.maicraft.core.task.FirstPersonActionGate.Status.RUNNING) {
                    return TaskState.RUNNING;
                }
                if (selected == org.maiwithu.maicraft.core.task.FirstPersonActionGate.Status.FAILED) {
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
                // A use interaction must not inherit placement sneak from the preceding task.
                InputDriver.halt(player);
                InputDriver.lookAt(player, aimPoint);
                // requestLook is applied/rate-limited at endTick. Always cross that boundary and
                // wait for the actual camera vector to converge before trusting nativeRaytrace;
                // otherwise the old view can turn ordinary camera lag into a false obstruction.
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
                            player.level(), r.aim, useItem, (net.minecraft.world.phys.BlockHitResult) hit)
                    : FirstPersonInteractionTargeting.blockedByWorld(
                            player.level(), player.getEyePosition(), r.aim,
                            rayEnd, hit))) {
                String landing;
                if (hit instanceof net.minecraft.world.phys.BlockHitResult blockedHit
                        && hit.getType() == HitResult.Type.BLOCK) {
                    var blocker = blockedHit.getBlockPos();
                    String blockerId = BuiltInRegistries.BLOCK
                            .getKey(player.level().getBlockState(blocker).getBlock()).getPath();
                    landing = blockerId + " at " + blocker.getX() + "," + blocker.getY()
                            + "," + blocker.getZ();
                } else if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
                    landing = "an entity at " + entityHit.getEntity().blockPosition().toShortString();
                } else {
                    landing = "empty space before reaching the target";
                }
                fail("aim " + aimLabel() + " is blocked from here — the crosshair lands on "
                        + landing + " instead. Reposition to the target's open side, then retry.",
                        FailureType.OCCLUDED);
                return TaskState.FAILED;
            }
            // A right-click landing on a block activates it (opens a station's GUI,
            // flips a switch, …). Remember the block we touched so <known_blocks> can
            // walk us back to stations we've used, not just ones we placed. The harvest
            // filters to tracked station types; doors/buttons fall away there.
            if (!bucket && button() == Interaction.Button.USE && hit instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                activatedBlock = bhr.getBlockPos();
                activatedBlockId = BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(activatedBlock).getBlock()).getPath();
            }
            receipt = PressReceipt.before(player, r.aim);
            // This is the real LocalPlayer. Preserve vanilla's ordinary fall-through from an
            // unhandled block/entity use to the held item (food, pearls and modded items included).
            interaction = bucket ? Interaction.useInAir(player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    r.holdTicks == 0 ? Interaction.Timing.once()
                            : r.holdTicks > 0 ? Interaction.Timing.hold(r.holdTicks) : Interaction.Timing.hold())
                    : Interaction.forHit(player, hit, button(), r.holdTicks, true);
            if (interaction != null) interaction.requireBlock(r.aim, r.requiredBlock);
            if (interaction == null) {       // left-click on air — a swing, nothing to do
                successMsg = "nothing under the aim (left-click in the air)";
                return TaskState.SUCCESS;
            }
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }

        // A fixed-duration hold ends when its window elapses: release the button.
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

    /** Release the interaction, then the nav + overlay (base default). */
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
        // Report the activated station (and its exact position, authoritative over the
        // raw aim) so the agent loop can harvest it into <known_blocks>.
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
