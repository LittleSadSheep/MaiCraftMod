// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.StackSplit;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用熔炉烧一批：走到熔炉跟前点开 → 没在烧又没燃料就按要烧的件数添燃料 → 把要烧的原料按件数装进投入口（一次一组）
 * → 关上界面在旁边等 → 估摸着烧好了（或炉子灭了）再走过去点开 → 收走成品。没烧完接着等，燃料烧完就补，
 * 投入口烧空了身上还有原料就再装一组；做够了只收回自己放进去的燃料和原料，关上界面再走。
 *
 * <p>等的时候界面关着：吃东西、自卫打断碰不到炉子里的东西，回来照常取；界面被关掉（打断或别的原因）
 * 不当成烧完了，重新走到跟前点开接着照看。炉子亮着就是在烧，按真实进展报，不让任务把等待当成原地打转。
 * 炉子里是别人的东西（投入口是别的原料、产出格是别的成品）就是正忙，不动它：这台里还没放过这次的东西，
 * 就关上界面换记得的下一台同种炉子（近的在前，连第一台最多三台，见 {@link FurnaceChoices}），换过去的那台
 * 走不到、点不开也接着换；都没用上才收场，逐台说清在哪、为什么。已经放过东西的那台不丢下，照旧在那台收场。
 */
final class FurnaceRun implements Action {

    /** 一下点击等游戏确认的期限（刻）。 */
    private static final int CLICK_TIMEOUT_TICKS = 100;
    /** 原版熔炉烧一件的刻数；配方自己写了烧多久时按配方。 */
    private static final int DEFAULT_COOK_TICKS = 200;
    /** 估摸烧好的时刻再多等一会儿：宁可晚回来一点，也别回来看见还差一件又得再等。 */
    private static final int CHECK_MARGIN_TICKS = 20;
    /** 关着界面最少等多久再回来看，最多隔多久回来收一次货（烧得多时也隔一阵收一次、看看燃料够不够）。 */
    private static final int MIN_AWAY_TICKS = 40;
    private static final int MAX_AWAY_TICKS = 20 * 60;
    /** 炉子灭了这么久就提前回去：燃料烧完了或已经烧完。 */
    private static final int UNLIT_GRACE_TICKS = 40;
    /** 炉子亮着时隔多久报一次进展。 */
    private static final int LIT_REPORT_TICKS = 100;
    /** 走过去、点开、装料、收货、收拾的余量：总期限是烧的时间加上它。 */
    private static final int HANDLING_TICKS = 20 * 120;
    /** 换一台炉子多给的时间：走到下一台、点开看一眼。 */
    private static final int SWITCH_TICKS = 20 * 60;
    /** 界面不是被打断关上、而是无缘无故没了，最多重开几次。 */
    private static final int MAX_REOPENS = 3;
    /** 零碎燃料的上限：木棍、树苗一件只烧半件，有木板以上的燃料时不拿它们。 */
    static final int SMALL_FUEL_TICKS = 300;
    /** 一格最多放一组。 */
    private static final int STACK = 64;
    /** 熔炉容器侧：0 投入口、1 燃料槽、2 产出格。 */
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int OUTPUT = 2;

    /** 走过去点开、照看、收拾、关界面、关着界面等。 */
    private enum Stage { VISIT, TEND, TIDY, CLOSE, AWAY }

    /** 一下点击确认之后要做的结算。 */
    @FunctionalInterface
    private interface Settlement {
        ActionStatus settle(PendingMenuAction.Status status);
    }

    /** 分堆装料的一下：点哪一格、哪个键，点完原格、炉格、鼠标上各几件。 */
    private record SplitClick(int slot, int button, int sourceAfter, int destinationAfter, int cursorAfter) {}

    /** 身上的一堆燃料：角色侧第几格、几件、一件烧多少刻。 */
    record FuelStack(int index, int count, int burnTicks) {}

    /** 挑中的燃料：角色侧第几格、放几件。 */
    record FuelPick(int index, int count) {}

    private final RecipeHolder<?> holder;
    private final WorkstationRecipe recipe;
    /** 记得的几台同种炉子，近的在前；眼下用的是其中一台。 */
    private final FurnaceChoices choices;
    private WorldPosition station;
    private BlockPos at;
    /** 到某一台炉子跟前点开的一趟。 */
    private final Function<WorldPosition, StationVisit> visits;
    private final ReadsFuels fuels;
    private final WorldMemory memory;
    private final int targetItems;
    private final int cookTicks;
    /** 总期限：烧的时间加上照看的余量，每换一台炉子再多给一些。 */
    private long giveUpTicks;

