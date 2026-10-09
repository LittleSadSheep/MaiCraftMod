// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.ReplantsCrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采集的任务：走到跟前，看清现场，只收熟了的作物、挖够得着的方块，然后走近让原版把掉落物收进包。
 * 收完庄稼顺手把种子种回去（种子从这次收获里出，没有就不补，补不上也不白收）；
 * 工具不够格不自己去做一把，如实说缺什么等级的工具——备工具是拿东西的事，两件事分开才都看得清。
 * 拿没拿到按背包里数量的变化确认：冷却中的掉落物照常等，不误报"附近没有"。
 */
final class GatherTask extends PhasedTask<GatherTask.Phase> {

    /** 任务的阶段：走近 → 看现场 → 动手（挖/收）→ 补种 → 等掉落物入包。掉落物目标没有中间三步。 */
    enum Phase { WALK, JUDGE, DIG, REPLANT, RECOLLECT }

    /** 半分钟没有真实进展算卡住；整件事最多做五分钟。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;
    private static final long MAX_TICKS = 20L * 60 * 5;
    /** 到了跟前等掉落物入包的耐心：拾取有冷却，等满这一阵再下结论。 */
    private static final int PICKUP_WAIT_TICKS = 20 * 3;

    private final GatherSpot spot;
    private final ApproachesTargets approaches;
    private final DigsBlocks digs;
    private final ReadsToolRequirements tools;
    private final ReadsSpot world;
    private final ReplantsCrops replants;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final Permissions permissions;

    /** 动手前背包里每样东西有几件；捡没捡到以它和结算时的差为准。 */
    private Map<String, Integer> baseline;
    /** 看现场时确认的方块种类；挖与结算用。 */
    private String liveType;
    /** 等掉落物入包已经等了多少刻。 */
    private int waitedForPickup;

