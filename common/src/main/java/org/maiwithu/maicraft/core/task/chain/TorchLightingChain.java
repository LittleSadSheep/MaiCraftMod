// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.LandmarkProtection;
import org.maiwithu.maicraft.core.task.build.BuildEdgeMotion;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.lighting.RoutineTorchPlacement;
import org.maiwithu.maicraft.core.task.mine.MineBlockTaskRecord;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.reflex.Reflex;

/** 采矿、找矿和行走间隙自动插一支随身火把；身体与菜单结清后才接手，紧急自救始终优先。 */
public final class TorchLightingChain implements Task, Reflex {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate aim = new ActualViewConvergenceGate();
    private BuildTaskRecord.Target candidate;
    private NativeActionReceipt placement;
    private Vec3 origin;
    private BlockPos failedOrigin;
    private long retryAt, started;
    private boolean active;

    @Override public boolean canRun(LocalPlayer player) {
        if (active) return true;
        long now = player.level().getGameTime();
        if (now < retryAt || !ordinaryWork(CompanionTickDispatcher.current()) || !stable(player)
                || player.getAbilities().instabuild || !ClientRuntime.actor().settledForRoutinePause()
                || PlayerInv.carriedCount(player.getInventory(), Items.TORCH) == 0) return false;
        var context = ClientRuntime.requireContext(player);
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) return false;
        BlockPos feet = player.blockPosition(), eye = BlockPos.containing(player.getEyePosition());
        // 无显式工作时仅在洞内补光；同一处失败后先让角色继续走，避免原地反复转头插灯。
        if (failedOrigin != null && failedOrigin.distSqr(feet) < 16
                || CompanionTickDispatcher.current() == null && player.level().canSeeSky(eye)) return false;
        if (!RoutineTorchPlacement.needsLight(player.level().getMaxLocalRawBrightness(feet), player.level().getMaxLocalRawBrightness(eye))) return false;
        var protection = protection(player);
        if (!protection.problems().isEmpty()) return false;
        candidate = protection.run(() -> RoutineTorchPlacement.find(player, Set.of()));
        if (candidate == null) { retryAt = now + 40; return false; }
        return true;
    }

    static boolean ordinaryWork(TaskRecord task) {
        if (task == null || task instanceof MineBlockTaskRecord) return true;
        if (!(task instanceof IntentTaskRecord intent) || intent.paused() || intent.stepIndex() >= intent.steps().size()) return false;
        // 施工自己的灯位由图纸负责，战斗、计时和菜单工艺也不被日常补光改变节奏。
        return switch (intent.steps().get(intent.stepIndex()).ability()) {
            case "maicraft:acquire_items", "maicraft:find_block", "maicraft:travel" -> true;
            default -> false;
        };
    }

    static boolean stable(LocalPlayer player) {
        return player.isAlive() && player.onGround() && !player.isPassenger() && !player.isInWater()
                && !player.isInLava() && !player.isUsingItem() && !player.isSleeping()
                && BuildEdgeMotion.canStandAt(player, NavigationSafetyContext.forbiddenBodyCells(), pos -> !NavigationSafetyContext.forbidsBody(pos));
    }

    private static LandmarkProtection protection(LocalPlayer player) {
        var labels = new LinkedHashSet<String>();
        if (CompanionTickDispatcher.current() instanceof IntentTaskRecord intent && intent.stepIndex() < intent.steps().size()) {
            var step = intent.steps().get(intent.stepIndex()); labels.addAll(step.inheritedProtectionLabels());
            var explicit = step.parameters().get("protected_labels");
            if (explicit != null && explicit.isJsonArray()) explicit.getAsJsonArray().forEach(label -> labels.add(label.getAsString()));
        }
        return LandmarkProtection.resolve(List.copyOf(labels), IntentRuntime.get().landmarks(), player.level().dimension().location().toString());
    }

    @Override public TaskState tick(LocalPlayer player) {
        var context = ClientRuntime.requireContext(player);
        if (!active) {
            if (candidate == null) return TaskState.RUNNING;
            active = true; origin = player.position(); started = player.level().getGameTime();
            GameplayAttentionMonitor.reflexStarted(id(), "work area light below 8", "place one carried torch nearby",
                    "at most one carried torch", "only observed empty space beside terrain");
        }
        InputDriver.halt(player);
        // 已发出的点击先收回执；敌人抢占后位置改变，也不能忘记旧点击再补发一支。
        if (placement != null) {
            placement = context.actions().poll(context, placement);
            if (placement.terminal()) finish(player, placement.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED, placement.detail());
            return TaskState.RUNNING;
        }
        if (!stable(player) || player.position().distanceToSqr(origin) > .75 * .75 || player.level().getGameTime() - started > 100) {
            finish(player, false, "standing position changed or preparation timed out"); return TaskState.RUNNING;
        }
        int slot = selection.started() ? selection.requestedSlot() : PlayerInv.findSlot(player.getInventory(), Items.TORCH);
        var selected = selection.select(player, slot);
        if (selected == FirstPersonActionGate.Status.FAILED) { finish(player, false, selection.failure()); return TaskState.RUNNING; }
        if (selected != FirstPersonActionGate.Status.READY || !context.mutationAvailable()) return TaskState.RUNNING;
        var protection = protection(player);
        if (!player.getMainHandItem().is(Items.TORCH) || !protection.problems().isEmpty()
                || !protection.run(() -> RoutineTorchPlacement.stillUsable(player, candidate, Set.of()))) {
            finish(player, false, "torch site or held material changed"); return TaskState.RUNNING;
        }
        Direction face = candidate.desiredState().is(Blocks.WALL_TORCH) ? candidate.desiredState().getValue(WallTorchBlock.FACING) : Direction.UP;
        BlockPos support = candidate.pos().relative(face.getOpposite());
        Vec3 point = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
        InputDriver.lookAt(player, point);
        if (!aim.ready(player, point.subtract(player.getEyePosition()))) return TaskState.RUNNING;
        Vec3 end = player.getEyePosition().add(player.getViewVector(1).scale(Math.min(4.25, player.blockInteractionRange())));
        BlockHitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support) || hit.getDirection() != face) return TaskState.RUNNING;
        placement = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, confirmation(player, candidate), 40);
        return TaskState.RUNNING;
    }

    public static NativeConfirmation confirmation(LocalPlayer player, BuildTaskRecord.Target target) {
        // 火把常驻副手后，消耗必须按全身库存核对；背包与副手交换不能冒充一次放置。
        int before = PlayerInv.count(player.getInventory(), Items.TORCH);
        // 世界方块与火把扣减都符合，且收到本次服务器预测确认号后才记一支成功；本地画面预测不算。
        return new NativeConfirmation() {
            @Override public boolean requiresBlockAcknowledgement() { return true; }
            @Override public Verdict observe(LocalPlayerContext context) { return observe(context, false); }
            @Override public Verdict observeAcknowledged(LocalPlayerContext context) { return observe(context, true); }
            private Verdict observe(LocalPlayerContext context, boolean acknowledged) {
                if (!context.level().isLoaded(target.pos())) return Verdict.PENDING;
                var live = context.level().getBlockState(target.pos());
                int consumed = before - PlayerInv.count(context.player().getInventory(), Items.TORCH);
                if (consumed < 0 || consumed > 1 || !live.isAir() && !live.equals(target.desiredState())) return Verdict.DIVERGED;
                if (live.equals(target.desiredState()) && (consumed == 1
                        || context.player().getAbilities().instabuild && consumed == 0)) return Verdict.APPLIED;
                if (acknowledged && live.isAir() && consumed == 0) return Verdict.NOT_APPLIED;
                return Verdict.PENDING;
            }
        };
    }

    private void finish(LocalPlayer player, boolean confirmed, String detail) {
        // 有限回退把身体立即还给原工作；未知效果如实留在注意回执中，不把出手当成照明成功。
        GameplayAttentionMonitor.reflexFinished(id(), detail, confirmed ? 1 : 0,
                confirmed ? "one torch placement and consumption confirmed" : "no torch placement confirmed", "ambient light will be rechecked");
        Constants.LOG.info("[maicraft-light] 自动补光 confirmed={} target={} detail={}", confirmed, candidate == null ? null : candidate.pos(), detail);
        if (!confirmed) failedOrigin = player.blockPosition().immutable(); else failedOrigin = null;
        retryAt = player.level().getGameTime() + 100; active = false; candidate = null; placement = null;
        selection.reset(); aim.reset(); contextRelease(player);
    }

    private static void contextRelease(LocalPlayer player) {
        ClientRuntime.actor().activeContext().filter(context -> context.player() == player && context.isCurrent()).ifPresent(context -> context.body().releaseAll());
    }
    @Override public void stop(LocalPlayer player, StopReason reason) {
        contextRelease(player);
        if (reason == StopReason.PREEMPTED) return;
        if (active) {
            ClientRuntime.actor().activeContext().filter(context -> context.player() == player && context.isCurrent()).ifPresent(context -> {
                if (placement != null && !placement.terminal()) placement = context.actions().retireOneShotForTaskBoundary(context, placement, "automatic lighting ended: " + reason);
            });
            finish(player, placement != null && placement.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED, "automatic lighting ended: " + reason);
        }
    }
    @Override public String name() { return "TorchLightingChain"; }
    @Override public String id() { return "routine_torch_lighting"; }
    @Override public String describe() { return "采矿、找矿和行走时在暗处用随身火把自动补光，安全站稳并确认放置后继续工作。"; }
}