    private Stage stage = Stage.VISIT;
    private StationVisit visit;
    private MenuContent menus;
    private MenuSession closingSession;
    /** 关上界面以后是去旁边等（true）还是收场（false）。 */
    private boolean awayAfterClose;
    /** 关上界面以后换下一台炉子：这台正忙，里面又没有这次放的东西。 */
    private boolean moveOnAfterClose;
    private long awayTicks;
    private long awayUntil;
    private long unlitSince = -1;
    private long lastLitReport;
    private int waited;
    /** 背包里实际多出来的成品件数：每次收货按收货前后背包里的数量差记。 */
    private int produced;
    /** 发起任务的记账口：每收一次货记一笔烧出来的东西，结果里的变化能和背包净变化对得上；null 不记。 */
    private final TaskRecords records;
    private int unexpectedCloses;
    private boolean closedByPause;
    /** 自己放进燃料槽的是什么：收拾时只收回这一种，炉子里原有的燃料不拿走。 */
    private String fuelPlaced;
    /** 这次往投入口装过原料：收拾时才收回投入口剩下的。 */
    private boolean inputLoaded;
    private boolean fuelTidied;
    private boolean inputTidied;
    /** 收场的原因（缺料、缺燃料、炉子正忙、背包满了）；一件没收到时按它失败。 */
    private Problem ending;
    private Problem failure;
    private PendingMenuAction pending;
    private Settlement settlement;
    /** 正在分堆装料：一串左右键，点完才算装好。 */
    private List<SplitClick> splitting;
    private int splitIndex;
    private int splitSource;
    private int splitDestination;
    private ItemStack splitKind = ItemStack.EMPTY;
    private String splitWhat = "";

