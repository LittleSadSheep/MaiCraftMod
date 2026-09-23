package org.maiwithu.maicraft.core.task.interact;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.PlayerInv;

import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.entity.InputDriver;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.GoToThenDoTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.act.PressReceipt;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;

/**
 * 先靠近选定实体，跟随它的位置，等真实准星命中它后再执行左键或右键。
 * 第一次靠近失败时允许在目标附近换一次站位；物品选择、持续按住和收尾结果也由这次任务保留。
 */
public final class InteractEntityCompanionTask extends GoToThenDoTask<InteractEntityTaskRecord> {

    private static final double REACH = 3.0;            // 原版实体交互距离。
    private static final double REACH_SQR = REACH * REACH;
    private static final double WALK_SPEED = 1.0;
    /**
     * 第一次路线走不通时，第二次允许站在目标附近这个范围内，但实际操作仍要求距离和视线通过。
     */
    private static final double REPOSITION_RADIUS = 2.5;
    /**
     * 最多额外尝试一次站位；耗尽后把原失败原因一起返回。
     */
    private static final int MAX_REPOSITIONS = 1;

    private Entity entity;
    // 保存换站位次数和第一次失败的原因，暂停恢复时继续同一次尝试。
    /**
     * 已经额外换过几次站位。
     */
    private int repositionAttempts;
    /**
     * 保留第一次失败原因，最终结果同时说明后续尝试为何失败。
     */
    private String firstNavFailReason;
    private Interaction interaction;
    private final FirstPersonActionGate selection =
            new FirstPersonActionGate();
    private boolean itemSelected;
    /** 按键前的世界快照,收尾时对账出"真发生了什么"。 */
    private PressReceipt receipt;
    private List<String> changes = List.of();
    private long holdUntil = -1;
    private boolean acted = false;     // 至少有一次按键命中；之后目标死亡应算成功，而非失败。
    private String successMsg = "done";

