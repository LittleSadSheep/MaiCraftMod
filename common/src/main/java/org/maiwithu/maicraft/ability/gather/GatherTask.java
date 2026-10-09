// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.ReplantsCrops;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
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
 * 采集的任务：走到跟前，看清现场，只收熟了的作物、挖够得着的方块，然后走过去捡起这一下掉出来的东西。
 * 收完庄稼、种子进了包再顺手种回去（种子从这次收获里出，没有就不补，补不上也不白收）；
 * 工具不够格不自己去做一把，如实说缺什么等级的工具——备工具是拿东西的事，两件事分开才都看得清。
 * 拿没拿到按背包里数量的变化确认：冷却中的掉落物照常等，不误报"附近没有"。
 */
final class GatherTask extends PhasedTask<GatherTask.Phase> {

    /** 任务的阶段：走近 → 看现场 → 动手（挖/收）→ 捡掉落物 → 补种。掉落物目标走近后直接捡。 */
    enum Phase { WALK, JUDGE, DIG, PICK_UP, REPLANT }

    /** 半分钟没有真实进展算卡住；整件事最多做五分钟。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;
    private static final long MAX_TICKS = 20L * 60 * 5;

    private final GatherSpot spot;
    private final ApproachesTargets approaches;
    private final DigsBlocks digs;
    private final ReadsToolRequirements tools;
    private final ReadsSpot world;
    private final ReplantsCrops replants;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final Permissions permissions;

    /** 动手前背包里每样东西有几件；捡没捡到以它和结算时的差为准。 */
    private Map<String, Integer> baseline;
    /** 看现场时确认的方块种类；挖与结算用。 */
    private String liveType;
    /** 看现场时确认是熟了的作物：收完要不要补种按它，挖开后格子空了就再也看不出来。 */
    private boolean matureCrop;
    /** 动手前脚边已经躺着的掉落物：捡东西只捡这一下新冒出来的。 */
    private Set<Integer> dropsBefore = Set.of();
    /** 下一阶段的动作：上一刻按现场备好，进入阶段时交给基类持有。 */
    private Action prepared;

    GatherTask(GatherSpot spot, ApproachesTargets approaches, DigsBlocks digs, ReadsToolRequirements tools,
            ReadsSpot world, ReplantsCrops replants, PicksUpDrops drops, PermissionCheck permission,
            BackpackView backpack, OffhandContents offhand, Permissions permissions) {
        super("采集", Phase.WALK, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.spot = spot;
        this.approaches = approaches;
        this.digs = digs;
        this.tools = tools;
        this.world = world;
        this.replants = replants;
        this.drops = drops;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.permissions = permissions;
    }

    @Override protected Action enter(Phase phase) {
        if (phase == Phase.WALK) {
            // 出发前记下背包里每样东西有几件：走过去时顺路吸进包的掉落物也算这次捡到的。
            snapshotBaseline();
            // 走近交给站位与靠近的模型。
            return approaches.toward(asBlock(spot.at()), spot.permissions());
        }
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case WALK -> runActionThen(context, () -> {
                // 掉落物目标：走到了就把附近地上的东西捡起来（出手前没有"原来就在"的那一批）。
                return spot.drop() ? goWith(Phase.PICK_UP, drops.pickUpNewSince(Set.of()), "是掉落物，走过去捡")
                        : Next.go(Phase.JUDGE, "到跟前了，先看清是什么");
            });
            case JUDGE -> judge();
            case DIG -> digDone(context);
            case PICK_UP -> pickedUp(context);
            case REPLANT -> replantDone(context);
        };
    }

    private Next<Phase> goWith(Phase phase, Action action, String why) {
        prepared = action;
        return Next.go(phase, why);
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
            matureCrop = true;
            return permissionOr("是熟了的作物，收");
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
        return permissionOr("是一格普通方块，挖");
    }

    // 动手前过一次许可：别人搭的、玩家放的，一格都不动。许可过了才记下脚边原有的掉落物、开挖。
    private Next<Phase> permissionOr(String why) {
        Optional<Problem> refusal = permission.blockAllowed(permissions,
                PermissionCheck.WorldAction.DIG_BLOCK, spot.at(), liveType);
        if (refusal.isPresent()) return Next.fail(refusal.get());
        Optional<Action> dig = digs.dig(asBlock(spot.at()));
        if (dig.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作还没接上，采不了"));
        }
        dropsBefore = drops.nearby();
        return goWith(Phase.DIG, dig.get(), why);
    }

    // 挖开了：记下拆掉的方块，走过去捡这一下掉出来的东西。
    private Next<Phase> digDone(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(Change.of(Change.Kind.BLOCK_BROKEN, liveType, 1));
                yield goWith(Phase.PICK_UP, drops.pickUpNewSince(dropsBefore), "挖开了，走过去捡掉出来的东西");
            }
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 捡完了（捡不到的如实记一笔）：收的是熟作物就趁种子进了包顺手种回去，否则按背包变化结算。
    private Next<Phase> pickedUp(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("捡起掉出的东西", failed.problem().message());
        }
        if (matureCrop && replants != null) {
            Optional<Action> replant = replants.replant(asBlock(spot.at()), liveType);
            if (replant.isPresent()) {
                return goWith(Phase.REPLANT, replant.get(), "收完了，顺手把种子种回去");
            }
        }
        return settle();
    }

    // 补种成了、没种子、或游戏拒绝，都按已收的结算；补种失败不冒充没收成，记下事实。
    private Next<Phase> replantDone(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordAttempt("补种", "补种这步做完了（种回去了，或者没有种子没补）");
                yield settle();
            }
            case ActionStatus.Failed failed -> {
                recordAttempt("补种", "没补上：" + failed.problem().message() + "；已收的照算");
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