    /**
     * @param stations 记得的同种炉子，近的在前，第一台是挑中的那台；正忙时按次序换下一台
     * @param visits   到某一台炉子跟前点开的一趟
     */
    FurnaceRun(RecipeHolder<?> holder, WorkstationRecipe recipe, List<WorldPosition> stations, int times,
            Function<WorldPosition, StationVisit> visits, ReadsFuels fuels, WorldMemory memory, TaskRecords records) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.choices = new FurnaceChoices(stations);
        this.station = choices.current();
        this.at = new BlockPos(station.x(), station.y(), station.z());
        this.visits = Objects.requireNonNull(visits, "visits");
        this.fuels = Objects.requireNonNull(fuels, "fuels");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.records = records;
        this.targetItems = times * recipe.resultCount();
        this.cookTicks = holder.value() instanceof AbstractCookingRecipe cooking && cooking.getCookingTime() > 0
                ? cooking.getCookingTime() : DEFAULT_COOK_TICKS;
        this.giveUpTicks = (long) times * cookTicks + HANDLING_TICKS;
    }

    /** 这次用的炉子叫什么：熔炉、高炉或烟熏炉，按配方的设施种类说。 */
    private String stove() {
        return recipe.kind().displayName();
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        if (failure != null) {
            return ActionStatus.failed(failure);
        }
        if (++waited > giveUpTicks) {
            return fail(Problem.Kind.STUCK, "用" + stove() + "烧" + recipe.result().describe() + "超时，先收手"
                    + (inputLoaded ? "；" + stove() + "里可能还有这次放进去的原料或烧好的成品" : ""));
        }
        if (pending != null) {
            return awaitClick(tick);
        }
        return switch (stage) {
            case VISIT -> visit(tick);
            case TEND -> tend(tick);
            case TIDY -> tidy(tick);
            case CLOSE -> closeMenu(tick);
            case AWAY -> away(tick);
        };
    }

    // 走到熔炉跟前点开：每次回来都重新走一遍，人可能被挤开、撤离过。
    // 换过去的那台走不到、点不开，里面又没有这次放的东西：记下原因接着换下一台，不在这一台上收场。
    private ActionStatus visit(TickContext tick) {
        if (visit == null) {
            visit = visits.apply(station);
        }
        ActionStatus status = visit.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            Problem why = failed.problem();
            if (!choices.onFirst() && nothingOfOursInside()) {
                return unreachable(why.headline());
            }
            return fail(why.kind(), why.message()
                    + (inputLoaded ? "；" + stove() + "里可能还有这次放进去的原料或烧好的成品" : ""));
        }
        if (status instanceof ActionStatus.Done) {
            menus = visit.menus();
            closingSession = null;
            stage = Stage.TEND;
            return ActionStatus.progressed();
        }
        return status;
    }

    // 界面开着时照看熔炉，一刻只点一下：收成品 → 做够了收拾 → 添燃料 → 装原料 → 关上界面去旁边等。
    private ActionStatus tend(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) {
            return reopen();
        }
        if (splitting != null) {
            return nextSplitClick(tick);
        }
        MenuContent.Reading open = reading.get();
        SlotSnapshot input = open.containerSnapshots().get(INPUT);
        SlotSnapshot output = open.containerSnapshots().get(OUTPUT);
        // 炉子里是别人的东西：不动它。这台里还没放过这次的东西就关上换下一台，放过的照旧如实说正忙。
        if (!output.isEmpty() && !isResult(output.stack())) {
            return busy("产出格里放着" + output.stack().getHoverName().getString());
        }
        if (!input.isEmpty() && !isIngredient(input.stack())) {
            return busy("投入口里在烧" + input.stack().getHoverName().getString());
        }
        if (!output.isEmpty()) {
            return takeOutput(tick, open);
        }
        if (produced >= targetItems) {
            return finish(null);
        }
        boolean fueled = lit(tick) || !open.containerSnapshots().get(FUEL).isEmpty();
        if (input.isEmpty()) {
            // 投入口空了：身上没原料就按做到多少收场；有就先备好燃料再装一组。
            int source = ingredientSource(open);
            if (source < 0) {
                return finish(Problem.of(Problem.Kind.NEED_ITEM,
                        "身上没有要烧的原料了，烧不出" + recipe.result().describe()));
            }
            return fueled ? loadInput(tick, open, source) : addFuel(tick, open);
        }
        // 投入口有料：没在烧又没燃料就补燃料，都齐了关上界面去旁边等。
        return fueled ? leaveToWait(tick, input.count()) : addFuel(tick, open);
    }

    // 装原料：身上一堆配方认的原料分出还要烧的件数放进投入口，一次最多一组。
    private ActionStatus loadInput(TickContext tick, MenuContent.Reading open, int source) {
        ItemStack kind = open.playerSnapshots().get(source).stack();
        int amount = Math.min(Math.min(stillToSmelt(), kind.getCount()), Math.min(kind.getMaxStackSize(), STACK));
        inputLoaded = true;
        return startSplit(tick, open.playerSlotIds().get(source), open.containerSlotIds().get(INPUT), kind, amount,
                "往投入口装" + kind.getHoverName().getString());
    }

    // 添燃料：按还要烧的件数挑一堆最合适的燃料，分出够烧完的件数放进燃料槽；身上没有能烧的就收场说明。
    private ActionStatus addFuel(TickContext tick, MenuContent.Reading open) {
        List<FuelStack> stacks = new ArrayList<>();
        for (int i = 0; i < open.playerSnapshots().size(); i++) {
            SlotSnapshot stack = open.playerSnapshots().get(i);
            // 工具、武器这类一格一件的不烧，这次要烧的原料和烧出来的成品也不烧。
            if (stack.isEmpty() || stack.stack().getMaxStackSize() <= 1
                    || isIngredient(stack.stack()) || isResult(stack.stack())) continue;
            // 燃料表给的是熔炉里烧多久；高炉、烟熏炉里同一件燃料只顶一半。
            int burn = fuels.burnTicks(itemId(stack.stack())) / recipe.kind().fuelBurnDivisor();
            if (burn > 0) stacks.add(new FuelStack(i, stack.count(), burn));
        }
        FuelPick pick = pickFuel(stacks, (long) stillToSmelt() * cookTicks);
        if (pick == null) {
            return finish(Problem.of(Problem.Kind.NEED_ITEM, "身上没有能烧的燃料（工具、要烧的原料和成品不算），烧不了"
                    + recipe.result().describe()));
        }
        ItemStack kind = open.playerSnapshots().get(pick.index()).stack();
        fuelPlaced = itemId(kind);
        return startSplit(tick, open.playerSlotIds().get(pick.index()), open.containerSlotIds().get(FUEL), kind,
                pick.count(), "往燃料槽添" + kind.getHoverName().getString());
    }

    /**
     * 挑燃料：要烧 needTicks 刻。先挑一堆就够烧完的；再挑木板以上的（木棍、树苗一件只烧半件，太零碎）；
     * 同样的里挑烧完浪费最少的（烧两件东西不扔一块煤炭块进去），一样少时挑一件烧得久的（少点几下）。
     * 放几件按够烧完算，一组封顶；身上没有能烧的给 null。
     */
    static FuelPick pickFuel(List<FuelStack> stacks, long needTicks) {
        FuelStack best = null;
        long[] bestKey = null;
        long need = Math.max(1, needTicks);
        for (FuelStack stack : stacks) {
            if (stack.count() <= 0 || stack.burnTicks() <= 0) continue;
            long items = Math.min(stack.count(), ceilDiv(need, stack.burnTicks()));
            long covered = items * stack.burnTicks();
            long[] key = {covered >= need ? 0 : 1, stack.burnTicks() >= SMALL_FUEL_TICKS ? 0 : 1,
                    Math.max(0, covered - need), -stack.burnTicks()};
            if (best == null || Arrays.compare(key, bestKey) < 0) {
                best = stack;
                bestKey = key;
            }
        }
        if (best == null) {
            return null;
        }
        long count = Math.min(Math.min(best.count(), STACK), ceilDiv(need, best.burnTicks()));
        return new FuelPick(best.index(), (int) count);
    }

    // 料和燃料都齐了：关上界面去旁边等，估摸这一炉烧完的时刻再回来；烧得多时隔一阵回来收一次货。
    private ActionStatus leaveToWait(TickContext tick, int loaded) {
        float cooking = tick.player().localPlayer().containerMenu instanceof AbstractFurnaceMenu furnace
                ? furnace.getBurnProgress() : 0f;
        awayTicks = waitTicks(loaded, cooking, cookTicks);
        awayAfterClose = true;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    /** 投入口 loaded 件、手上这件烧了 cooking 成，估摸多久烧完再回来看（有上下限）。 */
    static long waitTicks(int loaded, float cooking, int cookTicks) {
        long left = Math.round((loaded - Math.max(0f, Math.min(1f, cooking))) * cookTicks) + CHECK_MARGIN_TICKS;
        return Math.max(MIN_AWAY_TICKS, Math.min(MAX_AWAY_TICKS, left));
    }

    // 关着界面在旁边等：到了估摸的时刻回去看；炉子灭了一阵（燃料烧完或已经烧完）就提前回去。
    // 炉子亮着就是在烧，隔一阵按真实进展报一次。
    private ActionStatus away(TickContext tick) {
        long now = tick.player().clientTick();
        boolean lit = blockLit(tick);
        if (lit) {
            unlitSince = -1;
        } else if (unlitSince < 0) {
            unlitSince = now;
        }
        if (now >= awayUntil || !lit && now - unlitSince >= UNLIT_GRACE_TICKS) {
            stage = Stage.VISIT;
            return ActionStatus.progressed();
        }
        if (lit && now - lastLitReport >= LIT_REPORT_TICKS) {
            lastLitReport = now;
            return ActionStatus.progressed();
        }
        return ActionStatus.running();
    }

    // 收成品：产出格整堆收进背包，收到几件按收货前后背包里这件东西的数量差记，不按产出格上写的数猜。
    // 真烧出来几件就记几件：自制的东西逐笔记进发起任务的结果，结果里的变化能和背包净变化对得上。
    private ActionStatus takeOutput(TickContext tick, MenuContent.Reading open) {
        int before = carriedResults(open);
        int slotId = open.containerSlotIds().get(OUTPUT);
        return click(tick, slotId, 0, ClickType.QUICK_MOVE, resultTaken(before), status -> {
            int gained = openReading().map(now -> carriedResults(now) - before).orElse(0);
            gained = Math.max(0, gained);
            produced += gained;
            if (gained > 0 && records != null) {
                records.change(Change.of(Change.Kind.ITEM_GAINED, recipe.result().itemId(), gained));
            }
            if (gained <= 0 && status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return finish(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "产出格的" + recipe.result().describe() + "收不进背包，背包可能满了"));
            }
            return ActionStatus.progressed();
        });
    }

    // 收拾：只收回自己放进去的——这次添的那种燃料、这次装的原料；炉子里原有的燃料不拿走。收不回（背包满了）就留着。
    private ActionStatus tidy(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) {
            // 收拾到一半界面没了（被打断）：回去点开再收；无缘无故一再被关就按失败收场。
            return reopen();
        }
        MenuContent.Reading open = reading.get();
        SlotSnapshot fuel = open.containerSnapshots().get(FUEL);
        SlotSnapshot input = open.containerSnapshots().get(INPUT);
        int index = -1;
        if (!fuelTidied && fuelPlaced != null && !fuel.isEmpty() && itemId(fuel.stack()).equals(fuelPlaced)) {
            index = FUEL;
            fuelTidied = true;
        } else if (!inputTidied && inputLoaded && !input.isEmpty() && isIngredient(input.stack())) {
            index = INPUT;
            inputTidied = true;
        }
        if (index < 0) {
            if (produced > 0) {
                memory.rememberWorkstationUsed(station, MenuRecipeRuns.workstationBlockType(recipe.kind()), Instant.now());
            }
            awayAfterClose = false;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }
        int slotId = open.containerSlotIds().get(index);
        ItemStack shown = open.containerSnapshots().get(index).stack().copy();
        return click(tick, slotId, 0, ClickType.QUICK_MOVE, slotChanged(slotId, shown), status -> ActionStatus.progressed());
    }

    // 关界面：打开者负责关；关上以后要么去旁边等，要么收场。关不上也照常往下走，不冒充没开过。
    private ActionStatus closeMenu(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isPresent()) {
            if (closingSession == null) {
                MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
                if (claim instanceof MenuSession.Claim.Owned owned) closingSession = owned.session();
            }
            if (closingSession != null) {
                var closing = closingSession.closeNow(reading.get().channel(), tick.player().clientTick());
                if (!(closing instanceof MenuSession.Closing.Closed || closing instanceof MenuSession.Closing.Failed)) {
                    return ActionStatus.running();
                }
            }
        }
        dropVisit();
        if (moveOnAfterClose) {
            // 正忙的那台关好了：走去下一台，从点开看一眼重新来。
            moveOnAfterClose = false;
            goTo(choices.current());
            return ActionStatus.progressed();
        }
        if (awayAfterClose) {
            long now = tick.player().clientTick();
            awayUntil = now + awayTicks;
            unlitSince = -1;
            lastLitReport = now;
            stage = Stage.AWAY;
            return ActionStatus.progressed();
        }
        if (produced == 0 && ending != null) {
            failure = ending;
            return ActionStatus.failed(failure);
        }
        return ActionStatus.done();
    }

    // 收场：记下原因（为空就是做够了），先收拾自己放进去的再关界面。
    private ActionStatus finish(Problem why) {
        if (why != null) ending = why;
        stage = Stage.TIDY;
        return ActionStatus.progressed();
    }

    // 炉子正忙：不动别人的东西。这台里没有这次放的东西就换下一台；放过的收拾自己放进去的就走。
    // 只记得这一台时照旧一句"正忙"收场，由引擎换别的路。
    private ActionStatus busy(String what) {
        if (nothingOfOursInside() && choices.skip("正忙，" + what)) {
            return moveOn();
        }
        if (choices.tried() > 1 && nothingOfOursInside()) {
            return finish(allSkipped());
        }
        return finish(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, stove() + "正忙：" + what + "，不是这次要烧的",
                "换一台" + stove() + "，或等它烧完再来"));
    }

    // 换过去的那台走不到、点不开（界面没开着）：还有下一台就直接走过去，试满了以逐台说明收场。
    private ActionStatus unreachable(String why) {
        if (choices.skip(why)) {
            giveUpTicks += SWITCH_TICKS;
            goTo(choices.current());
            return ActionStatus.progressed();
        }
        failure = allSkipped();
        return ActionStatus.failed(failure);
    }

    // 正忙的这台界面开着：先关上（不收拾，里面没有自己的东西），关好了再走去下一台。
    private ActionStatus moveOn() {
        giveUpTicks += SWITCH_TICKS;
        moveOnAfterClose = true;
        awayAfterClose = false;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 换到某一台炉子：之后的走过去、点开、看炉子亮不亮、记用过的设施都按这一台。
    private void goTo(WorldPosition next) {
        station = next;
        at = new BlockPos(next.x(), next.y(), next.z());
        dropVisit();
        stage = Stage.VISIT;
    }

    // 试满了都没用上：逐台说清在哪、为什么，一件没烧出来交给引擎换别的路。
    private Problem allSkipped() {
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, choices.summary(stove()),
                "等它们烧完再来，或换别的路（例如就地摆一台新的" + stove() + "）");
    }

    // 这台炉子里没有这次放进去的东西：没装过原料、没添过燃料，丢下它去别处不落下自己的东西。
    private boolean nothingOfOursInside() {
        return !inputLoaded && fuelPlaced == null;
    }

    // 界面没了：被生存需求打断时关的、或别的原因关的，都回到熔炉跟前重新点开接着照看，不当成烧完了。
    // 不是打断关的、一再被关掉才如实失败；分堆点到一半鼠标上的东西，关界面时原版已经还回背包。
    private ActionStatus reopen() {
        if (!closedByPause && ++unexpectedCloses > MAX_REOPENS) {
            return fail(Problem.Kind.STUCK, "熔炉的界面一再被关掉，烧" + recipe.result().describe() + "烧不下去");
        }
        closedByPause = false;
        splitting = null;
        dropVisit();
        stage = Stage.VISIT;
        return ActionStatus.progressed();
    }

    private void dropVisit() {
        if (visit != null) visit.close();
        visit = null;
        menus = null;
        closingSession = null;
    }

    // 分堆：从角色侧一格分出 amount 件放进炉子一格，点得最少的左右键串；每一下等原格、炉格、鼠标上件数对上再点下一下。
    private ActionStatus startSplit(TickContext tick, int sourceSlot, int destinationSlot, ItemStack kind, int amount,
            String what) {
        List<SplitClick> clicks = new ArrayList<>();
        for (StackSplit.Step step : StackSplit.plan(kind.getCount(), amount, kind.getMaxStackSize())) {
            clicks.add(new SplitClick(step.side() == StackSplit.Side.SOURCE ? sourceSlot : destinationSlot,
                    step.button(), step.after().source(), step.after().deposited(), step.after().cursor()));
        }
        splitting = clicks;
        splitIndex = 0;
        splitSource = sourceSlot;
        splitDestination = destinationSlot;
        splitKind = kind.copy();
        splitWhat = what;
        return nextSplitClick(tick);
    }

    private ActionStatus nextSplitClick(TickContext tick) {
        if (splitIndex >= splitting.size()) {
            splitting = null;
            return ActionStatus.progressed();
        }
        SplitClick next = splitting.get(splitIndex);
        return click(tick, next.slot(), next.button(), ClickType.PICKUP, splitLanded(next), status -> {
            if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                splitting = null;
                return fail(Problem.Kind.REFUSED_BY_GAME, splitWhat + "时第 " + (splitIndex + 1) + " 下没得到游戏确认");
            }
            splitIndex++;
            return ActionStatus.progressed();
        });
    }

    // 这一下点完：原格、鼠标上的件数要正好对上；炉格可能刚被炉子吃掉一件（点着火、烧完一件），少一件也算。
    // 炉子自己在动不算对不上，没对上就接着等，到期限还没对上才算这一下没成。
    private MenuConfirmation splitLanded(SplitClick click) {
        return (player, pendingClick) -> {
            var menu = player.localPlayer().containerMenu;
            int destination = countOf(menu.getSlot(splitDestination).getItem());
            boolean source = countOf(menu.getSlot(splitSource).getItem()) == click.sourceAfter();
            boolean cursor = countOf(menu.getCarried()) == click.cursorAfter();
            boolean placed = destination == click.destinationAfter() || destination == click.destinationAfter() - 1;
            return source && cursor && placed ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
        };
    }

    private int countOf(ItemStack stack) {
        return stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, splitKind) ? 0 : stack.getCount();
    }

    // 收货确认：背包里这件东西变多了，或产出格空了。
    private MenuConfirmation resultTaken(int before) {
        return (player, click) -> openReading()
                .map(now -> carriedResults(now) > before || now.containerSnapshots().get(OUTPUT).isEmpty())
                .orElse(false) ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
    }

    // 点到的格子变了（变空、变少或换了东西）才算这一下生效。
    private static MenuConfirmation slotChanged(int slotId, ItemStack before) {
        return (player, click) -> ItemStack.matches(player.localPlayer().containerMenu.getSlot(slotId).getItem(), before)
                ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
    }

    // 点一下格子：别的界面操作没结清或界面还没真正显示时不点，下一刻再看。
    private ActionStatus click(TickContext tick, int slotId, int button, ClickType type,
            MenuConfirmation confirmation, Settlement after) {
        PlayerContext player = tick.player();
        if (player.menuActions().hasPendingTransaction() || !player.menuActions().ensureVisible(player)) {
            return ActionStatus.running();
        }
        pending = player.menuActions().click(player, slotId, button, type, confirmation, CLICK_TIMEOUT_TICKS);
        settlement = after;
        return ActionStatus.progressed();
    }

    // 等这一下点击的结果；有了结果交给它的结算决定下一步。
    private ActionStatus awaitClick(TickContext tick) {
        pending = tick.player().menuActions().poll(tick.player(), pending);
        if (!pending.terminal()) {
            return ActionStatus.running();
        }
        PendingMenuAction.Status status = pending.status();
        Settlement after = settlement;
        pending = null;
        settlement = null;
        return after.settle(status);
    }

    // 还在的界面才读：点开的那份已经关了（就算又开了别的界面）都按界面没了算。
    private Optional<MenuContent.Reading> openReading() {
        return menus == null ? Optional.empty() : menus.current().filter(reading -> reading.channel().stillOpen());
    }

    // 投入口空着时能装的原料：身上第一堆配方认的。
    private int ingredientSource(MenuContent.Reading open) {
        for (int i = 0; i < open.playerSnapshots().size(); i++) {
            SlotSnapshot stack = open.playerSnapshots().get(i);
            if (!stack.isEmpty() && isIngredient(stack.stack())) return i;
        }
        return -1;
    }

    // 还要烧几件原料才做够：按配方单次产出折算。
    private int stillToSmelt() {
        int missing = Math.max(0, targetItems - produced);
        return (int) ceilDiv(missing, Math.max(1, recipe.resultCount()));
    }

    private boolean lit(TickContext tick) {
        return tick.player().localPlayer().containerMenu instanceof AbstractFurnaceMenu furnace && furnace.isLit();
    }

    // 关着界面看不到格子，只看炉子亮不亮：亮着就是在烧。
    private boolean blockLit(TickContext tick) {
        BlockState state = tick.player().level().getBlockState(at);
        return state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
    }

    private int carriedResults(MenuContent.Reading open) {
        int total = 0;
        for (SlotSnapshot stack : open.playerSnapshots()) {
            if (!stack.isEmpty() && isResult(stack.stack())) total += stack.count();
        }
        return total;
    }

    private boolean isResult(ItemStack stack) {
        return itemId(stack).equals(recipe.result().itemId());
    }

    private boolean isIngredient(ItemStack stack) {
        return holder.value().getIngredients().stream().anyMatch(ingredient -> ingredient.test(stack));
    }

    private ActionStatus fail(Problem.Kind kind, String message) {
        failure = Problem.of(kind, message, null);
        return ActionStatus.failed(failure);
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // 被生存需求打断：走到一半、点到一半的先停住；界面开着就关上（炉子接着烧），回来重新走到跟前点开接着照看。
    @Override
    public void pause() {
        boolean menuOpen = openReading().isPresent();
        if (visit != null) visit.pause();
        abandonMenu();
        if (menuOpen) closedByPause = true;
        if (stage == Stage.VISIT) dropVisit();
    }

    // 收尾：没走完的走到、没确认的点开一并收尾，开着的界面请游戏关上。
    @Override
    public void close() {
        if (visit != null) visit.close();
        abandonMenu();
    }

    private void abandonMenu() {
        MenuContent open = menus != null ? menus : visit != null ? visit.menus() : null;
        if (open != null) {
            open.current().filter(reading -> reading.channel().stillOpen())
                    .ifPresent(reading -> reading.channel().requestClose());
        }
    }

    // 点到一半、分堆分到一半时不宜打断：鼠标上可能还拿着东西。
    @Override
    public Interruptibility interruptibility() {
        return pending != null || splitting != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        String progress = "（已收 " + produced + "/" + targetItems
                + (choices.tried() > 1 ? "，换到第 " + choices.tried() + " 台" : "") + "）";
        return stage == Stage.AWAY ? "关着界面等熔炉烧" + recipe.result().describe() + progress
                : "用熔炉烧" + recipe.result().describe() + progress;
    }
}