    GatherTask(GatherSpot spot, ApproachesTargets approaches, DigsBlocks digs, ReadsToolRequirements tools,
            ReadsSpot world, ReplantsCrops replants, PermissionCheck permission, BackpackView backpack,
            OffhandContents offhand, Permissions permissions) {
        super("采集", Phase.WALK, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.spot = spot;
        this.approaches = approaches;
        this.digs = digs;
        this.tools = tools;
        this.world = world;
        this.replants = replants;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.permissions = permissions;
    }

    @Override protected Action enter(Phase phase) {
        BlockPos target = new BlockPos(spot.at().x(), spot.at().y(), spot.at().z());
        return switch (phase) {
            // 掉落物目标没有"动手"这步，走近后直接等入包；两种靠近都交给站位与靠近的模型。
            case WALK, RECOLLECT -> approaches.toward(target, spot.permissions());
            case DIG -> digs.dig(target).orElse(null);
            // 看现场不是现场动作，在 tick 里读现场；补种的入口再问一次，没接上时这一步跳过。
            case JUDGE -> null;
            case REPLANT -> replants == null ? null : replants.replant(target).orElse(null);
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case WALK -> runActionThen(context, () -> {
                snapshotBaseline();
                return spot.drop() ? Next.go(Phase.RECOLLECT, "是掉落物，走近让游戏自己收")
                        : Next.go(Phase.JUDGE, "到跟前了，先看清是什么");
            });
            case JUDGE -> judge();
            case DIG -> action() == null
                    ? Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作还没接上，采不了"))
                    : digDone(context);
            case REPLANT -> action() == null
                    ? Next.go(Phase.RECOLLECT, "补种没接上，不补了")
                    : replantDone(context);
            case RECOLLECT -> waitForPickup(context);
        };
    }

    // 看现场：目标还在不在、是熟作物还是普通方块、工具与许可过不过。哪一关不过都以问题如实结束。
    private Next<Phase> judge() {
        Optional<String> type = world.blockTypeAt(spot.at());
        if (type.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "要采的那格已经是空的：方块不在了，或者那时候看到的位置现在没有东西"));
        }
        liveType = type.get();
        if (spot.declaredType() != null && !spot.declaredType().equals(liveType)) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "要采的是 " + spot.declaredType() + "，但那里现在是 " + liveType));
        }
        if (world.isCrop(liveType)) {
            if (!world.matureCrop(spot.at(), liveType)) {
                // 没熟的被踩掉就白长了：只收熟的是采集的常识，没熟时如实说等成熟再来。
                return Next.fail(Problem.of(Problem.Kind.WRONG_TIME,
                        liveType + "还没熟，现在收不上东西", "等它成熟了再来收"));
            }
            return permissionOr(Next.go(Phase.DIG, "是熟了的作物，收"));
        }
        Optional<String> required = tools.toolRequired(liveType);
        if (required.isPresent() && !CarriedItems.hasToolThatSuffices(backpack, offhand, liveType, tools)) {
            // 工具不足不自动备：备一把镐可能是采矿石加合成的一大串，采集悄悄做完会让结果难读。
            // 先拿东西备一把工具再来采集，两步都清清楚楚。
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "挖" + liveType + "需要" + required.get() + "（或更高级的工具），身上没有够格的；"
                            + "采集不会自己去备工具",
                    "先用拿东西备一件够格的工具，再来采集"));
        }
        return permissionOr(Next.go(Phase.DIG, "是一格普通方块，挖"));
    }

    // 动手前过一次许可：别人搭的、玩家放的，一格都不动。
    private Next<Phase> permissionOr(Next<Phase> allowed) {
        Optional<Problem> refusal = permission.blockAllowed(permissions,
                PermissionCheck.WorldAction.DIG_BLOCK, spot.at(), liveType);
        return refusal.isEmpty() ? allowed : Next.fail(refusal.get());
    }

    private Next<Phase> digDone(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(Change.of(Change.Kind.BLOCK_BROKEN, liveType, 1));
                boolean crop = world.matureCrop(spot.at(), liveType);
                // 补种在挖掘之后：作物被收掉、种子进了背包，才轮得到补种。
                if (crop && replants != null && replants.replant(asBlock(spot.at())).isPresent()) {
                    yield Next.go(Phase.REPLANT, "收完了，顺手把种子种回去");
                }
                yield Next.go(Phase.RECOLLECT, "挖开了，等掉落物入包");
            }
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 补种成了、没种子、或游戏拒绝，都接着等掉落物；补种失败不冒充没收成，记下事实，保留已收的进度。
    private Next<Phase> replantDone(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordAttempt("补种", "补种这步做完了（种回去了，或者没有种子没补）");
                yield Next.go(Phase.RECOLLECT, "补种收尾了，等掉落物入包");
            }
            case ActionStatus.Failed failed -> {
                recordAttempt("补种", "没补上：" + failed.problem().message() + "；已收的照算");
                yield Next.go(Phase.RECOLLECT, "补种没成，保留已收的继续");
            }
        };
    }

    // 走近让原版结算拾取，按背包同类数量的变化确认。冷却中的掉落物照常等，等满耐心才下结论。
    private Next<Phase> waitForPickup(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> {
                waitedForPickup++;
                // 已经有东西进包就不用再等靠近：边走边捡是原版的规矩。
                if (!gainedSinceBaseline().isEmpty() || waitedForPickup >= PICKUP_WAIT_TICKS) {
                    yield settle();
                }
                yield Next.stay();
            }
            case ActionStatus.Done done -> settle();
            case ActionStatus.Failed failed -> {
                // 走不进掉落物那一格不算失败：站得够近原版也会收，先按等到的结算。
                recordAttempt("走近掉落物", "没站进去：" + failed.problem().message());
                yield settle();
            }
        };
    }

    private Next<Phase> settle() {
        Map<String, Integer> gained = gainedSinceBaseline();
        for (var entry : gained.entrySet()) {
            recordChange(Change.of(Change.Kind.ITEM_GAINED, entry.getKey(), entry.getValue()));
        }
        if (!gained.isEmpty()) {
            return Next.done(TaskResult.done("采集完成：" + gainedText(gained) + " 进了背包"));
        }
        if (spot.expectedItem() != null) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    spot.expectedItem() + " 没进背包：采开了但没等到掉落物（可能没掉、被别人捡走，"
                            + "或掉在够不着的地方）"));
        }
        return Next.done(TaskResult.done("采集完成：没有东西掉落入包，按现场如实记录"));
    }

    /** 动手以来背包里每样东西多了几件。 */
    private Map<String, Integer> gainedSinceBaseline() {
        Map<String, Integer> now = countByItem();
        Map<String, Integer> gained = new LinkedHashMap<>();
        for (var entry : now.entrySet()) {
            int before = baseline.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > before) {
                gained.put(entry.getKey(), entry.getValue() - before);
            }
        }
        return gained;
    }

    private void snapshotBaseline() {
        baseline = countByItem();
    }

    private Map<String, Integer> countByItem() {
        Map<String, Integer> counts = new HashMap<>();
        for (var stack : backpack.stacks()) {
            counts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return counts;
    }

    private String gainedText(Map<String, Integer> gained) {
        List<String> parts = gained.entrySet().stream()
                .map(entry -> entry.getValue() + " 个 " + entry.getKey())
                .toList();
        return String.join("、", parts);
    }

    private static BlockPos asBlock(WorldPosition at) {
        return new BlockPos(at.x(), at.y(), at.z());
    }
}
