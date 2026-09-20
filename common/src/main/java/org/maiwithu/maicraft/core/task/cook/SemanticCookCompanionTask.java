// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.mixin.MenuDataSlotsAccessor;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.cook.CookingRecipePlanner.FuelChoice;
import org.maiwithu.maicraft.core.task.cook.CookingRecipePlanner.ResolvedCandidate;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.menu.CloseMenuTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Objects;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.Constants;

/**
 * 完成一批加工：挑配方和炉子、备原料和燃料、打开并装入、等烧好、确认收回成品，必要时再做下一批。
 * 目标是主背包最终达到指定数量，不是新加工数量；原先已有的也算。
 * 等待时会关掉界面，并记住这批放在哪台炉子里；重新打开后核对投入与产出，再决定哪些东西能取。
 */
public final class SemanticCookCompanionTask
        extends AbstractCompanionTask<SemanticCookTaskRecord> {
    /** 炉子进度确实变化时续上无进展期限，不能让仍在正常加工的多炉目标被最初总时长截断。 */
    private static final long COOK_PROGRESS_LEASE_TICKS = 30L * 20L;
    private enum Phase { RESOLVE, PREPARE, OPEN, WAIT_MENU, VALIDATE, LOAD_INPUT,
        LOAD_FUEL, CONFIRM_START, CLOSE_WAIT, WAIT_CLOSED, RECONCILE,
        VERIFY_OUTPUT, VERIFY_CLEAN_INPUT, CLEANUP, COMPLETE }
    private enum OpenMode { NEW_BATCH, RESUME_BATCH }
    private enum Purpose { ACQUIRE_INPUT, ACQUIRE_FUEL, ACQUIRE_STATION, PLACE_STATION,
        MOVE_STATION, OPEN_STATION, LOAD_INPUT, LOAD_FUEL, TAKE_OUTPUT,
        CLOSE_WAIT, CLEAN_INPUT, CLOSE, ABANDON_CLOSE }
    private Phase phase = Phase.RESOLVE;
    private CookingRecipe candidate;
    private Item fuel;
    private int fuelBurnTicks;
    private int batchRaw;
    private int batchFuel;
    private BlockPos stationPos;
    private boolean stationClaimed;
    private boolean stationPlaced;
    private boolean openedMenu;
    private AbstractFurnaceMenu ownedMenu;
    private boolean openRequested;
    private boolean effectsStarted;
    private boolean batchOutstanding;
    private boolean finishRequested;
    private boolean parentSatisfied;
    private boolean replenishAfterClose;
    private OpenMode openMode = OpenMode.NEW_BATCH;
    private BlockPos stationReturnStance;
    private long nextCookCheckTick;
    private long closedWaitStartedTick;
    // 本批装入多少原料、已经取回多少成品，单独记账；背包目标总数还包含开工前已有的物品。
    private int ownedInputLoaded;
    private int ownedOutputTaken;
    private int takeInventoryBefore;
    private int takeSlotCount;
    private int cleanupInventoryBefore;
    private int cleanupTransferCount;
    private boolean cleanupSnapshotReady;
    private ItemStack cleanupInputExpected = ItemStack.EMPTY;
    private long waitMenuSince;
    private long lastCookEvidenceTick;
    private String lastCookEvidence = "";
    private BlockPos openAttemptStation;
    private int openAttempts;
    private Task activeChild;
    private TaskRecord activeRecord;
    private Purpose activePurpose;
    private int childSerial;
    private int initialOutputCount;
    private String failureCode;
    private String failureMessage;
    private FailureType failureType = FailureType.UNKNOWN;
    private String failedChildStage;
    private String failedChildMessage;
    private boolean outcomeUncertain;
    private Map<String, Object> prerequisiteFailure = Map.of();
    private final CookingRecipePlanner recipePlanner;
    private final Set<String> rejectedInputCandidates = new LinkedHashSet<>();
    private final Set<CookingDevice> rejectedDevices = new LinkedHashSet<>();
    private final List<Map<String, Object>> planningAttempts = new ArrayList<>();

    public SemanticCookCompanionTask(LocalPlayer player, SemanticCookTaskRecord record) {
        super(player, record);
        recipePlanner = new CookingRecipePlanner(player, record);
    }

    @Override protected void onStart() { initialOutputCount = outputCount(); }

    @Override
    // 先看目标数量是否已够，再继续未结束的子任务。收货和收回剩料正在确认时，不能因背包一时增长就跳过确认。
    protected TaskState onTick() {
        // 跨刻期间换成另一份菜单时先停止旧子任务，不能让同类同编号的新炉子接收旧槽位操作。
        if (openedMenu && ownedMenu != null && player.containerMenu != ownedMenu
                && !observingOwnClose()) {
            cancelActiveChild();
            return menuLost();
        }
        boolean outputTransferSettling = phase == Phase.VERIFY_OUTPUT
                || phase == Phase.VERIFY_CLEAN_INPUT
                || activePurpose == Purpose.TAKE_OUTPUT
                || activePurpose == Purpose.CLEAN_INPUT;
        if (outputCount() >= r.count && !finishRequested && !outputTransferSettling) {
            finishRequested = true;
        }
        if (activeChild != null) return tickChild();
        if (phase == Phase.COMPLETE) {
            if (failureMessage != null) {
                fail(failureMessage, failureType);
                return TaskState.FAILED;
            }
            return TaskState.SUCCESS;
        }
        routeFinishRequest();
        return switch (phase) {
            case RESOLVE -> resolve();
            case PREPARE -> prepare();
            case OPEN -> openStation();
            case WAIT_MENU -> waitMenu();
            case VALIDATE -> validateMenu();
            case LOAD_INPUT -> loadInput();
            case LOAD_FUEL -> loadFuel();
            case CONFIRM_START -> confirmStart();
            case CLOSE_WAIT -> closeForCookWait();
            case WAIT_CLOSED -> waitClosed();
            case RECONCILE -> reconcileBatch();
            case VERIFY_OUTPUT -> verifyOutputTake();
            case VERIFY_CLEAN_INPUT -> verifyCleanupInput();
            case CLEANUP -> cleanupMachine();
            case COMPLETE -> TaskState.SUCCESS;
        };
    }

    // 数量够了只是不再开新批次；炉里还有本任务的原料时，要回去核对并收尾，不能直接丢下正在加工的这一批。
    private void routeFinishRequest() {
        if (!finishRequested || phase == Phase.CLEANUP || phase == Phase.COMPLETE
                || phase == Phase.VERIFY_OUTPUT || phase == Phase.VERIFY_CLEAN_INPUT) {
            return;
        }
        if (!stationClaimed || ownedInputLoaded <= 0) {
            phase = Phase.CLEANUP;
            return;
        }
        if (player.containerMenu instanceof AbstractFurnaceMenu && menuMatches()) {
            openedMenu = true;
            phase = Phase.RECONCILE;
            return;
        }
        if (phase == Phase.WAIT_CLOSED) {
            nextCookCheckTick = player.level().getGameTime();
            return;
        }
        if (phase == Phase.OPEN || phase == Phase.WAIT_MENU) {
            return;
        }
        openMode = OpenMode.RESUME_BATCH;
        nextCookCheckTick = player.level().getGameTime();
        phase = Phase.WAIT_CLOSED;
    }

    @Override
    // 关着界面等炉子加工时，没到检查时刻就暂不要求执行；火熄灭或设备变化也可能提前唤起检查。
    public boolean canRun(LocalPlayer companion) {
        return phase != Phase.WAIT_CLOSED || closedWaitDue(companion);
    }

    private boolean closedWaitDue(LocalPlayer companion) {
        long now = companion.level().getGameTime();
        if (now >= nextCookCheckTick) return true;
        if (stationPos == null || !companion.level().isLoaded(stationPos)) return false;
        var state = companion.level().getBlockState(stationPos);
        if (candidate == null || !state.is(candidate.device().block)) return true;
        // 等关闭稳定两刻后，炉子熄火就是提前检查的线索：可能已经烧完，也可能缺燃料。
        return now > closedWaitStartedTick + 2L
                && state.hasProperty(
                        BlockStateProperties.LIT)
                && !state.getValue(
                        BlockStateProperties.LIT);
    }

    // 先找能产出目标的配方，再估原料、燃料和设备的准备成本，从认为可行的组合里选择一组。
    private TaskState resolve() {
        List<CookingRecipe> candidates = recipePlanner.candidates();
        if (candidates.isEmpty()) {
            return failOrClean("no_cooking_recipe",
                    "No smelting, blasting, smoking or campfire recipe produces " + r.itemId + ".",
                    FailureType.NO_MATERIAL);
        }
        // 能识别营火配方，但本版本不执行营火操作；明确要求营火时报告不支持，不改用炉子偷偷替代。
        if (r.preference == SemanticCookTaskRecord.Preference.CAMPFIRE) {
            boolean available = candidates.stream().anyMatch(c -> c.device() == CookingDevice.CAMPFIRE);
            return failOrClean(
                    available ? "campfire_execution_not_supported" : "no_preferred_recipe",
                    available
                            ? "A campfire recipe exists, but this version only executes synchronized "
                                    + "furnace, blast-furnace and smoker menus."
                            : "No campfire recipe produces " + r.itemId + ".",
                    FailureType.UNKNOWN);
        }
        candidates.removeIf(c -> c.device() == CookingDevice.CAMPFIRE || !recipePlanner.preferred(c.device())
                || rejectedDevices.contains(c.device())
                || rejectedInputCandidates.contains(candidateKey(c)));
        if (candidates.isEmpty()) {
            return failOrClean("no_preferred_recipe",
                    "No untried recipe matching recipe_preference can produce " + r.itemId + ".",
                    FailureType.NO_MATERIAL);
        }
        recipePlanner.observeNearbyBlocks();

        List<ResolvedCandidate> plans = new ArrayList<>();
        for (CookingRecipe option : candidates) {
            int raw = rawRemaining(option);
            FuelChoice fuelChoice = recipePlanner.chooseFuel(option, raw);
            if (fuelChoice == null) continue;
            plans.add(new ResolvedCandidate(
                    option, fuelChoice, fuelChoice.inputCost(), fuelChoice.stationCost(),
                    fuelChoice.preparationCost()));
        }
        if (plans.isEmpty()) {
            return failOrClean("no_allowed_fuel",
                    "No allowed ordinary furnace fuel can be selected.", FailureType.NO_MATERIAL);
        }
        ResolvedCandidate selected = plans.stream()
                .filter(plan -> plan.preparationCost() < CookingRecipePlanner.UNAVAILABLE_COST)
                .min(recipePlanner.candidateComparator())
                .orElse(null);
        if (selected == null) {
            return failOrClean("no_reachable_cooking_plan",
                    "Cooking recipes exist, but none has a supported path to its input, fuel and workstation.",
                    FailureType.NO_MATERIAL);
        }
        candidate = selected.candidate();
        FuelChoice selectedFuel = selected.fuel();
        fuel = selectedFuel.item();
        fuelBurnTicks = selectedFuel.burnTicks();
        prerequisiteFailure = Map.of();
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    // 还欠上一炉的结果时先回到原设备核对；新批次则根据产物和燃料堆叠上限决定装多少原料。
    private TaskState prepare() {
        if (stationClaimed && ownedInputLoaded > 0) {
            if (stationPos == null) {
                return closeWithoutClaimingContents("station_target_lost",
                        "The exact claimed workstation position was lost while its batch was outstanding.",
                        FailureType.TARGET_LOST);
            }
            if (player.level().isLoaded(stationPos)
                    && !player.level().getBlockState(stationPos).is(candidate.device().block)) {
                return closeWithoutClaimingContents("station_replaced_while_cooking",
                        "The exact claimed workstation disappeared while its batch was outstanding.",
                        FailureType.TARGET_LOST);
            }
            openMode = OpenMode.RESUME_BATCH;
            nextCookCheckTick = player.level().getGameTime();
            phase = Phase.WAIT_CLOSED;
            return TaskState.RUNNING;
        }
        if (finishRequested) {
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        CookingBatch batch = CookingBatch.plan(candidate, fuel, rawRemaining(), player.level().registryAccess());
        if (batch == null) return failOrClean("cooking_batch_unavailable",
                "The selected input, output or fuel cannot fit one supported cooking batch.", FailureType.UNSUPPORTED);
        batchRaw = batch.inputCount();
        batchFuel = batch.fuelCount();
        fuelBurnTicks = batch.burnTicks();
        // 原料和燃料是同种物品时，实际准备要求两份用途的数量相加。
        // 估价和实际准备都合计这两份用途，不能让同一份原木同时承担原料和燃料。
        if (candidate.input() == fuel) {
            int sharedNeed = batchRaw + batchFuel;
            if (PlayerInv.buildableCount(player.getInventory(), candidate.input()) < sharedNeed) {
                return acquire(candidate.input(), sharedNeed, Purpose.ACQUIRE_INPUT);
            }
        } else {
            if (PlayerInv.buildableCount(player.getInventory(), candidate.input()) < batchRaw) {
                return acquire(candidate.input(), batchRaw, Purpose.ACQUIRE_INPUT);
            }
            if (PlayerInv.buildableCount(player.getInventory(), fuel) < batchFuel) {
                return acquire(fuel, batchFuel, Purpose.ACQUIRE_FUEL);
            }
        }
        if (openedMenu) {
            if (!(player.containerMenu instanceof AbstractFurnaceMenu) || !menuMatches()) {
                return menuLost();
            }
            phase = Phase.VALIDATE;
            return TaskState.RUNNING;
        }
        // 先选最近的同类设备；只有没找到任何这种方块时才考虑放自己的设备。
        // 当前不先筛空闲状态，选到忙炉后也不试另一台空炉，见 A59。
        if (stationPos == null
                || !player.level().getBlockState(stationPos).is(candidate.device().block)) {
            stationPos = nearest(candidate.device().block, 32, 16);
        }
        if (stationPos == null) {
            if (PlayerInv.buildableCount(
                    player.getInventory(), candidate.device().block.asItem()) < 1) {
                return acquire(
                        candidate.device().block.asItem(), 1, Purpose.ACQUIRE_STATION);
            }
            BlockPos site = placementSite();
            if (site == null) {
                return failOrClean("no_safe_station_site",
                        "No safe loaded nearby cell can receive the required cooking workstation.",
                        FailureType.TERRAIN_BLOCKED);
            }
            stationPos = site;
            BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                    candidate.device().block, candidate.device().block.asItem(), site,
                    BuiltInRegistries.BLOCK.getKey(candidate.device().block).toString(),
                    null, null, null).asItemPlace();
            return start(new BuildTaskRecord(
                    childId("place"), childDeadline(3L * 60L * 20L),
                    List.of(target), false, true, false), Purpose.PLACE_STATION);
        }
        if (!withinReach(stationPos)) {
            return start(new MoveToTaskRecord(
                    childId("move"), childDeadline(3L * 60L * 20L),
                    null, null, null,
                    BuiltInRegistries.BLOCK.getKey(candidate.device().block).toString(), false),
                    Purpose.MOVE_STATION);
        }
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    // 需要补料时先关自己开的菜单，再把需求交给语义取物任务，并传递允许来源、伤害与保护标签。
    // 去掉 COOK 来源，避免为了本次加工原料又递归开启加工任务。
    private TaskState acquire(Item item, int finalCount, Purpose purpose) {
        if (openedMenu) {
            replenishAfterClose = true;
            phase = Phase.CLEANUP;
            return TaskState.RUNNING;
        }
        SemanticAcquireTaskRecord child = new SemanticAcquireTaskRecord(
                childId("acquire"), childDeadline(8L * 60L * 20L),
                List.of(BuiltInRegistries.ITEM.getKey(item)), finalCount,
                r.allowedSources.stream()
                        .filter(source -> source != SemanticAcquireTaskRecord.Source.COOK)
                        .toList(),
                r.allowHarm, SemanticAcquireTaskRecord.SourceHint.empty(),
                r.protectedLabels, 16);
        return start(child, purpose);
    }

    // 回到已选设备附近并重新右键；上一批仍在里面时必须保留那个位置，不能随便换另一台同类设备。
    private TaskState openStation() {
        // 人工或其他工作正在使用界面时等待；只有从玩家背包返回世界后才开始本次开炉。
        if (player.containerMenu != player.inventoryMenu) return TaskState.RUNNING;
        if (stationPos == null || (player.level().isLoaded(stationPos)
                && !player.level().getBlockState(stationPos).is(candidate.device().block))) {
            if (stationClaimed && ownedInputLoaded > 0) {
                return closeWithoutClaimingContents("station_replaced_while_cooking",
                        "The exact claimed workstation was removed or replaced before it could be reopened.",
                        FailureType.TARGET_LOST);
            }
            stationPos = null;
            openAttemptStation = null;
            openAttempts = 0;
            phase = Phase.PREPARE;
            return TaskState.RUNNING;
        }
        if (!withinReach(stationPos)) {
            if (stationClaimed && ownedInputLoaded > 0) {
                nextCookCheckTick = player.level().getGameTime();
                openMode = OpenMode.RESUME_BATCH;
                phase = Phase.WAIT_CLOSED;
            } else {
                phase = Phase.PREPARE;
            }
            return TaskState.RUNNING;
        }
        if (!stationPos.equals(openAttemptStation)) {
            openAttemptStation = stationPos;
            openAttempts = 0;
        }
        openAttempts++;
        openRequested = true;
        ownedMenu = null;
        return start(new InteractAtTaskRecord(
                childId("open"), childDeadline(30L * 20L),
                MouseButton.RIGHT, stationPos, 0, null, null, candidate.device().block), Purpose.OPEN_STATION);
    }

    // 等炉类菜单出现、显示且类型与配方对应；未打开时有限重试，出现错误菜单时只安排关闭并报错。
    private TaskState waitMenu() {
        if (player.containerMenu instanceof AbstractFurnaceMenu menu) {
            if (!openRequested || ownedMenu != null && ownedMenu != menu) return menuLost();
            // 本次打开收到的菜单对象只绑定一次；等渲染时若又换了对象，也不能重新认领。
            ownedMenu = menu;
            openedMenu = true;
            var context = ClientRuntime.requireContext(player);
            if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
            if (!menuMatches()) {
                rememberFailure("wrong_station_menu",
                        "The opened workstation does not match the selected cooking recipe.",
                        FailureType.TARGET_LOST);
                outcomeUncertain |= effectsStarted;
                openedMenu = true;
                return start(new CloseMenuTaskRecord(
                        childId("wrong-menu-close"), childDeadline(30L * 20L), ownedMenu),
                        Purpose.ABANDON_CLOSE);
            }
            // 这只限制连续开门确认失败；下一炉重新打开时，不累计此前正常打开的次数。
            openAttempts = 0;
            openedMenu = true;
            phase = openMode == OpenMode.RESUME_BATCH
                    ? Phase.RECONCILE : Phase.VALIDATE;
            return TaskState.RUNNING;
        }
        if (player.level().getGameTime() - waitMenuSince > 60L) {
            if (openAttempts < 2) {
                phase = Phase.OPEN;
                return TaskState.RUNNING;
            }
            if (player.containerMenu != player.inventoryMenu) {
                return failOrClean("station_open_unconfirmed",
                        "The selected workstation opened an unexpected synchronized menu.",
                        FailureType.TARGET_LOST);
            }
            return failOrClean("station_open_unconfirmed",
                    "The selected workstation did not open a synchronized furnace-family menu.",
                    FailureType.TARGET_LOST);
        }
        return TaskState.RUNNING;
    }

    // 首次使用要求原料、燃料、成品和活动进度都为空，之后才把自己装入的这一批记作本任务所有。
    private TaskState validateMenu() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        ItemStack input = menu.getSlot(0).getItem();
        ItemStack fuelSlot = menu.getSlot(1).getItem();
        ItemStack result = menu.getSlot(2).getItem();
        if (!stationClaimed && (!input.isEmpty() || !fuelSlot.isEmpty()
                || !result.isEmpty() || data(menu, 0) > 0 || data(menu, 2) > 0)) {
            return failOrClean("station_in_use",
                    "The resolved workstation already contains items or active progress; "
                            + "MaiCraft will not claim or disturb it automatically.",
                    FailureType.UNKNOWN);
        }
        if (stationClaimed && (!input.isEmpty() || !result.isEmpty())) {
            return failOrClean("station_state_diverged",
                    "The claimed workstation contains unexpected input or output before a new batch.",
                    FailureType.UNKNOWN);
        }
        if (!fuelSlot.isEmpty() && !AbstractFurnaceBlockEntity.isFuel(fuelSlot)) {
            return failOrClean("station_fuel_diverged",
                    "The claimed workstation fuel slot contains a non-fuel item.",
                    FailureType.UNKNOWN);
        }
        stationClaimed = true;
        // 认领空设备还不等于原料已经入炉，装入量必须等 LOAD_INPUT 的原生搬运回执确认。
        ownedInputLoaded = 0;
        ownedOutputTaken = 0;
        takeInventoryBefore = 0;
        takeSlotCount = 0;
        phase = Phase.LOAD_INPUT;
        return TaskState.RUNNING;
    }

    private TaskState loadInput() {
        return transferTo(candidate.input(), batchRaw, 0, Purpose.LOAD_INPUT);
    }

    // 先考虑剩余燃烧时间和燃料格里的存量，再补足本批需要的燃料。
    // 已有燃料也按当前设备的原生规则折算，与备料时使用同一套时长。
    private TaskState loadFuel() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null) return menuLost();
        int availableBurn = Math.max(0, data(menu, 0));
        ItemStack fuelSlot = menu.getSlot(1).getItem();
        if (!fuelSlot.isEmpty()) {
            availableBurn += fuelSlot.getCount() * candidate.device().burnDuration(fuelSlot);
        }
        int neededTicks = batchRaw * candidate.recipe().getCookingTime();
        int toLoad = ceilDiv(
                Math.max(0L, (long) neededTicks - availableBurn), fuelBurnTicks);
        if (toLoad <= 0) {
            phase = Phase.CONFIRM_START;
            markCookEvidence();
            return TaskState.RUNNING;
        }
        return transferTo(fuel, toLoad, 1, Purpose.LOAD_FUEL);
    }

    /** 物品进了槽，不等于服务端已经接受配方并点火。 */
    // 看到原料减少或成品出现就核对产出；否则等燃烧时间和加工进度真的开始，再关界面等待。
    private TaskState confirmStart() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null) return menuLost();
        ItemStack result = menu.getSlot(2).getItem();
        if (!result.isEmpty() && !result.is(BuiltInRegistries.ITEM.get(r.itemId))) {
            return closeWithoutClaimingContents("cooking_output_diverged",
                    "The synchronized workstation output no longer matches the selected recipe.",
                    FailureType.UNKNOWN);
        }
        ItemStack input = menu.getSlot(0).getItem();
        if (!input.isEmpty() && !input.is(candidate.input())) {
            return closeWithoutClaimingContents("cooking_input_diverged",
                    "The synchronized workstation input changed after MaiCraft loaded the batch.",
                    FailureType.UNKNOWN);
        }
        // 极快配方或已经热着的炉子可能在第一次取进度前就产出，先核对数量账而不是误报没点火。
        if (!result.isEmpty() || input.getCount() < ownedInputLoaded) {
            phase = Phase.RECONCILE;
            return TaskState.RUNNING;
        }
        String evidence = data(menu, 0) + ":" + data(menu, 2) + ":" + data(menu, 3)
                + ":" + input.getCount() + ":" + result.getCount();
        if (!evidence.equals(lastCookEvidence)) {
            lastCookEvidence = evidence;
            lastCookEvidenceTick = player.level().getGameTime();
            renewCookProgressLease();
        }
        if (!input.isEmpty() && data(menu, 0) > 0
                && data(menu, 2) > 0 && data(menu, 3) > 0) {
            stationReturnStance = player.blockPosition();
            scheduleClosedCheck(menu);
            phase = Phase.CLOSE_WAIT;
            return TaskState.RUNNING;
        }
        long quietLimit = Math.max(200L, candidate.recipe().getCookingTime() + 100L);
        if (player.level().getGameTime() - lastCookEvidenceTick <= quietLimit) {
            return TaskState.RUNNING;
        }
        if (!input.isEmpty() && data(menu, 0) <= 0
                && menu.getSlot(1).getItem().isEmpty()) {
            return failOrClean("fuel_exhausted",
                    "The workstation never confirmed ignition and has no fuel remaining.",
                    FailureType.NO_MATERIAL);
        }
        return failOrClean("cooking_start_unconfirmed",
                "The loaded workstation did not produce synchronized ignition or progress evidence.",
                FailureType.UNKNOWN);
    }

    private TaskState closeForCookWait() {
        if (!(player.containerMenu instanceof AbstractFurnaceMenu) || !menuMatches()) {
            return menuLost();
        }
        return start(new CloseMenuTaskRecord(
                childId("wait-close"), childDeadline(30L * 20L), ownedMenu), Purpose.CLOSE_WAIT);
    }

    // 用菜单里的总耗时、当前进度和剩余原料数估计整批完成时刻，并给后续检查留一点时间。
    private void scheduleClosedCheck(AbstractFurnaceMenu menu) {
        int total = Math.max(1, data(menu, 3) > 0
                ? data(menu, 3) : candidate.recipe().getCookingTime());
        int progress = Math.max(0, Math.min(total - 1, data(menu, 2)));
        int remainingInputs = Math.max(1, menu.getSlot(0).getItem().getCount());
        long remaining = (long) total - progress
                + (long) (remainingInputs - 1) * total;
        nextCookCheckTick = player.level().getGameTime() + Math.max(2L, remaining + 2L);
        r.extendDeadlineTo(nextCookCheckTick + COOK_PROGRESS_LEASE_TICKS);
    }

    // 没到时间就继续等；玩家此时开着别的菜单也先等待，不去关它。
    // 需要回炉子时走到之前记住的站位，再打开同一工作站。
    private TaskState waitClosed() {
        openedMenu = false;
        if (!closedWaitDue(player)) return TaskState.RUNNING;
        if (player.containerMenu != player.inventoryMenu) {
            // 玩家或更高层任务正在操作别的界面时等待，不能为了看炉子先关掉它。
            return TaskState.RUNNING;
        }
        if (stationPos == null) {
            return closeWithoutClaimingContents("station_target_lost",
                    "The exact workstation position was lost while its batch was cooking.",
                    FailureType.TARGET_LOST);
        }
        if (player.level().isLoaded(stationPos)
                && !player.level().getBlockState(stationPos).is(candidate.device().block)) {
            return closeWithoutClaimingContents("station_replaced_while_cooking",
                    "The claimed workstation was removed or replaced while its batch was cooking.",
                    FailureType.TARGET_LOST);
        }
        openMode = OpenMode.RESUME_BATCH;
        if (!withinReach(stationPos)) {
            BlockPos stance = stationReturnStance;
            if (stance == null) {
                return closeWithoutClaimingContents("station_return_stance_lost",
                        "No verified first-person stance was retained for the claimed workstation.",
                        FailureType.TARGET_LOST);
            }
            return start(new MoveToTaskRecord(
                    childId("return"), childDeadline(3L * 60L * 20L),
                    (double) stance.getX(), (double) stance.getY(), (double) stance.getZ(),
                    null, false), Purpose.MOVE_STATION);
        }
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    /** 只核对本任务向初始空炉投入的数量，不认领来路不明的额外物品。 */
    // 核对本批账：装入量减去炉内剩余量，应等于已加工的原料数；对应成品应在结果槽或已经被本任务取走。
    // 数量不合就停止触碰内容，避免把外来的物品当自己的；当前一次不合就报外部变化，未等待分批同步。
    private TaskState reconcileBatch() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        ItemStack input = menu.getSlot(0).getItem();
        ItemStack result = menu.getSlot(2).getItem();
        if (!input.isEmpty() && !input.is(candidate.input())) {
            return closeWithoutClaimingContents("workstation_input_interference",
                    "The claimed workstation now contains another input; MaiCraft left it untouched.",
                    FailureType.UNKNOWN);
        }
        if (!result.isEmpty() && !result.is(BuiltInRegistries.ITEM.get(r.itemId))) {
            return closeWithoutClaimingContents("workstation_output_interference",
                    "The claimed workstation now contains another output; MaiCraft left it untouched.",
                    FailureType.UNKNOWN);
        }
        int currentInput = input.isEmpty() ? 0 : input.getCount();
        if (currentInput > ownedInputLoaded) {
            return closeWithoutClaimingContents("workstation_input_inserted",
                    "More recipe input appeared than MaiCraft loaded; external automation or a player changed the batch.",
                    FailureType.UNKNOWN);
        }
        int consumed = ownedInputLoaded - currentInput;
        long expectedProduced = (long) consumed * candidate.outputCount();
        long observedOwned = (long) ownedOutputTaken + result.getCount();
        if (observedOwned != expectedProduced) {
            String relation = observedOwned < expectedProduced
                    ? "Some cooked output was removed, likely by a hopper or player"
                    : "Additional cooked output appeared from outside this batch";
            return closeWithoutClaimingContents("workstation_output_externally_changed",
                    relation + " (expected " + expectedProduced + " batch output, observed "
                            + observedOwned + "); MaiCraft did not take or replace anything.",
                    FailureType.UNKNOWN);
        }
        if (!result.isEmpty()) {
            takeInventoryBefore = outputCount();
            takeSlotCount = result.getCount();
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(2, -1, 0)), Purpose.TAKE_OUTPUT);
        }
        if (failureMessage != null || finishRequested) {
            beginCleanupSnapshot(input);
            return TaskState.RUNNING;
        }
        if (currentInput == 0) {
            beginCleanupSnapshot(input);
            return TaskState.RUNNING;
        }
        if (data(menu, 0) > 0 && data(menu, 3) > 0) {
            stationReturnStance = player.blockPosition();
            scheduleClosedCheck(menu);
            phase = Phase.CLOSE_WAIT;
            return TaskState.RUNNING;
        }
        if (menu.getSlot(1).getItem().isEmpty()) {
            rememberFailure("fuel_exhausted",
                    "The verified batch still has input but no burn time or fuel remaining.",
                    FailureType.NO_MATERIAL);
            beginCleanupSnapshot(input);
            return TaskState.RUNNING;
        }
        rememberFailure("cooking_stalled",
                "The verified batch has input and fuel but no synchronized cooking progress.",
                FailureType.UNKNOWN);
        beginCleanupSnapshot(input);
        return TaskState.RUNNING;
    }

    // 结果槽原来有多少，主背包就应准确增加多少；低层快速搬运说完成也不能替代这一步。
    private TaskState verifyOutputTake() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        int inventoryGain = outputCount() - takeInventoryBefore;
        if (takeSlotCount <= 0 || inventoryGain != takeSlotCount) {
            outcomeUncertain = true;
            return closeWithoutClaimingContents("cooking_output_take_unverified",
                    "The result-slot click settled without the exact expected main-inventory gain; "
                            + "MaiCraft stopped before touching the remaining workstation contents.",
                    FailureType.UNKNOWN);
        }
        ownedOutputTaken += takeSlotCount;
        takeInventoryBefore = 0;
        takeSlotCount = 0;
        phase = Phase.RECONCILE;
        return TaskState.RUNNING;
    }

    // 从当前菜单的玩家侧收集所需物品格，再生成逐笔搬入炉子的操作。
    // 装原料／燃料允许放入后立即被机器消耗，不强求它们一直原样留在目标槽。
    private TaskState transferTo(
            Item item, int count, int destination, Purpose purpose) {
        List<ContainerTransferTaskRecord.Move> moves = new ArrayList<>();
        int remaining = count;
        for (int slot = 3;
                slot < player.containerMenu.slots.size() && remaining > 0; slot++) {
            ItemStack stack = player.containerMenu.getSlot(slot).getItem();
            if (!stack.is(item)) continue;
            int moved = Math.min(remaining, stack.getCount());
            ContainerTransferTaskRecord.DestinationMode destinationMode =
                    purpose == Purpose.LOAD_INPUT || purpose == Purpose.LOAD_FUEL
                            ? ContainerTransferTaskRecord.DestinationMode.MAY_MUTATE_AFTER_DEPOSIT
                            : ContainerTransferTaskRecord.DestinationMode.EXACT;
            moves.add(new ContainerTransferTaskRecord.Move(
                    slot, destination, moved, destinationMode));
            remaining -= moved;
        }
        if (remaining > 0) {
            return failOrClean(
                    purpose == Purpose.LOAD_INPUT
                            ? "input_inventory_changed" : "fuel_inventory_changed",
                    "Prepared cooking resources are no longer present in the synchronized inventory.",
                    FailureType.NO_MATERIAL);
        }
        return startTransfer(moves, purpose);
    }

    private TaskState startTransfer(
            List<ContainerTransferTaskRecord.Move> moves, Purpose purpose) {
        if (moves.isEmpty()) {
            return failOrClean("empty_menu_transaction",
                    "No synchronized menu transfer could be planned.", FailureType.INTERNAL);
        }
        return start(new ContainerTransferTaskRecord(
                childId("menu"), childDeadline(2L * 60L * 20L),
                player.containerMenu.containerId, moves, false), purpose);
    }

    // 本任务认为炉类菜单是自己打开时，才尝试收尾；先核对本批成品，再把确认属于本任务的剩余原料收回。
    // 留下剩余燃料，不尝试在仍可能烧制时强行取出它。
    private TaskState cleanupMachine() {
        if (!openedMenu || !(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            openedMenu = false;
            return finishCleanup();
        }
        if (!menuMatches()) return menuLost();
        if (!cleanupSnapshotReady) {
            // 失败时炉子可能还开着，先回到本批账核对，不能因为进入清理阶段就直接拿取槽内物品。
            phase = Phase.RECONCILE;
            return TaskState.RUNNING;
        }
        ItemStack result = menu.getSlot(2).getItem();
        ItemStack input = menu.getSlot(0).getItem();
        if (!result.isEmpty() || !sameStack(input, cleanupInputExpected)) {
            // 核对后到清理前可能又烧好一件；重新对账，让新成品经过正常取出与背包增量确认。
            cleanupSnapshotReady = false;
            cleanupInputExpected = ItemStack.EMPTY;
            phase = Phase.RECONCILE;
            return TaskState.RUNNING;
        }
        if (!cleanupInputExpected.isEmpty()) {
            cleanupInventoryBefore = PlayerInv.buildableCount(
                    player.getInventory(), candidate.input());
            cleanupTransferCount = cleanupInputExpected.getCount();
            return startTransfer(List.of(
                    new ContainerTransferTaskRecord.Move(
                            0, -1, cleanupTransferCount)), Purpose.CLEAN_INPUT);
        }
        // 当前没有完整燃料归属账；关过界面后不能排除玩家或漏斗补入同类燃料，因此不猜着取回。
        return start(new CloseMenuTaskRecord(
                childId("close"), childDeadline(30L * 20L), ownedMenu), Purpose.CLOSE);
    }

    private void beginCleanupSnapshot(ItemStack input) {
        cleanupInputExpected = input.copy();
        cleanupSnapshotReady = true;
        phase = Phase.CLEANUP;
    }

    // 收回剩余原料后，要看到原料槽清空且主背包准确增加预期数量，才继续收尾。
    private TaskState verifyCleanupInput() {
        AbstractFurnaceMenu menu = furnaceMenu();
        if (menu == null || !menuMatches()) return menuLost();
        int inventoryGain = PlayerInv.buildableCount(
                player.getInventory(), candidate.input()) - cleanupInventoryBefore;
        if (cleanupTransferCount <= 0 || inventoryGain != cleanupTransferCount
                || !menu.getSlot(0).getItem().isEmpty()) {
            outcomeUncertain = true;
            return closeWithoutClaimingContents("cooking_input_return_unverified",
                    "The input-slot return settled without the exact expected main-inventory "
                            + "gain; MaiCraft stopped before touching any remaining contents.",
                    FailureType.UNKNOWN);
        }
        cleanupInventoryBefore = 0;
        cleanupTransferCount = 0;
        cleanupInputExpected = ItemStack.EMPTY;
        phase = Phase.CLEANUP;
        return TaskState.RUNNING;
    }

    // 有失败就以失败结束，目标已够就结束；否则清掉上一批的数量记录并准备下一批，设备位置可继续保留。
    private TaskState finishCleanup() {
        // 只有正常核对、取回剩料并确认关闭后，才把本批记为结清；取消或丢菜单不能走这一步。
        batchOutstanding = false;
        if (failureMessage != null) {
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        if (finishRequested || outputCount() >= r.count) {
            finishRequested = true;
            phase = Phase.COMPLETE;
            return TaskState.RUNNING;
        }
        replenishAfterClose = false;
        openMode = OpenMode.NEW_BATCH;
        ownedInputLoaded = 0;
        ownedOutputTaken = 0;
        takeInventoryBefore = 0;
        takeSlotCount = 0;
        cleanupInventoryBefore = 0;
        cleanupTransferCount = 0;
        cleanupSnapshotReady = false;
        cleanupInputExpected = ItemStack.EMPTY;
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    // 本类会检查子任务自己的截止时间，到时停止并取超时结果；正常结束也调用 result，让子任务完成清理。
    private TaskState tickChild() {
        TaskState terminal;
        if (activeRecord != null
                && player.level().getGameTime() >= activeRecord.getDeadlineGameTime()) {
            // 内部子任务没有自己的 TaskSlot，在这里判定它的期限，让失败仍能说明是哪项前置工作超时。
            activeChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(activeChild);
        }
        if (terminal == null) {
            // 备料或导航仍在产生进展时，把它的续时带回父任务，避免父任务先到期丢掉具体进度。
            if (activeRecord != null) {
                extendParentPast(activeRecord.getDeadlineGameTime());
            }
            return TaskState.RUNNING;
        }
        TaskResult result = activeChild.result(terminal);
        // 子任务已经无法确认的效果必须传到总加工结果，不能只藏在嵌套失败说明里。
        outcomeUncertain |= uncertain(result);
        Purpose purpose = activePurpose;
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        if (terminal != TaskState.SUCCESS || result == null || !result.success() || uncertain(result)) {
            if (uncertain(result)) rememberFailure("cooking_child_outcome_uncertain",
                    "A cooking prerequisite or menu operation ended with unconfirmed effects; further work stopped.",
                    FailureType.UNKNOWN);
            if (purpose == Purpose.ABANDON_CLOSE) {
                outcomeUncertain = true;
                openedMenu = player.containerMenu != player.inventoryMenu;
                phase = Phase.COMPLETE;
                return TaskState.RUNNING;
            }
            if (purpose == Purpose.ACQUIRE_INPUT || purpose == Purpose.ACQUIRE_FUEL
                    || purpose == Purpose.ACQUIRE_STATION) {
                prerequisiteFailure = semanticPrerequisiteFailure(result);
                // 备料失败也可能已经挖过方块或消耗材料；这时不能把它当作免费试错，继续换另一条生产路线。
                effectsStarted |= prerequisiteEffects(result);
                if (retryAnotherPreparationPlan(purpose, result)) {
                    return TaskState.RUNNING;
                }
            }
            failedChildStage = purpose == null
                    ? "unknown" : purpose.name().toLowerCase();
            failedChildMessage = result == null ? "child returned no result" : result.message();
            if (purpose == Purpose.LOAD_INPUT || purpose == Purpose.LOAD_FUEL
                    || purpose == Purpose.TAKE_OUTPUT || purpose == Purpose.CLEAN_INPUT) {
                outcomeUncertain = true;
                return closeWithoutClaimingContents(
                        childFailureCode(purpose), childFailureMessage(purpose), lastFailure());
            }
            if (purpose == Purpose.CLOSE_WAIT || purpose == Purpose.CLOSE) {
                rememberFailure(
                        childFailureCode(purpose), childFailureMessage(purpose), lastFailure());
                outcomeUncertain |= effectsStarted;
                openedMenu = player.containerMenu != player.inventoryMenu;
                phase = Phase.COMPLETE;
                return TaskState.RUNNING;
            }
            return failOrClean(
                    childFailureCode(purpose), childFailureMessage(purpose), lastFailure());
        }
        switch (purpose) {
            case ACQUIRE_INPUT, ACQUIRE_FUEL, ACQUIRE_STATION -> {
                effectsStarted |= prerequisiteEffects(result);
                phase = Phase.PREPARE;
            }
            case PLACE_STATION -> {
                stationPlaced = true;
                effectsStarted = true;
                if (stationPos == null
                        || !player.level().getBlockState(stationPos)
                                .is(candidate.device().block)) {
                    return failOrClean("station_placement_unconfirmed",
                            "The first-person build task did not leave the required workstation.",
                            FailureType.UNKNOWN);
                }
                phase = Phase.PREPARE;
            }
            // 新批次按设备类型走近后重新找附近设备；恢复旧批次时保留原设备位置，避免把旧产物算到另一台。
            case MOVE_STATION -> {
                if (openMode == OpenMode.RESUME_BATCH) {
                    if (stationPos == null || !player.level().isLoaded(stationPos)
                            || !player.level().getBlockState(stationPos)
                                    .is(candidate.device().block)) {
                        return closeWithoutClaimingContents("station_target_lost",
                                "The exact claimed workstation was not present after returning to it.",
                                FailureType.TARGET_LOST);
                    }
                } else {
                    stationPos = nearest(candidate.device().block, 8, 8);
                }
                if (stationPos == null) {
                    return failOrClean("station_target_lost",
                            "The workstation was not visible after approach completed.",
                            FailureType.TARGET_LOST);
                }
                phase = Phase.OPEN;
            }
            case OPEN_STATION -> {
                waitMenuSince = player.level().getGameTime();
                phase = Phase.WAIT_MENU;
            }
            case LOAD_INPUT -> {
                effectsStarted = true;
                batchOutstanding = true;
                ownedInputLoaded = batchRaw;
                phase = Phase.LOAD_FUEL;
            }
            case LOAD_FUEL -> {
                effectsStarted = true;
                phase = Phase.CONFIRM_START;
                markCookEvidence();
            }
            case TAKE_OUTPUT -> phase = Phase.VERIFY_OUTPUT;
            case CLOSE_WAIT -> {
                openedMenu = false;
                ownedMenu = null;
                openRequested = false;
                closedWaitStartedTick = player.level().getGameTime();
                openMode = OpenMode.RESUME_BATCH;
                phase = Phase.WAIT_CLOSED;
            }
            case CLEAN_INPUT -> phase = Phase.VERIFY_CLEAN_INPUT;
            case CLOSE -> {
                openedMenu = false;
                ownedMenu = null;
                openRequested = false;
                return finishCleanup();
            }
            case ABANDON_CLOSE -> {
                openedMenu = false;
                ownedMenu = null;
                openRequested = false;
                phase = Phase.COMPLETE;
            }
        }
        routeFinishRequest();
        return TaskState.RUNNING;
    }

    private static Map<String, Object> semanticPrerequisiteFailure(TaskResult result) {
        if (result == null || result.data() == null) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "failure_type", "failure_code", "requires_decision",
                "requires_narration", "outcome_uncertain", "status",
                "recovery_options", "issues", "effects_observed", "effects_started", "planning_handoff")) {
            Object value = result.data().get(key);
            if (value != null) safe.put(key, value);
        }
        return Map.copyOf(safe);
    }

    private static boolean prerequisiteEffects(TaskResult result) {
        return result != null && result.data() != null
                && (Boolean.TRUE.equals(result.data().get("effects_observed"))
                    || Boolean.TRUE.equals(result.data().get("effects_started")));
    }

    // 还没装入加工材料、没有菜单待处理且上次结果不确定性未置位时，可换配方原料、燃料或设备重试。
    // 原料和燃料共用一种物品时，若原料份额仍够，只排除这次燃料选择，保留本来能做的配方。
    private boolean retryAnotherPreparationPlan(Purpose purpose, TaskResult result) {
        if (effectsStarted || openedMenu || uncertain(result)) return false;
        switch (purpose) {
            case ACQUIRE_INPUT -> {
                if (candidate == null) return false;
                if (candidate.input() == fuel
                        && PlayerInv.buildableCount(player.getInventory(), candidate.input()) >= batchRaw) {
                    recipePlanner.rejectFuel(fuel);
                } else rejectedInputCandidates.add(candidateKey(candidate));
            }
            case ACQUIRE_FUEL -> {
                if (fuel == null) return false;
                recipePlanner.rejectFuel(fuel);
            }
            case ACQUIRE_STATION -> {
                if (candidate == null) return false;
                rejectedDevices.add(candidate.device());
            }
            default -> {
                return false;
            }
        }
        if (planningAttempts.size() < 32) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("failed_prerequisite", purpose.name().toLowerCase());
            if (candidate != null) {
                attempt.put("recipe_id", candidate.recipeId().toString());
                attempt.put("input_item_id", BuiltInRegistries.ITEM.getKey(
                        candidate.input()).toString());
                attempt.put("device", BuiltInRegistries.BLOCK.getKey(
                        candidate.device().block).toString());
            }
            if (fuel != null) {
                attempt.put("fuel_item_id", BuiltInRegistries.ITEM.getKey(fuel).toString());
            }
            if (result != null && result.message() != null) {
                attempt.put("child_message", result.message());
            }
            planningAttempts.add(Map.copyOf(attempt));
        }
        candidate = null;
        fuel = null;
        fuelBurnTicks = 0;
        batchRaw = 0;
        batchFuel = 0;
        stationPos = null;
        phase = Phase.RESOLVE;
        return true;
    }

    private static boolean uncertain(TaskResult result) {
        if (result == null) return true;
        if (result.data() == null) return false;
        Object uncertain = result.data().get("outcome_uncertain");
        return Boolean.TRUE.equals(uncertain)
                || "uncertain".equals(String.valueOf(result.data().get("status")));
    }

    private static String candidateKey(CookingRecipe candidate) {
        return candidate.recipeId() + "|" + candidate.device().name()
                + "|" + BuiltInRegistries.ITEM.getKey(candidate.input());
    }

    private TaskState start(TaskRecord record, Purpose purpose) {
        activeRecord = record;
        activeChild = TaskFactory.create(player, record);
        activePurpose = purpose;
        extendParentPast(record.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    // 保留最早失败原因；炉里还有确认属于本任务的原料时先核对这一批，否则只关闭而不认领现有内容。
    private TaskState failOrClean(String code, String message, FailureType type) {
        rememberFailure(code, message, type);
        if (openedMenu && player.containerMenu instanceof AbstractFurnaceMenu) {
            if (stationClaimed && ownedInputLoaded > 0) {
                cleanupSnapshotReady = false;
                cleanupInputExpected = ItemStack.EMPTY;
                phase = Phase.RECONCILE;
                return TaskState.RUNNING;
            }
            return closeWithoutClaimingContents(code, message, type);
        }
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private void rememberFailure(String code, String message, FailureType type) {
        if (failureMessage == null) {
            failureCode = code;
            failureMessage = message;
            failureType = type == null ? FailureType.UNKNOWN : type;
        }
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    /** 数量归属无法继续确认时只安排关闭已绑定菜单，不再移动炉内任何槽位。 */
    // 停止认领和回收当前槽里的东西，仅尝试关闭相符类型的菜单，避免在来源已不明时继续拿取。
    private TaskState closeWithoutClaimingContents(
            String code, String message, FailureType type) {
        rememberFailure(code, message, type);
        outcomeUncertain |= effectsStarted;
        cleanupSnapshotReady = false;
        cleanupInputExpected = ItemStack.EMPTY;
        cleanupInventoryBefore = 0;
        cleanupTransferCount = 0;
        if (openedMenu && player.containerMenu instanceof AbstractFurnaceMenu
                && menuMatches()) {
            return start(new CloseMenuTaskRecord(
                    childId("abandon-close"), childDeadline(30L * 20L), ownedMenu),
                    Purpose.ABANDON_CLOSE);
        }
        openedMenu = false;
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private TaskState menuLost() {
        outcomeUncertain |= effectsStarted;
        openedMenu = false;
        String message = effectsStarted
                ? "The synchronized workstation menu changed after cooking effects began; "
                        + "blind retry is unsafe."
                : "The synchronized workstation menu changed before cooking began.";
        rememberFailure("cooking_menu_lost", message, FailureType.TARGET_LOST);
        phase = Phase.COMPLETE;
        return TaskState.RUNNING;
    }

    private String childFailureCode(Purpose purpose) {
        return switch (purpose) {
            case ACQUIRE_INPUT -> "missing_recipe_input";
            case ACQUIRE_FUEL -> "missing_allowed_fuel";
            case ACQUIRE_STATION -> "missing_workstation";
            case PLACE_STATION -> "station_placement_failed";
            case MOVE_STATION -> "station_unreachable";
            case OPEN_STATION -> "station_open_failed";
            case CLOSE -> "menu_close_unconfirmed";
            default -> "menu_transaction_unconfirmed";
        };
    }

    private String childFailureMessage(Purpose purpose) {
        return switch (purpose) {
            case ACQUIRE_INPUT ->
                    "Could not obtain enough selected recipe input from allowed_sources.";
            case ACQUIRE_FUEL ->
                    "Could not obtain enough allowed fuel from allowed_sources.";
            case ACQUIRE_STATION ->
                    "Could not obtain the selected cooking workstation from allowed_sources.";
            case PLACE_STATION ->
                    "Could not place the selected workstation through first-person building.";
            case MOVE_STATION ->
                    "Could not approach the selected workstation without altering terrain.";
            case OPEN_STATION ->
                    "Could not open the selected workstation through first-person interaction.";
            case CLOSE -> "The workstation menu close was not confirmed.";
            default -> "A synchronized workstation inventory transaction was not confirmed.";
        };
    }

    private AbstractFurnaceMenu furnaceMenu() {
        return menuMatches() ? ownedMenu : null;
    }

    // 类型对应配方，对象对应本次打开；编号可能复用，不能单凭编号或类型继续转移物品。
    private boolean menuMatches() {
        return ownedMenu != null && player.containerMenu == ownedMenu && candidate.device().matches(ownedMenu);
    }

    private boolean observingOwnClose() {
        if (player.containerMenu != player.inventoryMenu) return false;
        return activePurpose == Purpose.CLOSE || activePurpose == Purpose.CLOSE_WAIT
                || activePurpose == Purpose.ABANDON_CLOSE;
    }

    private void cancelActiveChild() {
        Task child = activeChild;
        Purpose purpose = activePurpose;
        activeChild = null;
        activeRecord = null;
        activePurpose = null;
        if (child == null) return;
        // 未确认的装料或取物可能已抵达服务端；先留下不确定性，再保证子任务也交付一次取消结果。
        outcomeUncertain |= purpose == Purpose.LOAD_INPUT || purpose == Purpose.LOAD_FUEL
                || purpose == Purpose.TAKE_OUTPUT || purpose == Purpose.CLEAN_INPUT;
        try { child.stop(player, Task.StopReason.REPLACED); }
        finally { outcomeUncertain |= uncertain(child.result(TaskState.CANCELLED)); }
    }

    // 读取菜单同步的数据：燃烧剩余时间、燃料总时长、加工进度和单次总时长；未提供的下标按零处理。
    private int data(AbstractFurnaceMenu menu, int index) {
        List<DataSlot> data =
                ((MenuDataSlotsAccessor) (Object) menu).maicraft$dataSlots();
        return index >= 0 && index < data.size() ? data.get(index).get() : 0;
    }

    private void markCookEvidence() {
        AbstractFurnaceMenu menu = furnaceMenu();
        lastCookEvidenceTick = player.level().getGameTime();
        lastCookEvidence = menu == null ? "" : data(menu, 0) + ":" + data(menu, 2)
                + ":" + data(menu, 3) + ":" + menu.getSlot(0).getItem().getCount()
                + ":" + menu.getSlot(2).getItem().getCount();
        renewCookProgressLease();
    }

    private void renewCookProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + COOK_PROGRESS_LEASE_TICKS);
    }

    // 只按同种方块和距离找最近位置，没有检查里面有没有别人放的东西，或当前能否走到。
    private BlockPos nearest(Block block, int horizontal, int vertical) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                for (int dy = -vertical; dy <= vertical; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!player.level().isLoaded(cursor)
                            || !player.level().getBlockState(cursor).is(block)) continue;
                    double distance = origin.distSqr(cursor);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    // 没有现成设备时，从近到远找五格范围内可放的位置，先试玩家附近高度，再试地表高度。
    private BlockPos placementSite() {
        BlockPos origin = player.blockPosition();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos cell = origin.offset(dx, dy, dz);
                        if (validSite(cell)) return cell;
                    }
                    int surfaceY = ClientSurfaceHeight.motionBlockingNoLeaves(
                            player.clientLevel, origin.getX() + dx, origin.getZ() + dz);
                    BlockPos surface = new BlockPos(
                            origin.getX() + dx, surfaceY, origin.getZ() + dz);
                    if (validSite(surface)) return surface;
                }
            }
        }
        return null;
    }

    // 要放的位置可替换、不占玩家身体，脚下有可托住的表面；真正放置还交给建造任务检查。
    private boolean validSite(BlockPos cell) {
        if (!player.level().isLoaded(cell)
                || !player.level().getBlockState(cell).canBeReplaced()
                || player.getBoundingBox().intersects(new AABB(cell))) return false;
        BlockPos support = cell.below();
        return player.level().isLoaded(support)
                && player.level().getBlockState(support)
                        .isFaceSturdy(player.level(), support, Direction.UP);
    }

    private boolean withinReach(BlockPos pos) {
        return player.distanceToSqr(Vec3.atCenterOf(pos)) <= 4.25D * 4.25D;
    }

    private int rawRemaining() {
        return rawRemaining(candidate);
    }

    // 用目标缺额除以每份原料产量，向上取整得到至少需要加工多少份；一份原料可能产生多个成品。
    private int rawRemaining(CookingRecipe cooking) {
        int missing = Math.max(0, r.count - outputCount());
        return Math.max(1, ceilDiv(missing, cooking.outputCount()));
    }

    private int outputCount() {
        return PlayerInv.buildableCount(
                player.getInventory(), BuiltInRegistries.ITEM.get(r.itemId));
    }

    private String childId(String label) {
        return r.getToolCallId() + "-cook-" + label + "-" + (++childSerial);
    }

    private long childDeadline(long ticks) {
        // 给子任务自己的初始无进展期限；它正常推进时可续时，再由 tickChild 同步到父任务。
        long lease = player.level().getGameTime() + ticks;
        extendParentPast(lease);
        return lease;
    }

    // 父任务至少比子任务晚一刻到期，留给父任务读取和处理子任务超时结果，避免同时截止先被外层截走。
    private void extendParentPast(long childDeadline) {
        r.extendDeadlineTo(childDeadline == Long.MAX_VALUE
                ? Long.MAX_VALUE : childDeadline + 1L);
    }

    @Override
    // 上层发现材料已够时，正在装料、收货、退料或处理已认领批次，仍必须先让本类完成必要的确认与收尾。
    public boolean mustSettleBeforeSatisfiedCancellation() {
        // 背包可能先更新，回执和关菜单却还没结束；让父任务继续同一炉的收尾，避免遗留鼠标物品。
        // 已准备好终态时也再推进一次，如实交付成功或失败，而不是仅因数量够了就改写成取消。
        if (phase == Phase.COMPLETE) return true;
        if (stationClaimed && ownedInputLoaded > 0) return true;
        if (phase == Phase.CLEANUP || (openedMenu && effectsStarted)) return true;
        if (activeChild == null || activePurpose == null) return false;
        return switch (activePurpose) {
            case LOAD_INPUT, LOAD_FUEL, TAKE_OUTPUT,
                    CLOSE_WAIT, CLEAN_INPUT,
                    CLOSE, ABANDON_CLOSE -> true;
            default -> false;
        };
    }

    @Override
    public void requestSatisfiedSettlement() {
        // 父目标可能由另一种可替代物品满足；这时只收尾当前这炉，不能继续追赶自己的旧成品数量。
        finishRequested = true;
        parentSatisfied = true;
    }

    private static int ceilDiv(long numerator, long denominator) {
        if (numerator <= 0L) return 0;
        return (int) Math.min(
                Integer.MAX_VALUE, (numerator + denominator - 1L) / denominator);
    }

    @Override
    // 任务整体被结束时停止子任务、尝试关闭当前使用过的菜单并停导航；已成功放置的设备和已经发生的加工不会撤销。
    protected void cleanup() {
        // 关着炉子等待时收到取消，炉内加工仍可能继续；没有待点击的子任务不等于没有未结效果。
        outcomeUncertain |= batchOutstanding;
        try {
            cancelActiveChild();
        } finally {
            closeOwnedMenuAtBoundary();
            super.cleanup();
        }
    }

    private void closeOwnedMenuAtBoundary() {
        // 只能关闭本次实际绑定的菜单；打开尚未确认或已换成别人的菜单时，保留当前界面。
        if (ownedMenu != null && player.containerMenu == ownedMenu && !ownedMenu.getCarried().isEmpty()) {
            // 分堆中断或外来鼠标物品需要保留界面，不能让上层清理覆盖搬运子任务的保留决定。
            outcomeUncertain = true;
        } else if (ownedMenu != null && player.containerMenu == ownedMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(
                        context, 20,
                        "the cooking task ended before its active menu transaction settled");
            } catch (RuntimeException closeFailure) {
                outcomeUncertain = true;
            }
        }
        openedMenu = false;
        ownedMenu = null;
    }

    @Override
    // 报告现在背包是否达到目标、选择了什么配方和设备、准备失败的历史以及是否仍有未确认效果，供上层决定后续。
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        int observed = outputCount();
        data.put("goal", "final_main_inventory_count");
        data.put("item_id", r.itemId.toString());
        data.put("required_final_count", r.count);
        data.put("initial_count", initialOutputCount);
        data.put("observed_final_count", observed);
        data.put("goal_satisfied", observed >= r.count);
        data.put("recipe_preference", r.preference.name().toLowerCase());
        data.put("allow_harm", r.allowHarm);
        data.put("supported_execution_devices",
                List.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker"));
        data.put("campfire_execution_supported", false);
        if (candidate != null) {
            data.put("recipe_id", candidate.recipeId().toString());
            data.put("device", BuiltInRegistries.BLOCK.getKey(candidate.device().block).toString());
            data.put("input_item_id", BuiltInRegistries.ITEM.getKey(candidate.input()).toString());
            data.put("recipe_output_count", candidate.outputCount());
        }
        if (fuel != null) {
            data.put("fuel_item_id", BuiltInRegistries.ITEM.getKey(fuel).toString());
        }
        data.put("station_placed", stationPlaced);
        // 未确认的搬运可能已生效；历史未知时省略“没有效果”的断言，只保留明确的不确定性。
        if (effectsStarted || !outcomeUncertain) data.put("effects_started", effectsStarted);
        data.put("batch_outstanding", batchOutstanding);
        data.put("stopped_because_parent_satisfied", parentSatisfied);
        if (batchOutstanding) {
            data.put("owned_input_loaded", ownedInputLoaded);
            data.put("owned_output_taken", ownedOutputTaken);
        }
        data.put("outcome_uncertain", outcomeUncertain);
        if (failedChildStage != null) {
            data.put("failed_child_stage", failedChildStage);
        }
        if (failedChildMessage != null) {
            data.put("failed_child_message", failedChildMessage);
        }
        if (!prerequisiteFailure.isEmpty()) {
            data.put("prerequisite_failure", prerequisiteFailure);
        }
        if (!planningAttempts.isEmpty()) {
            data.put("preparation_plan_failures", List.copyOf(planningAttempts));
        }
        if (failureCode != null) {
            data.put("decision", Map.of(
                    "required", true,
                    "reason_code", failureCode,
                    "recovery_options", List.of(
                            "change recipe_preference",
                            "expand allowed_sources or allowed_fuels",
                            "provide or clear a compatible workstation",
                            "retry after checking the synchronized workstation state")));
        }
        return data;
    }

    @Override
    protected String successMessage() {
        if (parentSatisfied && outputCount() < r.count)
            return "settled existing cooking work after the parent inventory goal was satisfied; carrying "
                    + outputCount() + " of the original " + r.count + " " + r.itemId;
        return "cooked " + r.itemId + " until the main inventory held at least "
                + r.count + " item(s), confirmed by live inventory state";
    }

    @Override
    protected String timeoutMessage() {
        return "cooking timed out; observed " + outputCount() + " of required final "
                + r.count + " in the main inventory";
    }

    @Override
    protected String cancelledMessage() {
        return "cooking interrupted; observed " + outputCount() + " of required final "
                + r.count + " in the main inventory";
    }
}
