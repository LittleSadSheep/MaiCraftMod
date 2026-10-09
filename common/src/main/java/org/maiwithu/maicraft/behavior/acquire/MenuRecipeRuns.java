// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientQuickMoves;
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
 * 在工作站上动手做配方的生产实现：走到设施跟前、点开界面，经游戏的配方簿把原料
 * （烧炼连燃料）摆进去，产出格一出货就整堆收进背包，做够次数关上界面。
 *
 * <p>摆料走游戏自己的配方簿通道（合成、烧炼、石切台都认），不自己往格子里塞东西；
 * 烧炼要慢慢烧，动作逐刻看着产出格出货、原料见底就再摆一批，到总期限如实收场，
 * 已出货的照实留在背包里由引擎清点。设施到现场可能已经不在：点不开、界面认不出
 * 都如实失败，引擎换别的路。做完把这台设施记成"亲手用过的工作站"。
 */
public final class MenuRecipeRuns implements RecipeRuns {

    /** 等界面打开并同步完的期限（刻）。 */
    private static final int MENU_WAIT_LIMIT = 40;
    /** 烧一件东西要多少刻：原版熔炉的固定速度，总期限按它与次数估。 */
    private static final int SMELT_TICKS_PER_ITEM = 200;
    /** 一次摆料等游戏确认的期限（刻）。 */
    private static final int PLACE_TIMEOUT_TICKS = 100;

    /** 合成台界面的类型注册 ID。 */
    private static final String CRAFTING_MENU = "minecraft:crafting";
    /** 石切台界面的类型注册 ID。 */
    private static final String STONECUTTER_MENU = "minecraft:stonecutter";
    /** 熔炉一族的界面类型注册 ID：投入口、燃料槽、产出格的次序与界面布局判定同一份事实。 */
    private static final Set<String> FURNACE_MENUS = Set.of(
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker");

    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final ClientQuickMoves quickMoves;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> context;

    public MenuRecipeRuns(BringsPlayerClose close, Interactions interactions,
            ClientQuickMoves quickMoves, WorldMemory memory, Supplier<PlayerContext> context) {
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.quickMoves = Objects.requireNonNull(quickMoves, "quickMoves");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<Action> run(RecipeView recipe, WorldPosition station, int times) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return Optional.empty();
        }
        // 配方按注册 ID 认回游戏自己的配方对象：摆料通道只认这个对象，认不回就动不了手。
        ResourceLocation id = ResourceLocation.tryParse(recipe.id());
        if (id == null) {
            return Optional.empty();
        }
        return level.getRecipeManager().byKey(id).map(holder -> new WorkAction(holder, recipe, station, times));
    }

    @Override
    public Optional<Action> runAtRememberedStation(RecipeView recipe, int times) {
        // 刚就地摆下的那台就是最新记下的：按方块类型从记忆里找记录时刻最新的工作站。
        String blockType = workstationBlockType(recipe.kind());
        return memory.allRecords().stream()
                .filter(record -> record.kind() == MemoryKind.WORKSTATION)
                .filter(record -> blockType.equals(record.blockType()))
                .sorted((a, b) -> b.recordedAt().compareTo(a.recordedAt()))
                .findFirst()
                .flatMap(record -> run(recipe, record.position(), times));
    }

    /** 动手做一轮的动作：靠近 → 点开 → 等同步 → 摆料与收货循环 → 关上，跨刻推进。 */
    private final class WorkAction implements Action {

        /** 推进的先后：靠近、点开、等同步、摆料收货、关上。 */
        private enum Stage { APPROACH, OPEN, WAIT, WORK, CLOSE }

        private final RecipeHolder<?> holder;
        private final RecipeView recipe;
        private final WorldPosition station;
        private final int batches;
        /** 想做出的总件数：按配方的单次产出与次数估，实际拿到几件以引擎清点为准。 */
        private final int targetItems;

        private Stage stage = Stage.APPROACH;
        private Action approaching;
        private AimAndInteract opening;
        /** 界面打开后才建的读端：绑的就是点开的那份界面。 */
        private MenuContent menus;
        private PendingMenuAction pendingPlace;
        private int menuWaited;
        private int waited;
        private int produced;
        private Problem failure;