    public InteractEntityCompanionTask(LocalPlayer player, InteractEntityTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                // 解析并缓存目标；若目标已消失或移出范围，立即失败。
                () -> {
                    entity = player.clientLevel.getEntity(r.entityId);
                    return (entity == null || !entity.isAlive())
                            ? new Precondition.Failure("no entity with id " + r.entityId
                                    + " nearby (it may have despawned or moved out of range)",
                                    FailureType.TARGET_LOST)
                            : null;
                },
                () -> r.item == null || PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("don't have "
                                + BuiltInRegistries.ITEM.getKey(r.item).getPath() + " to use on it",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected PlayerNav buildNav() {
        // 到达条件是同时处于交互距离内且视线畅通；寻路会持续跟随实体，直到两项都满足。
        // 若墙挡在角色与目标之间，就重新调整站位绕开，而不是永远停在墙前。
        return new PlayerNav(player, () -> entity.blockPosition(), WALK_SPEED, this::inReachAndLos)
                .withTerrainProbe();
    }

    /** 目标消失时报告结果、固定按住时间到期，或已在交互距离内且视线畅通时执行本 tick 动作；否则由父类导航继续跟随实体。 */
    @Override
    protected boolean reached() {
        return entity == null || !entity.isAlive()
                || (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil)
                || inReachAndLos();
    }

    @Override
    protected TaskState act() {
        // 目标消失时，若此前左键已命中则视为成功；否则说明目标在角色接触前逃离。
        if (entity == null || !entity.isAlive()) {
            if (acted) {
                successMsg = r.button == MouseButton.LEFT
                        ? "defeated " + targetName() : "done with " + targetName();
                return TaskState.SUCCESS;
            }
            fail("the target entity is gone before I could reach it", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }

        // 目标是她自己坐着的载具:右键的意图(上船)早已是事实,立即了结;左键
        // 原版玩家也打不到自己的座驾。不拦的话,下面的准星确认要求"看见目标",
        // 而从座位上看自己的船永远确认不了——任务空转到超时,实测就是这么挂的。
        if (entity == player.getVehicle()) {
            if (r.button == MouseButton.LEFT) {
                fail("you are riding the " + targetName()
                        + " — can't hit your own vehicle; a goto somewhere else steps off first",
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            successMsg = "already riding the " + targetName();
            return TaskState.SUCCESS;
        }

        // 固定时长按住到期后即结束，即使临近结束时视线暂时被挡住。
        // 按住时限到了便停止并返回成功，即使最后一次交互还没确认；这也是审计记录 A30 的触发点。
        if (interaction != null && holdUntil >= 0 && player.level().getGameTime() >= holdUntil) {
            interaction.stop();
            successMsg = describeDone() + settle();
            return TaskState.SUCCESS;
        }

        // 处于距离内且视线畅通时，先瞄准实体并确认准星实际命中它，再按下按键；避免另一实体恰好走入射线后误操作。
        InputDriver.lookAt(player, entity.getEyePosition());
        HitResult hit = Interaction.nativeRaytrace(player, REACH);
        boolean onTarget = hit.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) hit).getEntity() == entity;
        if (!onTarget) {
            return TaskState.RUNNING;   // 正在稳定或有物体短暂挡住视线，下个 tick 重新瞄准。
        }

        if (interaction == null) {
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
            receipt = PressReceipt.before(player, null);
            // 遵循真实 LocalPlayer 语义：实体没有处理交互时，原版可能继续使用手持物品。
            interaction = Interaction.forHit(player, hit, button(), r.holdTicks, true);
            if (r.holdTicks > 0) {
                holdUntil = player.level().getGameTime() + r.holdTicks;
            }
        }
        acted = true;

        return switch (interaction.tick()) {
            case DONE -> {
                successMsg = describeDone() + settle();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                fail(interaction.failReason(), FailureType.UNKNOWN);
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;
        };
    }

    /**
     * 只有无路、够不着或站位不合适等失败才尝试换一次站位；仍跟踪同一实体，不另找目标。
     * 再次失败时保留第一次原因，并附上这次尝试的结果。
     */
    @Override
    protected TaskState handleNavFailure(FailureType type, String reason) {
        if (repositionable(type) && repositionAttempts < MAX_REPOSITIONS) {
            repositionAttempts++;
            firstNavFailReason = reason;
            stopNav();
            nav = PlayerNav.toGoal(player,
                    () -> (entity == null || !entity.isAlive()) ? null
                            : NavGoal.near(entity.blockPosition(), REPOSITION_RADIUS),
                    WALK_SPEED, this::inReachAndLos).withTerrainProbe();
            return TaskState.RUNNING;
        }
        String original = firstNavFailReason != null ? firstNavFailReason : reason;
        String tried = repositionAttempts > 0
                ? " (also tried a looser stance anywhere within " + REPOSITION_RADIUS
                        + " blocks of it: " + reason + ")"
                : "";
        fail("can't reach " + targetName() + ": " + original + tried, type);
        return TaskState.FAILED;
    }

    /**
     * 这些失败可能通过换一个站位解决；其他失败直接结束。
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

    // 当前按双方位置之间的三格距离粗筛，没有读取玩家属性修改后的实体触及范围。
    private boolean withinReach() {
        return bodySettled() && entity != null
                && player.distanceToSqr(entity.position()) <= REACH_SQR;
    }

    /**
     * 要求身体状态允许操作、距离够近，而且眼睛到目标没有方块遮挡；真正点击前还要让准星命中该实体。
     */
    private boolean inReachAndLos() {
        return withinReach() && player.hasLineOfSight(entity);
    }

    private String targetName() {
        return entity != null ? entity.getName().getString() : "entity#" + r.entityId;
    }

    private String describeDone() {
        String verb = r.button == MouseButton.LEFT ? "attacked" : "interacted with";
        return verb + " " + targetName();
    }

    /** 收尾对账,与 interact_at 同款:只报事实,判断留给读回执的人。 */
    private String settle() {
        changes = receipt == null ? List.of() : receipt.diff(player);
        if (changes.isEmpty()) {
            return "";   // 实体交互多数不留可见痕迹(交易开了界面、动物进了求爱态),不硬报"没变"
        }
        return " — " + String.join("; ", changes);
    }

    /** 释放交互，再释放导航和目标覆盖层（父类默认清理）。 */
    @Override
    protected void cleanup() {
        selection.reset();
        if (interaction != null) interaction.stop();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("button", r.button == MouseButton.LEFT ? "left" : "right");
        data.put("entity_id", r.entityId);
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
        return "timed out before interacting with " + targetName();
    }

    @Override
    protected String cancelledMessage() {
        return "interact_entity interrupted";
    }
}
