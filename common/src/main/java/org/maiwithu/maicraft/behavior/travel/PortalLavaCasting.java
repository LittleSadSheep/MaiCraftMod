// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.ConstructionInput;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ConstructionTask;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.child.ChildTaskAction;
import org.maiwithu.maicraft.kernel.goal.Permissions;
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
 * 浇筑建门：在岩浆池岸边用一只桶、一桶水和十来块石头把门框浇出来。
 *
 * <p>备桶、备水（拿空桶去水边舀）、备石料 → 填直弯岸、铺站台、清净空（交施工引擎）→ 挡块、起手水、挖挡块，
 * 底行与两侧底脚成黑曜石 → 收水 → 砌模具 → 高处倒水 → 一桶一格浇框：桶里没岩浆就去池里舀 →
 * 收水、等退水 → 拆模具、岩浆倒回池子。每一桶的经过如实记账：倒出圆石、还是岩浆都记下，这格不再倒第二桶；
 * 门框成型没成型由建门验框说，这里不循环。靠近、换手、倒桶、放块、挖格全走施工引擎的接缝。
 *
 * <p>交施工引擎的几段（整地、挡块、模具、拆模具）按动作推进：浇筑被生存需求打断时施工跟着暂停、松手，
 * 先到安全处再停、提前结束时的收尾、此刻能不能打断都转给它，它交代的事实并进浇筑的结果。
 */
final class PortalLavaCasting extends PhasedTask<PortalLavaCasting.Phase> {

    /** 备桶 → 备水 → 备石料 → 整地 → 逐步浇 → 拆模具 → 岩浆倒回池子。 */
    enum Phase { SUPPLY_BUCKET, SUPPLY_WATER, SUPPLY_STONES, PREPARE_SITE, STEP, REMOVE_MOLD, RETURN_LAVA }

    /** 刚做完的小动作是哪一类：做完后怎么记账、要不要回到同一步接着做。 */
    private enum Pending { OBTAIN, BUILD, PLACE_WATER, DIG, SCOOP_WATER, SCOOP_LAVA, POUR_LAVA, RETURN_LAVA }

    static final String BUCKET = "minecraft:bucket";
    static final String WATER_BUCKET = "minecraft:water_bucket";
    static final String LAVA_BUCKET = "minecraft:lava_bucket";
    /** 门内的流水最多等多久退净；没退净照样往下走，门内有水点不着由验框说。 */
    static final long DRAIN_TICKS = 200;
    /** 收水时水源没了最多补几次水。 */
    private static final int WATER_REFILLS = 1;
    /** 舀岩浆接连舀不到几次就停：池边站不住、源格都被浇成了黑曜石。 */
    private static final int SCOOP_FAILURES = 3;
    /** 整段浇筑的预算：备料可能走一趟水边，十桶各走一个来回。 */
    private static final long MAX_TICKS = 20L * 60 * 15;
    /** 舀岩浆时找源格最远看多远（格）：池边几步之内，不跑去别的池。 */
    private static final int SOURCE_REACH = 8;

    private final LavaPoolSurvey.Candidate site;
    private final PortalCastingLayout layout;
    private final Permissions permissions;
    private final DimensionTravelServices services;
    private final ConstructionServices construction;
    private final Deque<PortalCastingLayout.Step> steps = new ArrayDeque<>();
    /** 舀不到的源格：站不过去、舀了没舀到，下次换一格。 */
    private final Set<BlockPos> badSources = new HashSet<>();
    private PortalCastingLayout.Step current;
    private Pending pending;
    private Action prepared;
    /** 交施工引擎的那段子施工（整地、挡块、模具、拆模具）；没在做为 null。 */
    private ChildTaskAction building;
    private BucketUse use;
    private BlockState before;
    private int waterRefills;
    private int scoopFailures;
    private long drainStarted = -1;
    private BlockPos lavaSource;
    private int obsidianCast;
    private Problem stopReason;
    private boolean givingUp;

    /**
     * @param site         勘察挑出的池岸
     * @param permissions  这次出行的许可：挖填站台、放石头都受它管
     * @param services     跨维度的现场部件：拿东西、身上有什么
     * @param construction 按许可拼好的施工服务：靠近、换手、出手、挖、记账
     */
    PortalLavaCasting(LavaPoolSurvey.Candidate site, Permissions permissions, DimensionTravelServices services,
            ConstructionServices construction) {
        super("浇筑建门", Phase.SUPPLY_BUCKET, new ProgressTracker(20L * 60, MAX_TICKS));
        this.site = site;
        this.layout = site.layout();
        this.permissions = permissions;
        this.services = services;
        this.construction = construction;
        steps.addAll(layout.steps());
    }

