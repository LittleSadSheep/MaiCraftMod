// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在工作站上动手做配方的生产实现：走到设施跟前、点开界面，按这种设施在原版里的用法摆料，
 * 产出格一出货就整堆收进背包，做够件数（按背包里实际多出来的算）收拾好再关上界面。
 *
 * <p>三种设施的用法不同，都按原版界面真实的规矩来：
 * <ul>
 *   <li>工作台：经配方簿一次摆一份原料，取走产出再摆下一份；</li>
 *   <li>熔炉：经配方簿一次摆一件原料；配方簿不管燃料，没在烧又没燃料时把身上烧得最久的燃料
 *       拿起来放进燃料槽；做完把剩下的燃料收回背包（熔炉不会自己把东西还回来）；</li>
 *   <li>石切台：配方簿不管石切台。把原料拿到手上，右键一件一件放进投入口，放够要切的件数，
 *       多的放回原格；再按下这条配方的按钮，取走产出。关界面时原版会把投入口剩下的还回背包。</li>
 * </ul>
 * 每一下点击都等游戏确认再点下一下；设施到现场不在、点不开、界面认不出都如实失败，引擎换别的路。
 * 做完把这台设施记成"亲手用过的工作站"。
 */
public final class MenuRecipeRuns implements RecipeRuns {

    /** 等界面打开并同步完的期限（刻）。 */
    private static final int MENU_WAIT_LIMIT = 40;
    /** 烧一件东西要多少刻：原版熔炉的固定速度，总期限按它与次数估。 */
    private static final int SMELT_TICKS_PER_ITEM = 200;
    /** 一下点击等游戏确认的期限（刻）。 */
    private static final int CLICK_TIMEOUT_TICKS = 100;

    /** 合成台界面的类型注册 ID。 */
    private static final String CRAFTING_MENU = "minecraft:crafting";
    /** 熔炉界面的类型注册 ID：烧炼配方只认普通熔炉，高炉、烟熏炉各有自己的配方。 */
    private static final String FURNACE_MENU = "minecraft:furnace";
    /** 石切台界面的类型注册 ID。 */
    private static final String STONECUTTER_MENU = "minecraft:stonecutter";

    // 容器侧格子的次序（原版界面布局）：熔炉 0 投入口、1 燃料槽、2 产出格；石切台 0 投入口、1 产出格；
    // 工作台 0 产出格、1 到 9 合成格。
    private static final int FURNACE_INPUT = 0;
    private static final int FURNACE_FUEL = 1;
    private static final int CUTTER_INPUT = 0;

    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final ReadsFuels fuels;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> context;