        WorkAction(RecipeHolder<?> holder, RecipeView recipe, WorldPosition station, int batches) {
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
                failure = Problem.of(Problem.Kind.STUCK,
                        "在工作站上做" + recipe.result().describe() + "超时，先收手", null);
                return ActionStatus.failed(failure);
            }
            return switch (stage) {
                case APPROACH -> approach(tick);
                case OPEN -> open(tick);
                case WAIT -> waitMenu();
                case WORK -> work(tick);
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
                approaching = close.toward(InteractionTarget.ofBlock(at), Permissions.DEFAULT);
            }
            ActionStatus status = approaching.tick(tick);
            if (status instanceof ActionStatus.Running) {
                return status;
            }
            if (status instanceof ActionStatus.Failed failed) {
                failure = Problem.of(Problem.Kind.UNREACHABLE,
                        "走不到工作站跟前做不了" + recipe.result().describe()
                                + "：" + failed.problem().message(), null);
                return ActionStatus.failed(failure);
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
                failure = Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "点开工作站没成功，做不了" + recipe.result().describe()
                                + "：" + failed.problem().message(), null);
                return ActionStatus.failed(failure);
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
                    failure = Problem.of(Problem.Kind.STUCK,
                            "工作站点开了但界面一直没同步，做不了" + recipe.result().describe(), null);
                    return ActionStatus.failed(failure);
                }
                return ActionStatus.running();
            }
            if (!menuMatches(reading.get())) {
                failure = Problem.of(Problem.Kind.UNSUPPORTED,
                        "打开的不是认得的" + workstationName() + "界面，做不了"
                                + recipe.result().describe(), null);
                return ActionStatus.failed(failure);
            }
            stage = Stage.WORK;
            return ActionStatus.progressed();
        }

        // 摆料与收货循环：出货就整堆收下，原料见底且没做够就再摆一批。
        private ActionStatus work(TickContext tick) {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                // 界面被合上了：已出货的照实算数，收场让引擎清点。
                return ActionStatus.done();
            }
            MenuContent.Reading open = reading.get();
            if (produced >= targetItems) {
                rememberUsed();
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            int outputIndex = outputIndex();
            SlotSnapshot output = open.containerSnapshots().get(outputIndex);
            if (!output.isEmpty()) {
                // 产出格有货：整堆收进背包，收到多少下一刻从界面上核对。
                produced += output.count();
                quickMoves.quickMove(tick.player(), open.containerSlotIds().get(outputIndex));
                return ActionStatus.running();
            }
            if (pendingPlace != null && !pendingPlace.terminal()) {
                pendingPlace = tick.player().menuActions().poll(tick.player(), pendingPlace);
                if (pendingPlace.terminal()
                        && pendingPlace.status() == PendingMenuAction.Status.CONFIRMED_NOT_APPLIED) {
                    failure = Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "配方簿没有把原料摆进去（身上可能缺料），做不了"
                                    + recipe.result().describe(), null);
                    return ActionStatus.failed(failure);
                }
                return pendingPlace.terminal() ? ActionStatus.progressed() : ActionStatus.running();
            }
            if (needsRefill(open)) {
                pendingPlace = tick.player().menuActions().placeRecipe(tick.player(), holder, false,
                        placedConfirmation(), PLACE_TIMEOUT_TICKS);
            }
            // 原料还在、产出没出：烧炼在慢慢烧，等下一刻。
            return ActionStatus.running();
        }

        // 原料格（合成格、投入口与燃料槽）空了且还没做够：需要再摆一批。
        private boolean needsRefill(MenuContent.Reading open) {
            for (int snapshotIndex : fillSlotIndexes()) {
                if (open.containerSnapshots().get(snapshotIndex).isEmpty()) {
                    return true;
                }
            }
            return false;
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

        // 做完把这台设施记成亲手用过的工作站：下次找设施、别人问起都用得上。
        private void rememberUsed() {
            memory.rememberWorkstationUsed(station, workstationBlockType(recipe.kind()), Instant.now());
        }

        // 摆料的确认条件：该有料的格子里真的出现了东西才算摆上。
        private MenuConfirmation placedConfirmation() {
            return (player, pending) -> {
                Optional<MenuContent.Reading> reading = menus.current();
                if (reading.isEmpty()) {
                    return MenuConfirmation.Verdict.PENDING;
                }
                for (int snapshotIndex : fillSlotIndexes()) {
                    if (!reading.get().containerSnapshots().get(snapshotIndex).isEmpty()) {
                        return MenuConfirmation.Verdict.APPLIED;
                    }
                }
                return MenuConfirmation.Verdict.PENDING;
            };
        }

        // 界面类型对得上这条配方的设施种类才动手。
        private boolean menuMatches(MenuContent.Reading reading) {
            String typeId = reading.slots().menuTypeId() == null
                    ? "" : reading.slots().menuTypeId().toLowerCase(Locale.ROOT);
            return switch (recipe.kind()) {
                case CRAFTING -> typeId.equals(CRAFTING_MENU);
                case SMELTING -> FURNACE_MENUS.contains(typeId);
                case STONECUTTING -> typeId.equals(STONECUTTER_MENU);
            };
        }

        // 该有料的格子在容器侧快照里的下标：合成是整张合成格，烧炼是投入口与燃料槽，石切台是投入口。
        private List<Integer> fillSlotIndexes() {
            return switch (recipe.kind()) {
                case CRAFTING -> gridIndexes();
                case SMELTING -> List.of(0, 1);
                case STONECUTTING -> List.of(0);
            };
        }

        // 产出格在容器侧快照里的下标：合成第 0 格，烧炼第 2 格，石切台第 1 格。
        private int outputIndex() {
            return switch (recipe.kind()) {
                case CRAFTING -> 0;
                case SMELTING -> 2;
                case STONECUTTING -> 1;
            };
        }

        // 合成格是容器侧除产出格（第 0 格）外的全部格子。
        private List<Integer> gridIndexes() {
            List<Integer> indexes = new ArrayList<>();
            for (int i = 1; i < containerSideSize(); i++) {
                indexes.add(i);
            }
            return List.copyOf(indexes);
        }

        // 容器侧有几格：按这条配方的设施从界面读数里数，读数还没到时按认不得处理。
        private int containerSideSize() {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                throw new IllegalStateException("界面读数还没同步就数容器侧的格子");
            }
            return reading.get().containerSlotIds().size();
        }

        private String workstationName() {
            return switch (recipe.kind()) {
                case CRAFTING -> "工作台";
                case SMELTING -> "熔炉";
                case STONECUTTING -> "石切台";
            };
        }

        @Override
        public String describe() {
            return "在工作站上做 " + recipe.result().describe();
        }
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