    @Override
    protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case SUPPLY_BUCKET -> supplyBucket(context);
            case SUPPLY_WATER -> supplyWater(context);
            case SUPPLY_STONES -> supplyStones(context);
            case PREPARE_SITE -> prepareSite(context);
            case STEP -> step(context);
            case REMOVE_MOLD -> removeMold(context);
            case RETURN_LAVA -> returnLava(context);
        };
    }

    /** 备桶：身上有桶（空的、装水的、装岩浆的都算）就往下走，没有按 M4 去弄一只。 */
    private Next<Phase> supplyBucket(TickContext context) {
        if (action() != null) {
            return afterSupply(context, Phase.SUPPLY_BUCKET, "弄一只桶");
        }
        if (carried(BUCKET) + carried(WATER_BUCKET) + carried(LAVA_BUCKET) > 0) {
            return Next.go(Phase.SUPPLY_WATER, "有桶了，去备一桶水");
        }
        prepared = obtain(BUCKET, 1, "浇筑用的桶");
        return Next.go(Phase.SUPPLY_BUCKET, "身上没有桶，先弄一只");
    }

    /** 备水：有水桶就往下走；桶里装着岩浆先倒回池子；空桶按 M4 去水边舀（舀了会自己补回来的水）。 */
    private Next<Phase> supplyWater(TickContext context) {
        if (action() != null) {
            return afterSupply(context, Phase.SUPPLY_WATER, "备一桶水");
        }
        if (carried(WATER_BUCKET) > 0) {
            return Next.go(Phase.SUPPLY_STONES, "水备好了，看石料够不够");
        }
        if (carried(LAVA_BUCKET) > 0) {
            Optional<BlockPos> source = pickLavaSource();
            if (source.isEmpty()) {
                return Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "桶里装着岩浆，池边又找不到能倒回去的岩浆源，腾不出桶装水",
                        "先把岩浆桶倒掉或另弄一只桶再来"));
            }
            pending = Pending.RETURN_LAVA;
            prepared = use = pourLava(source.get(), handBecomes(Items.BUCKET), "岩浆倒回池子");
            return Next.go(Phase.SUPPLY_WATER, "桶里是岩浆，先倒回池子腾出桶");
        }
        prepared = obtain(WATER_BUCKET, 1, "浇筑用的水");
        return Next.go(Phase.SUPPLY_WATER, "拿空桶去舀一桶水");
    }

    /** 备石料：挡块一块、模具六块、要填的站台格数；身上不怕火的整块不够就按圆石去要。 */
    private Next<Phase> supplyStones(TickContext context) {
        if (action() != null) {
            return afterSupply(context, Phase.SUPPLY_STONES, "备石料");
        }
        int needed = PortalCastingLayout.STONES_FOR_MOLD + site.bankFill().size();
        int have = CastingStones.carried(stacks());
        if (have >= needed) {
            return Next.go(Phase.PREPARE_SITE, "石料够了，去整地");
        }
        if (pending == Pending.OBTAIN) {
            // 刚去要过还是不够：不再绕第二圈，如实缺东西结束。
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "浇筑要 " + needed + " 块不怕火的整块石料（挡块、模具、填台），身上只有 " + have + " 块，去要也没凑齐",
                    "先弄些圆石、泥土再来建门"));
        }
        pending = Pending.OBTAIN;
        prepared = obtain(CastingStones.COBBLESTONE, needed - have, "浇筑模具");
        return Next.go(Phase.SUPPLY_STONES, "石料差 " + (needed - have) + " 块，去要圆石");
    }

    /** 备料动作的收尾：做完回到本阶段重新核对；弄不到按缺东西结束，写清是为浇筑要的。 */
    private Next<Phase> afterSupply(TickContext context, Phase again, String what) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                if (pending == Pending.RETURN_LAVA) {
                    pending = null;
                    if (carried(LAVA_BUCKET) > 0) {
                        yield Next.fail(Problem.of(Problem.Kind.NEED_ITEM, "岩浆桶倒回池子后桶里还是岩浆，腾不出桶装水",
                                "先把岩浆桶倒掉或另弄一只桶再来"));
                    }
                }
                yield Next.go(again, what + "做完，再核对一遍");
            }
            case ActionStatus.Failed failed -> Next.fail(Problem.of(
                    failed.problem().kind() == Problem.Kind.NEED_ITEM ? Problem.Kind.NEED_ITEM : failed.problem().kind(),
                    "浇筑建门" + what + "没做成：" + failed.problem().message(), failed.problem().suggestion()));
        };
    }

    /** 整地：站台要填的格放石料、净空格清空，编成一份蓝图交施工引擎；没什么要做就直接开浇。 */
    private Next<Phase> prepareSite(TickContext context) {
        if (action() == null) {
            List<PlannedCell> cells = new ArrayList<>(CastingStones.assign(site.bankFill(), stacks()));
            for (BlockPos at : layout.cellsToClear()) {
                Optional<BlockState> state = read(at);
                // 只清实块：流体格交给原版自己流，挖水挖岩浆没有意义。
                if (state.isPresent() && !state.get().isAir() && state.get().getFluidState().isEmpty()) {
                    cells.add(PlannedCell.air(at));
                }
            }
            if (cells.isEmpty()) {
                return Next.go(Phase.STEP, "岸边不用整，直接开浇");
            }
            // 要整就编成蓝图交施工引擎，下一刻起按动作推进：吃饭、自卫插进来时整地跟着停下松手。
            prepared = construct(cells, "浇筑整地");
            return Next.go(Phase.PREPARE_SITE, "交施工引擎整地");
        }
        return afterConstruction(context, Phase.STEP, "整地做完，开浇");
    }

    /** 逐步浇：手上有动作（挡块与模具的子施工，或倒水、舀岩浆这类小动作）先推它，没有就按步序定下一步。 */
    private Next<Phase> step(TickContext context) {
        if (action() != null) {
            return afterAction(context);
        }
        if (current == null) {
            if (steps.isEmpty()) {
                return Next.go(Phase.REMOVE_MOLD, "浇完了，拆模具");
            }
            current = steps.poll();
        }
        return plan(context);
    }

    /** 当前这一步该做什么：按现场状态跳过已成的，备下一个小动作。 */
    private Next<Phase> plan(TickContext context) {
        BlockPos at = current.cell();
        return switch (current.kind()) {
            case PLACE -> {
                if (startDone()) {
                    // 起手行与底行都已是黑曜石（上次浇了一半）：挡块、起手水那几步不用再做，直接砌模具。
                    while (!steps.isEmpty() && steps.peek().kind() != PortalCastingLayout.Step.Kind.MOLD) {
                        steps.poll();
                    }
                    recordProgress("起手已成，直接砌模具");
                    yield finished();
                }
                Optional<BlockState> state = read(at);
                if (state.isPresent() && solid(state.get())) {
                    yield finished();
                }
                yield buildStep(CastingStones.assign(List.of(at), stacks()), "浇筑挡块");
            }
            case MOLD -> {
                List<BlockPos> cells = new ArrayList<>();
                for (BlockPos cell : layout.mold()) {
                    Optional<BlockState> state = read(cell);
                    if (state.isEmpty() || !solid(state.get())) cells.add(cell);
                }
                if (cells.isEmpty()) {
                    afterSubConstruction();
                    yield Next.stay();
                }
                yield buildStep(CastingStones.assign(cells, stacks()), "浇筑模具");
            }
            case POUR_WATER -> pourWater(at);
            case DIG -> {
                Optional<BlockState> state = read(at);
                if (state.isEmpty() || !solid(state.get())) {
                    yield finished();
                }
                yield dig(at, "挖掉挡块");
            }
            case CAST -> cast(at);
            case SCOOP_WATER -> scoopWater(at);
            case DRAIN -> drain(context);
        };
    }

    /** 倒水：那格已是水源跳过；水桶空了（收水时源格变了）按 M4 再要一桶，只补一次。 */
    private Next<Phase> pourWater(BlockPos at) {
        Optional<BlockState> state = read(at);
        if (state.isPresent() && state.get().getFluidState().isSourceOfType(Fluids.WATER)) {
            return finished();
        }
        if (carried(WATER_BUCKET) == 0) {
            if (waterRefills >= WATER_REFILLS) {
                return stop(Problem.of(Problem.Kind.NEED_ITEM, "浇筑的水没了，补过一次又没了", "带一桶水再来建门"));
            }
            waterRefills++;
            pending = Pending.OBTAIN;
            prepared = obtain(WATER_BUCKET, 1, "浇筑用的水");
            return Next.go(Phase.STEP, "水桶空了，再去舀一桶");
        }
        before = state.orElse(Blocks.AIR.defaultBlockState());
        pending = Pending.PLACE_WATER;
        prepared = use = new BucketUse(construction, permissions, BucketUse.Kind.POUR, at, WATER_BUCKET,
                Blocks.WATER.defaultBlockState(), InteractionConfirmation.blockChanged(at, before), "倒水");
        return Next.go(Phase.STEP, "倒水到 " + at.toShortString());
    }

    /** 收水：只收仍在的那格水源——源格变了是现场事实，不跑去旁边取另一桶冒充收回。 */
    private Next<Phase> scoopWater(BlockPos at) {
        Optional<BlockState> state = read(at);
        if (state.isEmpty() || !state.get().getFluidState().isSourceOfType(Fluids.WATER)) {
            recordAttempt("收水 " + at.toShortString(), "那格已不是水源（流走或被盖住），水留在现场");
            return finished();
        }
        if (carried(BUCKET) == 0) {
            recordAttempt("收水 " + at.toShortString(), "身上没有空桶，水留在现场");
            return finished();
        }
        pending = Pending.SCOOP_WATER;
        prepared = use = new BucketUse(construction, permissions, BucketUse.Kind.SCOOP, at, BUCKET, null,
                handBecomes(Items.WATER_BUCKET), "收水");
        return Next.go(Phase.STEP, "把水收回桶里");
    }

    /** 等门内退水：退净或到时就往下走，没退净点火时由验框说。 */
    private Next<Phase> drain(TickContext context) {
        if (drainStarted < 0) drainStarted = context.gameTick();
        boolean dry = true;
        for (BlockPos cell : layout.frame().interior()) {
            Optional<BlockState> state = read(cell);
            if (state.isPresent() && !state.get().getFluidState().isEmpty()) dry = false;
        }
        if (dry || context.gameTick() - drainStarted >= DRAIN_TICKS) {
            if (!dry) recordAttempt("等门内退水", DRAIN_TICKS + " 刻还没退净，门内有水点火时由验框说");
            return finished();
        }
        return Next.stay();
    }

    /**
     * 浇一格：已是黑曜石跳过；是石头先挖掉；是岩浆或旁边没水不浇（倒岩浆进岩浆什么都不变，
     * 没水碰着只会流岩浆）；桶里没岩浆先去池里舀；然后面向那格倒，水碰到即成黑曜石。
     */
    private Next<Phase> cast(BlockPos at) {
        Optional<BlockState> state = read(at);
        if (state.isEmpty()) {
            recordAttempt("浇 " + at.toShortString(), "那格没加载，留给验框");
            return finished();
        }
        BlockState live = state.get();
        if (live.is(Blocks.OBSIDIAN)) {
            return finished();
        }
        if (solid(live)) {
            return dig(at, "挖掉要浇那格里的石头");
        }
        if (!live.getFluidState().isEmpty() && live.getFluidState().getType().isSame(Fluids.LAVA)) {
            recordAttempt("浇 " + at.toShortString(), "那格还是岩浆，没有水碰它，再倒岩浆什么都不变；留给验框");
            return finished();
        }
        boolean watered = live.getFluidState().getType().isSame(Fluids.WATER) || (live.isAir() && waterBeside(at));
        if (!watered) {
            recordAttempt("浇 " + at.toShortString(), "没有水碰着这格，浇了只会流岩浆；留给验框");
            return finished();
        }
        if (carried(LAVA_BUCKET) == 0) {
            Optional<BlockPos> source = pickLavaSource();
            if (source.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NOT_FOUND, "池边舀不到岩浆源了（都浇成了黑曜石或站不过去）",
                        "换一段池岸或带几桶岩浆再来"));
            }
            lavaSource = source.get();
            pending = Pending.SCOOP_LAVA;
            prepared = use = new BucketUse(construction, permissions, BucketUse.Kind.SCOOP, lavaSource, BUCKET, null,
                    handBecomes(Items.LAVA_BUCKET), "舀岩浆");
            return Next.go(Phase.STEP, "桶里没岩浆，去池里舀一桶");
        }
        before = live;
        pending = Pending.POUR_LAVA;
        prepared = use = pourLava(at, becomesObsidian(at), "浇岩浆");
        return Next.go(Phase.STEP, "浇 " + at.toShortString());
    }

    private Next<Phase> dig(BlockPos at, String what) {
        Optional<Action> action = construction.digs() == null ? Optional.empty() : construction.digs().dig(at);
        if (action.isEmpty()) {
            recordAttempt(what + " " + at.toShortString(), "没有挖方块的办法，留给验框");
            return finished();
        }
        pending = Pending.DIG;
        prepared = action.get();
        return Next.go(Phase.STEP, what);
    }

    /** 小动作做完或没做成：按它是哪一类记账，决定回到同一步还是下一步。 */
    private Next<Phase> afterAction(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) {
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt(action().describe(), failed.problem().message());
            return afterFailed(failed.problem());
        }
        BlockPos at = current.cell();
        switch (pending) {
            case OBTAIN -> {
                // 要到东西了：回到同一步接着做。
            }
            case BUILD -> {
                // 挡块或模具的施工收场：施工自己失败（缺料、受保护）只记一笔，不为一格停整扇门；立着的模具记临时方块。
                TaskResult built = building.settle();
                building = null;
                if (built.status() == TaskResult.Status.FAILED) {
                    recordAttempt("浇筑的子施工", built.problem().message());
                }
                afterSubConstruction();
            }
            case PLACE_WATER -> {
                BlockState now = read(at).orElse(before);
                recordChange(new Change(Change.Kind.BLOCK_CHANGED, blockId(now), 1,
                        "在 " + at.toShortString() + " 倒了水，现在是 " + now.getBlock().getName().getString()));
                finished();
            }
            case DIG -> {
                if (current.kind() == PortalCastingLayout.Step.Kind.DIG) finished();
                // 浇格前的挖：回到同一步，水流进来再浇。
            }
            case SCOOP_WATER -> {
                if (carried(WATER_BUCKET) > 0) {
                    recordChange(new Change(Change.Kind.ITEM_GAINED, WATER_BUCKET, 1,
                            "从 " + at.toShortString() + " 收回了水"));
                } else {
                    recordAttempt("收水 " + at.toShortString(), "舀了一下桶里没水，水留在现场");
                }
                finished();
            }
            case SCOOP_LAVA -> {
                if (carried(LAVA_BUCKET) > 0) {
                    scoopFailures = 0;
                } else {
                    badSources.add(lavaSource);
                    recordAttempt("舀岩浆 " + lavaSource.toShortString(), "舀了一下桶里没岩浆，换一格源");
                    if (++scoopFailures >= SCOOP_FAILURES) {
                        return stop(Problem.of(Problem.Kind.NOT_FOUND, "接连 " + SCOOP_FAILURES + " 次都没舀到岩浆",
                                "换一段池岸或带几桶岩浆再来"));
                    }
                }
            }
            case POUR_LAVA -> {
                settlePour(at);
                finished();
            }
            case RETURN_LAVA -> finished();
        }
        pending = null;
        // 换回本阶段：做完的小动作由阶段切换收掉，下一刻按步序接着定。
        return Next.go(Phase.STEP, current == null ? "这一步做完" : "回到同一步接着做");
    }

    /** 倒岩浆之后看现场：成了黑曜石记一笔进展；成了别的如实记变化，这格不再倒第二桶。 */
    private void settlePour(BlockPos at) {
        BlockState now = read(at).orElse(before);
        InteractionVerdict verdict = use == null || use.interaction() == null ? null : use.interaction().verdict();
        if (now.is(Blocks.OBSIDIAN)) {
            obsidianCast++;
            recordChange(new Change(Change.Kind.BLOCK_CHANGED, "minecraft:obsidian", 1,
                    "在 " + at.toShortString() + " 浇出一格黑曜石"));
            recordProgress("浇出第 " + obsidianCast + " 格黑曜石");
            return;
        }
        if (!now.equals(before)) {
            recordChange(new Change(Change.Kind.BLOCK_CHANGED, blockId(now), 1,
                    "在 " + at.toShortString() + " 倒了岩浆，没成黑曜石而是 " + now.getBlock().getName().getString()
                            + "；这格不再倒第二桶，留给验框"));
            return;
        }
        if (verdict == InteractionVerdict.APPLIED || verdict == InteractionVerdict.NOT_APPLIED || verdict == null) {
            recordAttempt("浇 " + at.toShortString(), "倒了一下那格没变（" + (verdict == null ? "没有结论" : use.interaction().scene())
                    + "），不再倒第二桶，留给验框");
        } else {
            recordUnconfirmed(new Change(Change.Kind.ITEM_CONSUMED, LAVA_BUCKET, 1,
                    at.toShortString() + "：" + use.interaction().scene()));
        }
    }

    /** 小动作没做成：走不到池边、舀不到就停下拆模具；别的记下继续下一步，不为一格停整扇门。 */
    private Next<Phase> afterFailed(Problem problem) {
        Pending failed = pending;
        pending = null;
        if (failed == Pending.OBTAIN) {
            return stop(Problem.of(Problem.Kind.NEED_ITEM, "浇筑的水没了，再要一桶也没要到：" + problem.message(),
                    "带一桶水再来建门"));
        }
        if ((failed == Pending.SCOOP_LAVA || failed == Pending.POUR_LAVA) && problem.kind() == Problem.Kind.UNREACHABLE) {
            return stop(Problem.of(Problem.Kind.UNREACHABLE, "池边站不过去：" + problem.message(), problem.suggestion()));
        }
        if (failed == Pending.SCOOP_LAVA) {
            badSources.add(lavaSource);
            if (++scoopFailures >= SCOOP_FAILURES) {
                return stop(Problem.of(Problem.Kind.NOT_FOUND, "接连 " + SCOOP_FAILURES + " 次都没舀到岩浆：" + problem.message(),
                        "换一段池岸或带几桶岩浆再来"));
            }
            return Next.go(Phase.STEP, "这格舀不到，换一格源再舀");
        }
        finished();
        return Next.go(Phase.STEP, "这一步没做成，记下继续下一步");
    }

    /** 当前这一步做完了，换下一步。 */
    private Next<Phase> finished() {
        current = null;
        return Next.stay();
    }

    /** 浇不下去了：记下原因，先拆模具、倒回岩浆再收场。 */
    private Next<Phase> stop(Problem why) {
        if (stopReason == null) stopReason = why;
        current = null;
        steps.clear();
        return Next.go(Phase.REMOVE_MOLD, "浇不下去了，先拆模具");
    }

    /** 一步里的子施工做完（挡块或模具）：立着的模具格记成临时方块，任何终态都会收回；这一步就算做完。 */
    private void afterSubConstruction() {
        for (BlockPos cell : layout.mold()) {
            Optional<BlockState> state = read(cell);
            if (state.isPresent() && solid(state.get())) {
                construction.ledger().temporaryPlaced(cell, blockId(state.get()), "浇筑模具");
            }
        }
        finished();
    }

    /** 拆模具：还立着的模具格编成清空蓝图交施工引擎，它收回方块、销掉临时方块账。 */
    private Next<Phase> removeMold(TickContext context) {
        if (action() == null) {
            List<PlannedCell> cells = new ArrayList<>();
            for (BlockPos cell : layout.mold()) {
                Optional<BlockState> state = read(cell);
                if (state.isPresent() && solid(state.get()) && !state.get().is(Blocks.OBSIDIAN)) {
                    cells.add(PlannedCell.air(cell));
                }
            }
            if (cells.isEmpty()) {
                return Next.go(Phase.RETURN_LAVA, "没有模具要拆");
            }
            // 拆模具也交施工引擎按动作推进：被打断时跟着停，浇筑提前结束时它收尾并交代收回了几块。
            prepared = construct(cells, "拆浇筑模具");
            return Next.go(Phase.REMOVE_MOLD, "交施工引擎拆模具");
        }
        return afterConstruction(context, Phase.RETURN_LAVA, "模具拆了");
    }

    /** 收尾：桶里还剩岩浆就倒回舀它的那格池子，倒不回如实写；然后交结果。 */
    private Next<Phase> returnLava(TickContext context) {
        if (action() != null) {
            ActionStatus status = runAction(context);
            if (status instanceof ActionStatus.Running) return Next.stay();
            if (status instanceof ActionStatus.Failed failed) {
                recordAttempt("岩浆倒回池子", failed.problem().message());
            } else if (carried(LAVA_BUCKET) > 0) {
                recordAttempt("岩浆倒回池子", "倒了一下桶里还是岩浆，带着走");
            }
            return Next.done(result());
        }
        if (carried(LAVA_BUCKET) > 0) {
            if (givingUp) {
                // 卡住收场：再走去池边可能又卡住，岩浆桶带着走，如实写明。
                recordAttempt("岩浆倒回池子", "卡住收场，不再走去池边，岩浆桶带着走");
                return Next.done(result());
            }
            Optional<BlockPos> source = lavaSource != null && !badSources.contains(lavaSource)
                    ? Optional.of(lavaSource) : pickLavaSource();
            if (source.isPresent()) {
                prepared = use = pourLava(source.get(), handBecomes(Items.BUCKET), "岩浆倒回池子");
                return Next.go(Phase.RETURN_LAVA, "桶里还剩岩浆，倒回池子");
            }
            recordAttempt("岩浆倒回池子", "池边找不到能倒回去的源格，岩浆桶带着走");
        }
        return Next.done(result());
    }

    @Override
    protected Next<Phase> beforeGivingUp(Problem problem) {
        // 卡住要收场：先把模具拆了、岩浆倒回去，再按卡住交代；善后阶段里再卡住就照常收场。
        if (givingUp || phase() == Phase.REMOVE_MOLD || phase() == Phase.RETURN_LAVA) return null;
        givingUp = true;
        // 手上那段施工（整地、挡块、模具）随换阶段按取消收尾，它交代的已放、已挖照样并进浇筑的结果，再去拆模具。
        building = null;
        pending = null;
        return stop(problem);
    }

    private TaskResult result() {
        String summary = "浇筑做完：浇出 " + obsidianCast + " 格黑曜石，门框成型没成型由验框说";
        if (stopReason == null) {
            return TaskResult.done(summary);
        }
        TaskResult.Status status = stopReason.kind() == Problem.Kind.NEED_ITEM
                ? TaskResult.Status.FAILED : TaskResult.Status.PARTIAL;
        return TaskResult.builder(status, "浇筑没浇完：浇出 " + obsidianCast + " 格黑曜石，" + stopReason.message())
                .problem(stopReason).build();
    }

    /**
     * 把一份蓝图编成交施工引擎的子施工动作：填台、挡块、模具、拆模具都走它。
     * 包成动作交给基类：浇筑被打断时施工跟着暂停，浇筑提前结束时它按取消收尾，事实记进浇筑的结果。
     */
    private ChildTaskAction construct(List<PlannedCell> cells, String purpose) {
        Blueprint blueprint = new Blueprint(services.world().currentDimension(), layout.origin(), cells);
        building = new ChildTaskAction(new ConstructionTask(new ConstructionInput(blueprint, permissions, purpose),
                construction), records());
        return building;
    }

    /** 一步里的子施工（挡块、模具）：备成这一步的动作，下一刻起推进，收场后回到这一步收账。 */
    private Next<Phase> buildStep(List<PlannedCell> cells, String purpose) {
        pending = Pending.BUILD;
        prepared = construct(cells, purpose);
        return Next.go(Phase.STEP, purpose);
    }

    /** 整地、拆模具这类整段施工收场：施工自己失败（缺料、受保护）照它的问题失败，做完换到下一阶段。 */
    private Next<Phase> afterConstruction(TickContext context, Phase next, String why) {
        // 施工还在做（走近、放块、挖格）：它的真实进展已由动作报给浇筑。
        if (runAction(context) instanceof ActionStatus.Running) {
            return Next.stay();
        }
        TaskResult result = building.settle();
        building = null;
        if (result.status() == TaskResult.Status.FAILED) {
            return Next.fail(result.problem());
        }
        return Next.go(next, why);
    }

    private BucketUse pourLava(BlockPos at, InteractionConfirmation confirmation, String what) {
        return new BucketUse(construction, permissions, BucketUse.Kind.POUR, at, LAVA_BUCKET,
                Blocks.LAVA.defaultBlockState(), confirmation, what);
    }

    /**
     * 池里挑一格舀：从起手行连通、同层、加载着的岩浆源里挑离站台最近的，模板范围内的不舀；
     * 池面都浇成了黑曜石时再在门址周围几格里找。
     */
    Optional<BlockPos> pickLavaSource() {
        Set<BlockPos> footprint = layout.footprint();
        BlockPos center = layout.cell(0, 0, 2);
        Set<BlockPos> visited = new LinkedHashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>(layout.startRow());
        BlockPos best = null;
        while (!pending.isEmpty()) {
            BlockPos at = pending.removeFirst();
            if (!visited.add(at) || at.distManhattan(layout.origin()) > SOURCE_REACH * 2) continue;
            Optional<BlockState> state = read(at);
            if (state.isEmpty() || !LavaPoolSurvey.lavaSource(state.get())) continue;
            if (!footprint.contains(at) && !badSources.contains(at)
                    && (best == null || at.distSqr(center) < best.distSqr(center))) {
                best = at;
            }
            for (Direction side : Direction.Plane.HORIZONTAL) pending.add(at.relative(side));
        }
        if (best != null) return Optional.of(best);
        for (BlockPos at : BlockPos.betweenClosed(layout.origin().offset(-SOURCE_REACH, -2, -SOURCE_REACH),
                layout.origin().offset(SOURCE_REACH, 2, SOURCE_REACH))) {
            if (footprint.contains(at) || badSources.contains(at)) continue;
            Optional<BlockState> state = read(at);
            if (state.isEmpty() || !LavaPoolSurvey.lavaSource(state.get())) continue;
            if (best == null || at.distSqr(center) < best.distSqr(center)) best = at.immutable();
        }
        return Optional.ofNullable(best);
    }

    /** 两侧底脚与底行都已是黑曜石、门内两格不再是岩浆：上次浇了一半，这次从模具接着做。 */
    private boolean startDone() {
        for (BlockPos cell : layout.startFrameCells()) {
            Optional<BlockState> state = read(cell);
            if (state.isEmpty() || !state.get().is(Blocks.OBSIDIAN)) return false;
        }
        for (BlockPos cell : layout.startInterior()) {
            Optional<BlockState> state = read(cell);
            if (state.isEmpty() || state.get().getFluidState().getType().isSame(Fluids.LAVA)) return false;
        }
        return true;
    }

    /** 这格五个方向（不算脚下）有没有水贴着：有才浇得成黑曜石。 */
    private boolean waterBeside(BlockPos at) {
        for (Direction side : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            Optional<BlockState> state = read(at.relative(side));
            if (state.isPresent() && state.get().getFluidState().getType().isSame(Fluids.WATER)) return true;
        }
        return false;
    }

    private Action obtain(String itemId, int count, String purpose) {
        return services.obtain().actionFor(new ItemRequest(WantedItem.ofItem(itemId), count, purpose), permissions, records());
    }

    private int carried(String itemId) {
        return construction.site().carried(itemId);
    }

    private List<BackpackStack> stacks() {
        return services.world().carriedStacks();
    }

    private Optional<BlockState> read(BlockPos at) {
        return construction.site().loaded(at) ? Optional.ofNullable(construction.site().state(at)) : Optional.empty();
    }

    /** 实块：不是空气也不是流体。 */
    private static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** 倒岩浆的确认：那格变成黑曜石才算浇成；成了别的或没变由收尾看现场。 */
    private static InteractionConfirmation becomesObsidian(BlockPos at) {
        BlockPos frozen = at.immutable();
        return context -> !context.level().isLoaded(frozen) ? InteractionConfirmation.Verdict.PENDING
                : context.level().getBlockState(frozen).is(Blocks.OBSIDIAN)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
    }

    /** 舀与倒回的确认：主手变成这种桶。 */
    private static InteractionConfirmation handBecomes(Item bucket) {
        return context -> context.localPlayer().getMainHandItem().is(bucket)
                ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case SUPPLY_BUCKET -> "备桶";
            case SUPPLY_WATER -> "备一桶水";
            case SUPPLY_STONES -> "备石料";
            case PREPARE_SITE -> "整地：填台、清净空";
            case STEP -> current == null ? "浇筑" : switch (current.kind()) {
                case PLACE -> "放挡块";
                case POUR_WATER -> "倒水";
                case DIG -> "挖挡块";
                case CAST -> "浇岩浆";
                case SCOOP_WATER -> "收水";
                case MOLD -> "砌模具";
                case DRAIN -> "等门内退水";
            };
            case REMOVE_MOLD -> "拆模具";
            case RETURN_LAVA -> "岩浆倒回池子";
        };
    }
}