    public MenuRecipeRuns(BringsPlayerClose close, Interactions interactions, ReadsFuels fuels,
            WorldMemory memory, Supplier<PlayerContext> context) {
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.fuels = Objects.requireNonNull(fuels, "fuels");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<Action> run(RecipeView recipe, WorldPosition station, int times, Permissions permissions) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return Optional.empty();
        }
        // 配方按注册 ID 认回游戏自己的配方对象：配方簿与石切台按钮都只认这个对象，认不回就动不了手。
        ResourceLocation id = ResourceLocation.tryParse(recipe.id());
        if (id == null) {
            return Optional.empty();
        }
        return level.getRecipeManager().byKey(id)
                .map(holder -> new WorkAction(holder, recipe, station, times, permissions));
    }

    @Override
    public Optional<Action> runAtRememberedStation(RecipeView recipe, int times, Permissions permissions) {
        // 刚就地摆下的那台就是最新记下的：按方块类型从记忆里找记录时刻最新的工作站。
        String blockType = workstationBlockType(recipe.kind());
        return memory.allRecords().stream()
                .filter(record -> record.kind() == MemoryKind.WORKSTATION)
                .filter(record -> blockType.equals(record.blockType()))
                .sorted((a, b) -> b.recordedAt().compareTo(a.recordedAt()))
                .findFirst()
                .flatMap(record -> run(recipe, record.position(), times, permissions));
    }

    /** 一下点击确认之后要做的结算；拿到的是这下点击最后的状态。 */
    @FunctionalInterface
    private interface Settlement {
        ActionStatus settle(PendingMenuAction.Status status, TickContext tick);
    }

    /** 动手做一轮的动作：靠近 → 点开 → 等同步 → 摆料与收货 → 收拾 → 关上，跨刻推进。 */
    private final class WorkAction implements Action {

        /** 推进的先后：靠近、点开、等同步、摆料收货、收拾熔炉里剩下的、关上。 */
        private enum Stage { APPROACH, OPEN, WAIT, WORK, TIDY, CLOSE }

        private final RecipeHolder<?> holder;
        /** 这次任务的许可：走到设施跟前时能动多少地形按它来。 */
        private final Permissions permissions;
        private final RecipeView recipe;
        private final WorldPosition station;
        private final int batches;
        /** 想做出的总件数：按配方的单次产出与次数估。 */
        private final int targetItems;

        private Stage stage = Stage.APPROACH;
        private Action approaching;
        private AimAndInteract opening;
        /** 界面打开后才建的读端：绑的就是点开的那份界面。 */
        private MenuContent menus;
        private int menuWaited;
        private int waited;
        /** 背包里实际多出来的产出件数：每次取货按取货前后背包里的数量差记。 */
        private int produced;
        private Problem failure;
        /** 正在等游戏确认的那一下点击，以及确认后的结算。 */
        private PendingMenuAction pending;
        private Settlement settlement;
        /** 石切台喂料：原料拿在手上时从哪一格拿起来的、还要往投入口放几件；没在喂料时为 -1。 */
        private int feedFrom = -1;
        private int feedLeft;

        WorkAction(RecipeHolder<?> holder, RecipeView recipe, WorldPosition station, int batches,
                Permissions permissions) {
            this.permissions = permissions;
            this.holder = holder;
            this.recipe = recipe;
            this.station = station;
            this.batches = batches;
            this.targetItems = batches * recipe.resultCount();
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            if (failure != null) {
                return ActionStatus.failed(failure);
            }
            if (++waited > giveUpTicks()) {
                return fail(Problem.Kind.STUCK, "在工作站上做" + recipe.result().describe() + "超时，先收手");
            }
            if (pending != null) {
                return awaitClick(tick);
            }
            return switch (stage) {
                case APPROACH -> approach(tick);
                case OPEN -> open(tick);
                case WAIT -> waitMenu();
                case WORK -> work(tick);
                case TIDY -> tidy(tick);
                case CLOSE -> close(tick);
            };
        }

        // 总期限：烧炼按每件的烧制刻数估，合成石切按一次一两刻估，另留动手与收尾的余量。
        private long giveUpTicks() {
            long smelting = recipe.kind() == RecipeView.Kind.SMELTING
                    ? (long) batches * SMELT_TICKS_PER_ITEM : 0;
            return smelting + 20L * 60;
        }

        // 走到设施跟前：够不着就如实失败，不隔空点开。
        private ActionStatus approach(TickContext tick) {
            if (approaching == null) {
                BlockPos at = new BlockPos(station.x(), station.y(), station.z());
                // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
                approaching = close.toward(InteractionTarget.ofBlock(at), permissions);
            }
            ActionStatus status = approaching.tick(tick);
            if (status instanceof ActionStatus.Running) {
                return status;
            }
            if (status instanceof ActionStatus.Failed failed) {
                return fail(Problem.Kind.UNREACHABLE, "走不到工作站跟前做不了" + recipe.result().describe()
                        + "：" + failed.problem().message());
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        // 点开设施：确认条件是界面打开（容器编号变了），点完等界面递过来。
        private ActionStatus open(TickContext tick) {
            if (opening == null) {
                int menuIdBefore = tick.player().localPlayer().containerMenu.containerId;
                BlockPos at = new BlockPos(station.x(), station.y(), station.z());
                opening = interactions.useBlock(at, InteractionConfirmation.menuChanged(menuIdBefore));
            }
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Running) {
                return status;
            }
            if (status instanceof ActionStatus.Failed failed) {
                return fail(Problem.Kind.REFUSED_BY_GAME, "点开工作站没成功，做不了" + recipe.result().describe()
                        + "：" + failed.problem().message());
            }
            stage = Stage.WAIT;
            return ActionStatus.progressed();
        }

        // 等格子同步完，并认下界面类型：认不出的界面不乱点。
        private ActionStatus waitMenu() {
            if (menus == null) {
                // 读端在这里才建：绑的就是刚点开、还没被换掉的这份界面。
                menus = new ClientMenuContent(context);
            }
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                if (++menuWaited > MENU_WAIT_LIMIT) {
                    return fail(Problem.Kind.STUCK,
                            "工作站点开了但界面一直没同步，做不了" + recipe.result().describe());
                }
                return ActionStatus.running();
            }
            if (!menuMatches(reading.get())) {
                return fail(Problem.Kind.UNSUPPORTED,
                        "打开的不是认得的" + workstationName() + "界面，做不了" + recipe.result().describe());
            }
            stage = Stage.WORK;
            return ActionStatus.progressed();
        }

        // 摆料与收货：产出格有货先收；做够了去收拾；否则按设施种类摆下一份料。
        private ActionStatus work(TickContext tick) {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                // 界面被合上了：已出货的照实算数，收场让引擎清点。
                return ActionStatus.done();
            }
            MenuContent.Reading open = reading.get();
            if (feedFrom < 0 && produced >= targetItems) {
                memory.rememberWorkstationUsed(station, workstationBlockType(recipe.kind()), Instant.now());
                stage = Stage.TIDY;
                return ActionStatus.progressed();
            }
            int output = outputIndex();
            if (feedFrom < 0 && !open.containerSnapshots().get(output).isEmpty()) {
                return takeOutput(tick, open, output);
            }
            return switch (recipe.kind()) {
                case CRAFTING -> placeByRecipeBook(tick);
                case SMELTING -> feedFurnace(tick, open);
                case STONECUTTING -> feedStonecutter(tick, open);
            };
        }

        // 取货：整堆收进背包；收到几件按取货前后背包里这件东西的数量差记，不按产出格上写的数猜。
        private ActionStatus takeOutput(TickContext tick, MenuContent.Reading open, int output) {
            int before = carriedResults(open);
            int slotId = open.containerSlotIds().get(output);
            ItemStack shown = open.containerSnapshots().get(output).stack().copy();
            return click(tick, slotId, 0, ClickType.QUICK_MOVE, slotChanged(slotId, shown), (status, later) -> {
                menus.current().ifPresent(now -> produced += Math.max(0, carriedResults(now) - before));
                if (status == PendingMenuAction.Status.CONFIRMED_NOT_APPLIED) {
                    return fail(Problem.Kind.INVENTORY_FULL,
                            "产出格的" + recipe.result().describe() + "收不进背包，背包可能满了");
                }
                return ActionStatus.progressed();
            });
        }

        // 工作台、熔炉的原料经配方簿摆：一次一份，等格子里真的出现原料才算摆上。
        private ActionStatus placeByRecipeBook(TickContext tick) {
            PlayerContext player = tick.player();
            if (!ready(player)) {
                return ActionStatus.running();
            }
            PendingMenuAction placing = player.menuActions().placeRecipe(player, holder, false,
                    ingredientsPlaced(), CLICK_TIMEOUT_TICKS);
            return submit(placing, (status, later) -> status == PendingMenuAction.Status.CONFIRMED_APPLIED
                    ? ActionStatus.progressed()
                    : fail(Problem.Kind.NEED_ITEM, "配方簿没有把原料摆进去（身上可能缺料），做不了"
                            + recipe.result().describe()));
        }

        // 熔炉：投入口空了就再摆一件；有料、没在烧、燃料槽空着时先添燃料；都齐了就等它烧。
        private ActionStatus feedFurnace(TickContext tick, MenuContent.Reading open) {
            if (open.containerSnapshots().get(FURNACE_INPUT).isEmpty()) {
                return placeByRecipeBook(tick);
            }
            boolean lit = tick.player().localPlayer().containerMenu instanceof AbstractFurnaceMenu furnace
                    && furnace.isLit();
            if (lit || !open.containerSnapshots().get(FURNACE_FUEL).isEmpty()) {
                return ActionStatus.running();
            }
            return addFuel(tick, open);
        }

        // 添燃料：把身上烧得最久的一堆拿起来整堆放进燃料槽，剩下的做完再收回；尽量不烧这次要烧的原料。
        private ActionStatus addFuel(TickContext tick, MenuContent.Reading open) {
            ItemStack carried = tick.player().localPlayer().containerMenu.getCarried();
            int fuelSlot = open.containerSlotIds().get(FURNACE_FUEL);
            if (!carried.isEmpty()) {
                return click(tick, fuelSlot, 0, ClickType.PICKUP, cursorEmptied(), (status, later) ->
                        status == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.progressed()
                                : fail(Problem.Kind.REFUSED_BY_GAME, "燃料没能放进熔炉的燃料槽"));
            }
            int best = -1;
            int bestBurn = 0;
            for (int i = 0; i < open.playerSnapshots().size(); i++) {
                SlotSnapshot stack = open.playerSnapshots().get(i);
                if (stack.isEmpty() || isIngredient(stack.stack())) continue;
                int burn = fuels.burnTicks(itemId(stack.stack()));
                if (burn > bestBurn) {
                    best = i;
                    bestBurn = burn;
                }
            }
            if (best < 0) {
                return fail(Problem.Kind.NEED_ITEM, "身上没有能烧的燃料，烧不了" + recipe.result().describe());
            }
            return click(tick, open.playerSlotIds().get(best), 0, ClickType.PICKUP, cursorFilled(), (status, later) ->
                    status == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.progressed()
                            : fail(Problem.Kind.REFUSED_BY_GAME, "拿不起燃料，烧不了" + recipe.result().describe()));
        }

        // 石切台：手上拿着原料就接着往投入口放；投入口空着就去拿原料；料够了还没选配方就按配方按钮。
        private ActionStatus feedStonecutter(TickContext tick, MenuContent.Reading open) {
            if (feedFrom >= 0) {
                return continueFeeding(tick, open);
            }
            if (open.containerSnapshots().get(CUTTER_INPUT).isEmpty()) {
                return pickUpIngredient(tick, open);
            }
            return selectCut(tick);
        }

        // 拿原料：身上第一堆配方认的原料拿到手上，要放几件按还差几次记下。
        private ActionStatus pickUpIngredient(TickContext tick, MenuContent.Reading open) {
            for (int i = 0; i < open.playerSnapshots().size(); i++) {
                SlotSnapshot stack = open.playerSnapshots().get(i);
                if (stack.isEmpty() || !isIngredient(stack.stack())) continue;
                int slotId = open.playerSlotIds().get(i);
                int needed = Math.max(1, (targetItems - produced + recipe.resultCount() - 1) / recipe.resultCount());
                return click(tick, slotId, 0, ClickType.PICKUP, cursorFilled(), (status, later) -> {
                    if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                        return fail(Problem.Kind.REFUSED_BY_GAME, "拿不起石切台要切的原料");
                    }
                    feedFrom = slotId;
                    feedLeft = needed;
                    return ActionStatus.progressed();
                });
            }
            return fail(Problem.Kind.NEED_ITEM, "身上没有石切台要切的原料，切不出" + recipe.result().describe());
        }

        // 喂料：右键一下放一件进投入口；放够了或手上没了，把多的放回原来那一格。
        private ActionStatus continueFeeding(TickContext tick, MenuContent.Reading open) {
            ItemStack carried = tick.player().localPlayer().containerMenu.getCarried();
            int inputSlot = open.containerSlotIds().get(CUTTER_INPUT);
            if (feedLeft > 0 && !carried.isEmpty()) {
                int inputBefore = open.containerSnapshots().get(CUTTER_INPUT).count();
                return click(tick, inputSlot, 1, ClickType.PICKUP, slotGrew(inputSlot, inputBefore), (status, later) -> {
                    if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                        return fail(Problem.Kind.REFUSED_BY_GAME, "原料没能放进石切台的投入口");
                    }
                    feedLeft--;
                    return ActionStatus.progressed();
                });
            }
            if (carried.isEmpty()) {
                feedFrom = -1;
                return ActionStatus.progressed();
            }
            return click(tick, feedFrom, 0, ClickType.PICKUP, cursorEmptied(), (status, later) -> {
                if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                    return fail(Problem.Kind.REFUSED_BY_GAME, "多拿的原料没能放回背包");
                }
                feedFrom = -1;
                return ActionStatus.progressed();
            });
        }

        // 选配方：按这条配方在石切台按钮里的次序按下去，产出格出货才算选上。
        private ActionStatus selectCut(TickContext tick) {
            PlayerContext player = tick.player();
            if (!(player.localPlayer().containerMenu instanceof StonecutterMenu cutter)) {
                return fail(Problem.Kind.UNSUPPORTED, "打开的不是石切台，切不了" + recipe.result().describe());
            }
            List<? extends RecipeHolder<?>> choices = cutter.getRecipes();
            int index = -1;
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).id().equals(holder.id())) index = i;
            }
            if (index < 0) {
                return fail(Problem.Kind.NEED_ITEM, "投入口的原料切不出" + recipe.result().describe());
            }
            if (!ready(player)) {
                return ActionStatus.running();
            }
            int chosen = index;
            MenuConfirmation selected = (who, click) -> who.localPlayer().containerMenu instanceof StonecutterMenu menu
                    && menu.getSelectedRecipeIndex() == chosen && menu.getSlot(1).hasItem()
                    ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
            return submit(player.menuActions().pressButton(player, chosen, selected, CLICK_TIMEOUT_TICKS),
                    (status, later) -> status == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.progressed()
                            : fail(Problem.Kind.REFUSED_BY_GAME, "石切台没有选上切" + recipe.result().describe() + "的配方"));
        }

        // 收拾：熔炉不会把东西还回来，燃料槽和投入口剩下的收回背包；工作台、石切台关界面时原版自己还。
        private ActionStatus tidy(TickContext tick) {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty() || recipe.kind() != RecipeView.Kind.SMELTING) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            MenuContent.Reading open = reading.get();
            for (int index : List.of(FURNACE_FUEL, FURNACE_INPUT)) {
                SlotSnapshot left = open.containerSnapshots().get(index);
                if (left.isEmpty()) continue;
                int slotId = open.containerSlotIds().get(index);
                // 收不回（背包满了）就留在熔炉里，照常关界面，不为它卡住。
                return click(tick, slotId, 0, ClickType.QUICK_MOVE, slotChanged(slotId, left.stack().copy()),
                        (status, later) -> {
                            if (status != PendingMenuAction.Status.CONFIRMED_APPLIED) stage = Stage.CLOSE;
                            return ActionStatus.progressed();
                        });
            }
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭；关不上不冒充没开过，照常收场让引擎清点。
        private ActionStatus close(TickContext tick) {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                return ActionStatus.done();
            }
            MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
            if (claim instanceof MenuSession.Claim.Refused) {
                return ActionStatus.done();
            }
            var closing = ((MenuSession.Claim.Owned) claim).session()
                    .closeNow(reading.get().channel(), tick.gameTick());
            if (closing instanceof MenuSession.Closing.Closed || closing instanceof MenuSession.Closing.Failed) {
                return ActionStatus.done();
            }
            return ActionStatus.running();
        }

        // 点一下格子：别的界面操作没结清或界面还没真正显示时不点，下一刻再看。
        private ActionStatus click(TickContext tick, int slotId, int button, ClickType type,
                MenuConfirmation confirmation, Settlement after) {
            PlayerContext player = tick.player();
            if (!ready(player)) {
                return ActionStatus.running();
            }
            return submit(player.menuActions().click(player, slotId, button, type, confirmation, CLICK_TIMEOUT_TICKS),
                    after);
        }

        private boolean ready(PlayerContext player) {
            return !player.menuActions().hasPendingTransaction() && player.menuActions().ensureVisible(player);
        }

        private ActionStatus submit(PendingMenuAction submitted, Settlement after) {
            pending = submitted;
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
            return after.settle(status, tick);
        }

        private ActionStatus fail(Problem.Kind kind, String message) {
            failure = Problem.of(kind, message, null);
            return ActionStatus.failed(failure);
        }

        // 背包侧有几件这次要做的东西：取货前后各数一次，差就是这次真收进来的。
        private int carriedResults(MenuContent.Reading open) {
            String wanted = recipe.result().itemId();
            int total = 0;
            for (SlotSnapshot stack : open.playerSnapshots()) {
                if (!stack.isEmpty() && itemId(stack.stack()).equals(wanted)) total += stack.count();
            }
            return total;
        }

        // 这件东西是不是这条配方的原料：熔炉添燃料时不烧它，石切台拿料时只拿它。
        private boolean isIngredient(ItemStack stack) {
            return holder.value().getIngredients().stream().anyMatch(ingredient -> ingredient.test(stack));
        }

        // 摆料的确认条件：该有料的格子里真的出现了东西才算摆上。
        private MenuConfirmation ingredientsPlaced() {
            return (player, click) -> {
                Optional<MenuContent.Reading> reading = menus.current();
                if (reading.isEmpty()) return MenuConfirmation.Verdict.PENDING;
                List<SlotSnapshot> container = reading.get().containerSnapshots();
                boolean placed = recipe.kind() == RecipeView.Kind.SMELTING
                        ? !container.get(FURNACE_INPUT).isEmpty()
                        : container.subList(1, container.size()).stream().anyMatch(stack -> !stack.isEmpty());
                return placed ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
            };
        }

        // 界面类型对得上这条配方的设施种类才动手。
        private boolean menuMatches(MenuContent.Reading reading) {
            String typeId = reading.slots().menuTypeId() == null
                    ? "" : reading.slots().menuTypeId().toLowerCase(Locale.ROOT);
            return switch (recipe.kind()) {
                case CRAFTING -> typeId.equals(CRAFTING_MENU);
                case SMELTING -> typeId.equals(FURNACE_MENU);
                case STONECUTTING -> typeId.equals(STONECUTTER_MENU);
            };
        }

        // 产出格在容器侧的下标：合成第 0 格，烧炼第 2 格，石切台第 1 格。
        private int outputIndex() {
            return switch (recipe.kind()) {
                case CRAFTING -> 0;
                case SMELTING -> 2;
                case STONECUTTING -> 1;
            };
        }

        private String workstationName() {
            return switch (recipe.kind()) {
                case CRAFTING -> "工作台";
                case SMELTING -> "熔炉";
                case STONECUTTING -> "石切台";
            };
        }

        // 被生存需求打断：正在走的那一趟、正在点的那一下先停住，恢复后接着推进。
        @Override
        public void pause() {
            if (approaching != null) approaching.pause();
            if (opening != null) opening.pause();
        }

        // 收尾：没走完的走到、没确认的点开一并收尾，不让它们悬着占着身体。
        @Override
        public void close() {
            if (approaching != null) approaching.close();
            if (opening != null) opening.close();
        }

        @Override
        public String describe() {
            return "在工作站上做 " + recipe.result().describe();
        }
    }

    // 点到的格子变了（变空、变少或换了东西）才算这一下生效。
    private static MenuConfirmation slotChanged(int slotId, ItemStack before) {
        return (player, click) -> {
            ItemStack now = player.localPlayer().containerMenu.getSlot(slotId).getItem();
            return ItemStack.matches(now, before) ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
        };
    }

    // 这一格比点之前多了才算放进去。
    private static MenuConfirmation slotGrew(int slotId, int before) {
        return (player, click) -> player.localPlayer().containerMenu.getSlot(slotId).getItem().getCount() > before
                ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
    }

    // 手上拿起了东西才算拿起。
    private static MenuConfirmation cursorFilled() {
        return (player, click) -> player.localPlayer().containerMenu.getCarried().isEmpty()
                ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.APPLIED;
    }

    // 手上空了才算放下。
    private static MenuConfirmation cursorEmptied() {
        return (player, click) -> player.localPlayer().containerMenu.getCarried().isEmpty()
                ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 配方的设施种类对应的方块类型；摆台与记设施都用它。 */
    static String workstationBlockType(RecipeView.Kind kind) {
        return switch (kind) {
            case CRAFTING -> "minecraft:crafting_table";
            case SMELTING -> "minecraft:furnace";
            case STONECUTTING -> "minecraft:stonecutter";
        };
    }
}
