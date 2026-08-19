package org.maiwithu.maicraft.core.task.craft;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.PathExecutor;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Receipt-driven recipe placement, result take, and menu close. */
public final class CraftCompanionTask extends AbstractCompanionTask<CraftTaskRecord> {
    private static final double AIM_CONVERGENCE_DOT = Math.cos(Math.toRadians(1.0D));
    /** Renewed only after concrete state progress; this is a no-progress lease, not a total cap. */
    private static final long PROGRESS_LEASE_TICKS = 60L * 20L;

    private enum Stage { CLOSE_WRONG_MENU, PREPARE_SURFACE, OPEN, PLACE, TAKE, CLOSE }
    private Stage stage;
    private RecipeHolder<?> recipe;
    private NativeActionReceipt openReceipt;
    private MenuReceipt menuReceipt;
    private int resultSlot = -1;
    private Item output;
    private int beforeCount;
    private int crafted;
    private long stationAimRequestedRevision = Long.MIN_VALUE;
    private boolean requiresTable;
    private final CraftingWorkstationCoordinator workstation =
            new CraftingWorkstationCoordinator();
    private BlockPos station;
    private Task surfaceChild;
    private TaskRecord surfaceRecord;
    private CraftingWorkstationCoordinator.Directive surfaceDirective;
    private int surfaceChildSerial;
    private boolean stationPlaced;
    private Vec3 stationAimPoint;
    private BlockPos stanceStation;
    private BlockPos surfaceMoveStance;
    private final Set<Long> rejectedStationStances = new LinkedHashSet<>();
    private String surfaceFailureCode;
    private String surfaceFailureDetail;

    public CraftCompanionTask(LocalPlayer player, CraftTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> candidate : manager.getRecipes()) {
            if (candidate.id().toString().equals(r.recipeId.toString())) { recipe = candidate; break; }
        }
        if (recipe == null) {
            fail("recipe is not known to this client: " + r.recipeId, FailureType.NO_MATERIAL);
            return;
        }
        requiresTable = requiresThreeByThree(recipe);
        station = r.station;
        boolean compatibleMenu = hasCraftGrid(
                requiresTable ? 3 : 2, requiresTable ? 3 : 2);
        if (compatibleMenu) {
            stage = Stage.PLACE;
        } else if (player.containerMenu != player.inventoryMenu) {
            stage = Stage.CLOSE_WRONG_MENU;
        } else {
            if (r.station != null) bindStation(r.station);
            stage = requiresTable ? Stage.PREPARE_SURFACE : Stage.PLACE;
        }
    }

    @Override protected TaskState onTick() {
        if (surfaceChild != null) return tickSurfaceChild();
        return switch (stage) {
            case CLOSE_WRONG_MENU -> closeWrongMenu();
            case PREPARE_SURFACE -> prepareSurface();
            case OPEN -> openStation();
            case PLACE -> placeRecipe();
            case TAKE -> takeResult();
            case CLOSE -> closeMenu();
            case RECLAIM_STATION -> reclaimTemporaryStation();
            case COLLECT_STATION -> collectTemporaryStation();
        };
    }

    private TaskState closeWrongMenu() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().close(context, 20);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("could not close the non-crafting menu: " + menuReceipt.detail(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        menuReceipt = null;
        renewProgressLease();
        if (requiresTable) {
            stage = Stage.PREPARE_SURFACE;
        } else stage = Stage.PLACE;
        return TaskState.RUNNING;
    }

    private TaskState prepareSurface() {
        // Keep one exact station identity for the lifetime of its active route. Re-running the
        // coordinator mid-route could switch to a newly carried/nearer table while the old nav
        // still owns the body.
        if (nav != null && CraftingWorkstationCoordinator.usableTable(player, station)) {
            var block = player.level().getBlockState(station).getBlock();
            return prepareExistingStation(new CraftingWorkstationCoordinator.Directive(
                    CraftingWorkstationCoordinator.Action.MOVE_TO_EXISTING,
                    station, block, "continue approaching the selected crafting table"));
        }
        CraftingWorkstationCoordinator.Directive directive = workstation.next(player, station);
        return switch (directive.action()) {
            case READY, MOVE_TO_EXISTING -> prepareExistingStation(directive);
            case PLACE_CARRIED -> {
                BlockPos site = directive.position();
                var table = directive.workstationBlock();
                if (table == null) {
                    workstation.reject(directive);
                    yield TaskState.RUNNING;
                }
                BuildTaskRecord.Target target = new BuildTaskRecord.Target(
                        table, table.asItem(), site,
                        BuiltInRegistries.BLOCK.getKey(table).toString(),
                        null, null, null).asItemPlace();
                yield startSurfaceChild(new BuildTaskRecord(
                        childId("place"), childDeadline(),
                        List.of(target), false, true, false), directive);
            }
            case UNAVAILABLE -> {
                if (surfaceFailureCode == null) {
                    surfaceFailureCode = "crafting_surface_unavailable";
                    surfaceFailureDetail = directive.detail();
                }
                String detail = surfaceFailureDetail == null
                        ? directive.detail() : surfaceFailureDetail;
                fail("recipe " + r.recipeId + " needs a 3x3 crafting surface, but "
                        + detail, FailureType.NO_SUPPORT);
                yield TaskState.FAILED;
            }
        };
    }

    /**
     * Approach the exact station selected by the coordinator. A block-id FIND child may silently
     * walk to a different table when several exist, so the physical route is compiled against this
     * concrete target and keeps that cell sacred. If its nearest interaction goal lands at a bad
     * first-person stance, finite visible stance candidates are tried without condemning the table.
     */
    private TaskState prepareExistingStation(
            CraftingWorkstationCoordinator.Directive directive) {
        BlockPos target = directive.position();
        if (!CraftingWorkstationCoordinator.usableTable(player, target)) {
            workstation.reject(directive);
            abandonStation();
            return TaskState.RUNNING;
        }
        bindStation(target);

        BlockHitResult visible = visibleStationHit();
        if (visible != null && !currentStanceRejected()) {
            stopNav();
            stationAimPoint = visible.getLocation();
            stationAimRequestedRevision = Long.MIN_VALUE;
            stage = Stage.OPEN;
            return TaskState.RUNNING;
        }

        if (nav == null) {
            BlockPos exact = station;
            nav = PlayerNav.to(player, () -> GoalCompiler.interact(exact), 1.0D,
                    () -> visibleStationHit() != null && !currentStanceRejected())
                    .withTerrainProbe();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                yield startAlternativeStationStance(directive);
            }
            case FAILED -> {
                recordSurfaceRouteFailure(directive, "route_exhausted", nav.failReason());
                stopNav();
                workstation.reject(directive);
                abandonStation();
                yield TaskState.RUNNING;
            }
        };
    }

    private TaskState startAlternativeStationStance(
            CraftingWorkstationCoordinator.Directive directive) {
        rejectedStationStances.add(PathExecutor.playerFeet(player).asLong());
        BlockPos stance = FirstPersonInteractionTargeting.nearestVisibleStand(
                player, directive.position(), 4.5D, rejectedStationStances);
        if (stance == null) {
            recordSurfaceRouteFailure(directive, "stances_exhausted",
                    "no remaining loaded standable cell has a visible workstation face");
            workstation.reject(directive);
            abandonStation();
            return TaskState.RUNNING;
        }
        surfaceMoveStance = stance;
        return startSurfaceChild(new MoveToTaskRecord(
                childId("move"), childDeadline(),
                (double) stance.getX(), (double) stance.getY(), (double) stance.getZ(),
                null, false), directive);
    }

    private TaskState startSurfaceChild(
            TaskRecord record, CraftingWorkstationCoordinator.Directive directive) {
        stopNav();
        surfaceDirective = directive;
        surfaceRecord = record;
        surfaceChild = TaskFactory.create(player, record);
        return TaskState.RUNNING;
    }

    private TaskState tickSurfaceChild() {
        Task active = surfaceChild;
        TaskRecord activeRecord = surfaceRecord;
        TaskState terminal = runChild(active);
        if (activeRecord != null) {
            r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
        }
        if (terminal == null) return TaskState.RUNNING;
        TaskResult result = active.result(terminal);
        CraftingWorkstationCoordinator.Directive completed = surfaceDirective;
        surfaceChild = null;
        surfaceRecord = null;
        surfaceDirective = null;

        if (terminal != TaskState.SUCCESS || result == null || !result.success()) {
            // A native effect can commit immediately before a child receipt times out. World fact
            // outranks the failed wrapper: never consume a table and then pretend it was absent.
            if (completed.action() == CraftingWorkstationCoordinator.Action.PLACE_CARRIED
                    && CraftingWorkstationCoordinator.usableTable(
                            player, completed.position())) {
                rememberTemporaryStation(completed.position());
                bindStation(completed.position());
                renewProgressLease();
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            if (surfaceMoveStance != null
                    && completed.action() != CraftingWorkstationCoordinator.Action.PLACE_CARRIED) {
                rejectedStationStances.add(surfaceMoveStance.asLong());
                recordSurfaceRouteFailure(completed, "stance_unreachable",
                        result == null ? "the exact interaction stance ended without a result"
                                : result.message());
                surfaceMoveStance = null;
                bindStation(completed.position());
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            workstation.reject(completed);
            abandonStation();
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }

        if (completed.action() == CraftingWorkstationCoordinator.Action.PLACE_CARRIED) {
            if (!CraftingWorkstationCoordinator.usableTable(player, completed.position())) {
                workstation.reject(completed);
                station = null;
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            rememberTemporaryStation(completed.position());
            bindStation(completed.position());
            renewProgressLease();
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }
        surfaceMoveStance = null;
        bindStation(completed.position());
        renewProgressLease();
        stage = Stage.PREPARE_SURFACE;
        return TaskState.RUNNING;
    }

    private void recordSurfaceRouteFailure(
            CraftingWorkstationCoordinator.Directive directive,
            String suffix,
            String detail) {
        boolean temporary = directive != null && directive.position() != null
                && CraftingWorkstationCoordinator.temporaryTable(
                        player, directive.position()) != null;
        surfaceFailureCode = (temporary
                ? "temporary_workstation_" : "crafting_surface_") + suffix;
        surfaceFailureDetail = temporary
                ? "the companion-owned temporary crafting table still exists, but " + detail
                        + "; another table will not be manufactured to replace it"
                : detail;
    }

    private TaskState openStation() {
        var context = ClientRuntime.requireContext(player);
        // Building deliberately sneaks while clicking support. Opening a station must explicitly
        // release that one-tick posture or vanilla treats right-click as bypass-use.
        InputDriver.halt(player);
        if (player.isCrouching()) {
            if (standingPoseFits()) {
                return TaskState.RUNNING;
            }
            rejectCurrentStance();
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }
        if (hasCraftGrid(3, 3)) {
            openReceipt = null;
            surfaceFailureCode = null;
            surfaceFailureDetail = null;
            renewProgressLease();
            stage = Stage.PLACE;
            return TaskState.RUNNING;
        }
        if (player.containerMenu != player.inventoryMenu) {
            openReceipt = null;
            workstation.reject(new CraftingWorkstationCoordinator.Directive(
                    CraftingWorkstationCoordinator.Action.READY, station,
                    "the selected block opened a menu without a compatible 3x3 crafting grid"));
            abandonStation();
            stage = Stage.CLOSE_WRONG_MENU;
            return TaskState.RUNNING;
        }
        if (!CraftingWorkstationCoordinator.usableTable(player, station)) {
            abandonStation();
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }
        if (openReceipt == null) {
            BlockHitResult visible = visibleStationHit();
            if (visible == null || currentStanceRejected()) {
                rejectCurrentStance();
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            if (stationAimPoint == null) stationAimPoint = visible.getLocation();
            InputDriver.lookAt(player, stationAimPoint);
            long revision = context.tickRevision();
            if (stationAimRequestedRevision == Long.MIN_VALUE) {
                stationAimRequestedRevision = revision;
                return TaskState.RUNNING;
            }
            Vec3 desired = stationAimPoint.subtract(player.getEyePosition());
            if (revision <= stationAimRequestedRevision
                    || (desired.lengthSqr() > 1.0e-8D
                            && player.getLookAngle().normalize().dot(desired.normalize())
                                    < AIM_CONVERGENCE_DOT)) {
                return TaskState.RUNNING;
            }
            HitResult aimed = Interaction.nativeRaytrace(player, 4.5);
            if (!(aimed instanceof BlockHitResult hit) || !hit.getBlockPos().equals(station)) {
                rejectCurrentStance();
                stage = Stage.PREPARE_SURFACE;
                return TaskState.RUNNING;
            }
            int beforeMenu = player.containerMenu.containerId;
            openReceipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit,
                    NativeConfirmation.menuChanged(beforeMenu), 30);
            return TaskState.RUNNING;
        }
        openReceipt = context.actions().poll(context, openReceipt);
        if (!openReceipt.terminal()) return TaskState.RUNNING;
        if (openReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            openReceipt = null;
            if (hasCraftGrid(3, 3)) {
                surfaceFailureCode = null;
                surfaceFailureDetail = null;
                renewProgressLease();
                stage = Stage.PLACE;
                return TaskState.RUNNING;
            }
            if (player.containerMenu != player.inventoryMenu) {
                workstation.reject(new CraftingWorkstationCoordinator.Directive(
                        CraftingWorkstationCoordinator.Action.READY, station,
                        "the selected block opened an incompatible menu"));
                abandonStation();
                stage = Stage.CLOSE_WRONG_MENU;
                return TaskState.RUNNING;
            }
            rejectCurrentStance();
            stage = Stage.PREPARE_SURFACE;
            return TaskState.RUNNING;
        }
        openReceipt = null;
        if (!hasCraftGrid(3, 3)) {
            workstation.reject(new CraftingWorkstationCoordinator.Directive(
                    CraftingWorkstationCoordinator.Action.READY, station,
                    "the selected block opened a menu without a compatible 3x3 crafting grid"));
            abandonStation();
            stage = Stage.CLOSE_WRONG_MENU;
            return TaskState.RUNNING;
        }
        surfaceFailureCode = null;
        surfaceFailureDetail = null;
        renewProgressLease();
        stage = Stage.PLACE;
        return TaskState.RUNNING;
    }

    private BlockHitResult visibleStationHit() {
        if (!CraftingWorkstationCoordinator.usableTable(player, station)
                || !CraftingWorkstationCoordinator.withinReach(player, station)) return null;
        return FirstPersonInteractionTargeting.visibleBlockHit(
                player.level(), player, player.getEyePosition(), station, 4.5D);
    }

    private boolean standingPoseFits() {
        return player.level().noCollision(
                player, player.getDimensions(Pose.STANDING).makeBoundingBox(player.position()));
    }

    private boolean currentStanceRejected() {
        return rejectedStationStances.contains(PathExecutor.playerFeet(player).asLong());
    }

    private void rejectCurrentStance() {
        rejectedStationStances.add(PathExecutor.playerFeet(player).asLong());
        stationAimPoint = null;
        stationAimRequestedRevision = Long.MIN_VALUE;
    }

    private void bindStation(BlockPos next) {
        if (next == null) {
            abandonStation();
            return;
        }
        if (stanceStation == null || !stanceStation.equals(next)) {
            rejectedStationStances.clear();
            stanceStation = next.immutable();
        }
        station = next.immutable();
        Block temporary = CraftingWorkstationCoordinator.temporaryTable(player, next);
        if (temporary != null) {
            temporaryStation = next.immutable();
            temporaryStationBlock = temporary;
        }
        stationAimPoint = null;
        stationAimRequestedRevision = Long.MIN_VALUE;
    }

    private void rememberTemporaryStation(BlockPos pos) {
        stationPlaced = true;
        CraftingWorkstationCoordinator.rememberTemporaryTable(player, pos);
        Block temporary = CraftingWorkstationCoordinator.temporaryTable(player, pos);
        if (temporary != null) {
            temporaryStation = pos.immutable();
            temporaryStationBlock = temporary;
        }
    }

    private void abandonStation() {
        stopNav();
        station = null;
        stanceStation = null;
        surfaceMoveStance = null;
        rejectedStationStances.clear();
        stationAimPoint = null;
        stationAimRequestedRevision = Long.MIN_VALUE;
    }

    private TaskState placeRecipe() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().placeRecipe(
                    context, recipe, false, MenuConfirmation.stateChanged(), 30);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("recipe placement was not confirmed: " + menuReceipt.detail(), FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        menuReceipt = null;
        renewProgressLease();
        resultSlot = findResultSlot();
        if (resultSlot < 0 || player.containerMenu.getSlot(resultSlot).getItem().isEmpty()) {
            fail("the current menu cannot form recipe " + r.recipeId
                    + "; open the required crafting surface and ensure its materials are present",
                    FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        output = player.containerMenu.getSlot(resultSlot).getItem().getItem();
        beforeCount = PlayerInv.count(player.getInventory(), output);
        stage = Stage.TAKE;
        return TaskState.RUNNING;
    }

    private TaskState takeResult() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().click(context, resultSlot, 0, ClickType.QUICK_MOVE,
                    (c, ignored) -> PlayerInv.count(c.player().getInventory(), output) > beforeCount
                            ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                    40);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("crafted result was not received: " + menuReceipt.detail(), FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        menuReceipt = null;
        renewProgressLease();
        crafted += PlayerInv.count(player.getInventory(), output) - beforeCount;
        if (crafted >= r.count) {
            stage = Stage.CLOSE;
        } else {
            stage = Stage.PLACE;
        }
        return TaskState.RUNNING;
    }

    private TaskState closeMenu() {
        var context = ClientRuntime.requireContext(player);
        if (menuReceipt == null) {
            menuReceipt = context.menus().close(context, 20);
            return TaskState.RUNNING;
        }
        menuReceipt = context.menus().poll(context, menuReceipt);
        if (!menuReceipt.terminal()) return TaskState.RUNNING;
        if (menuReceipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED) {
            menuReceipt = null;
            return beginTemporaryStationRecovery();
        }
        fail("craft completed but menu close was not confirmed: " + menuReceipt.detail(),
                FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    /**
     * A table placed by this client is travelling equipment, not a disposable build. Reclaim it
     * while the body is still at the verified interaction stance, before a following gather task
     * can descend or cross terrain that makes the old station expensive to reach again.
     */
    private TaskState beginTemporaryStationRecovery() {
        if (!requiresTable || temporaryStation == null || temporaryStationBlock == null) {
            return TaskState.SUCCESS;
        }
        Block owned = CraftingWorkstationCoordinator.temporaryTable(player, temporaryStation);
        if (owned == null) {
            stationRecoveryDetail = "the temporary crafting table was no longer present";
            return TaskState.SUCCESS;
        }
        if (!player.level().isLoaded(temporaryStation)) {
            // Ownership stays in the live-level ledger. A later craft can reuse it after the chunk
            // is loaded; an unloaded cell is never guessed at or broken speculatively.
            stationRecoveryDetail = "the temporary crafting table left the loaded client world";
            return TaskState.SUCCESS;
        }
        temporaryStationBlock = owned;
        temporaryStationItem = owned.asItem();
        if (temporaryStationItem == null
                || temporaryStationItem == net.minecraft.world.item.Items.AIR) {
            stationRecoveryDetail = "the temporary crafting surface has no recoverable item form";
            return TaskState.SUCCESS;
        }
        if (!canAcceptRecoveredStation()) {
            stationRecoveryDetail = "the temporary crafting table was left in place because the "
                    + "main inventory has no slot that can accept it";
            return TaskState.SUCCESS;
        }

        stationRecoveryAttempted = true;
        stationItemBeforeRecovery = PlayerInv.carriedCount(
                player.getInventory(), temporaryStationItem);
        stationDrops.clear();
        stationDrops.rememberExisting(player.level(), stationRecoveryBox());
        stationBreakTick = Long.MIN_VALUE;
        stationDropMissingSince = Long.MIN_VALUE;
        stationPickupTicks = 0;
        stationDropObserved = false;
        stationDropTarget = null;
        stage = Stage.RECLAIM_STATION;
        return TaskState.RUNNING;
    }

    private TaskState reclaimTemporaryStation() {
        if (temporaryStation == null || temporaryStationBlock == null) {
            return finishStationRecovery(false,
                    "the temporary crafting table identity was lost before recovery");
        }

        boolean exactTableStillPresent = player.level().isLoaded(temporaryStation)
                && player.level().getBlockState(temporaryStation).getBlock()
                        == temporaryStationBlock;
        BlockDigger.DigResult dig;
        if (!exactTableStillPresent) {
            // Once the synchronized block cell changes, the ray can no longer hit it. Settle the
            // already-submitted native break receipt instead of starting or abandoning an action.
            if (stationDigger.current() != null
                    && stationDigger.current().equals(temporaryStation)) {
                dig = stationDigger.settleGone(true);
            } else {
                CraftingWorkstationCoordinator.forgetTemporaryTable(player, temporaryStation);
                return finishStationRecovery(false,
                        "the temporary crafting table changed before our break was confirmed");
            }
        } else {
            dig = stationDigger.digTargetStep(temporaryStation);
        }

        return switch (dig) {
            case PROGRESSING -> TaskState.RUNNING;
            case BROKE_TARGET -> beginStationDropCollection();
            case BROKE_OCCLUDER -> finishStationRecovery(false,
                    "temporary workstation recovery changed an unexpected block");
            case NO_SHOT -> finishStationRecovery(false,
                    "the temporary crafting table was left in place because no verified "
                            + "first-person breaking ray was available");
        };
    }

    private TaskState beginStationDropCollection() {
        CraftingWorkstationCoordinator.forgetTemporaryTable(player, temporaryStation);
        stationBreakTick = player.level().getGameTime();
        stationDrops.discover(player.level(), stationRecoveryBox());
        renewProgressLease();
        stage = Stage.COLLECT_STATION;
        return TaskState.RUNNING;
    }

    private TaskState collectTemporaryStation() {
        if (recoveredStationInInventory()) {
            return finishStationRecovery(true,
                    "the temporary crafting table was broken and returned to the main inventory");
        }

        stationDrops.discover(player.level(), stationRecoveryBox());
        stationDrops.prune(player.clientLevel);
        ItemEntity nearest = stationDrops.live(player.clientLevel, Set.of()).stream()
                .filter(item -> item.getItem().is(temporaryStationItem))
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);

        long now = player.level().getGameTime();
        if (nearest == null) {
            stopNav();
            stationDropTarget = null;
            if (stationDropObserved) {
                if (stationDropMissingSince == Long.MIN_VALUE) stationDropMissingSince = now;
                if (now - stationDropMissingSince <= STATION_PICKUP_SYNC_TICKS) {
                    return TaskState.RUNNING;
                }
                return finishStationRecovery(false,
                        "the table drop disappeared without a synchronized inventory increase");
            }
            if (stationBreakTick != Long.MIN_VALUE
                    && now - stationBreakTick <= STATION_DROP_SYNC_TICKS) {
                return TaskState.RUNNING;
            }
            return finishStationRecovery(false,
                    "the confirmed table break produced no attributable loaded drop");
        }

        stationDropObserved = true;
        stationDropMissingSince = Long.MIN_VALUE;
        if (stationDropTarget == null || stationDropTarget.getId() != nearest.getId()) {
            stopNav();
            stationDropTarget = nearest;
        }

        if (insideNativePickupEnvelope(nearest)) {
            if (nav != null) nav.pause();
            if (nearest.hasPickUpDelay()) {
                stationPickupTicks = 0;
                return TaskState.RUNNING;
            }
            if (++stationPickupTicks <= STATION_PICKUP_SYNC_TICKS) {
                return TaskState.RUNNING;
            }
            return finishStationRecovery(false,
                    "the body reached the table drop, but the authoritative inventory did not "
                            + "accept it");
        }
        stationPickupTicks = 0;

        if (nav == null) {
            nav = PlayerNav.toRevalidating(player, this::stationDropGoal, 1.0D,
                    this::recoveredStationInInventory, PlayerNav.ContextProvider.DEFAULT);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                nav.pause();
                yield TaskState.RUNNING;
            }
            case FAILED -> finishStationRecovery(false,
                    "the attributable table drop could not be reached without altering terrain: "
                            + nav.failReason());
        };
    }

    private GoalCompiler.Compiled stationDropGoal() {
        ItemEntity drop = stationDropTarget;
        return drop == null || drop.isRemoved()
                ? null : GoalCompiler.standOn(drop.blockPosition());
    }

    private boolean recoveredStationInInventory() {
        return temporaryStationItem != null
                && PlayerInv.carriedCount(player.getInventory(), temporaryStationItem)
                        > stationItemBeforeRecovery;
    }

    private boolean insideNativePickupEnvelope(ItemEntity item) {
        return player.getBoundingBox().inflate(1.0D).intersects(item.getBoundingBox());
    }

    private AABB stationRecoveryBox() {
        return new AABB(temporaryStation).inflate(STATION_DROP_SCAN_RADIUS);
    }

    private boolean canAcceptRecoveredStation() {
        ItemStack wanted = new ItemStack(temporaryStationItem);
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, player.getInventory().items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(stack, wanted)
                    && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private TaskState finishStationRecovery(boolean recovered, String detail) {
        stationRecovered = recovered;
        stationRecoveryDetail = detail;
        stopNav();
        stationDropTarget = null;
        if (stationDigger.current() != null) stationDigger.cancel();
        return TaskState.SUCCESS;
    }

    @Override
    public boolean mustSettleBeforeSatisfiedCancellation() {
        // Before the result click there is no committed craft to finish: an externally satisfied
        // parent may cancel and cleanup will simply return the grid. Once the click is submitted,
        // however, its receipt, menu close, and recovery of a self-placed temporary workstation
        // form one indivisible terminal tail. Otherwise the inventory fact becomes true first and
        // the semantic parent can strand the table immediately before a gathering child departs.
        return stage == Stage.CLOSE
                || stage == Stage.RECLAIM_STATION
                || stage == Stage.COLLECT_STATION
                || (stage == Stage.TAKE && menuReceipt != null);
    }

    private int findResultSlot() {
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            if (player.containerMenu.getSlot(i) instanceof ResultSlot) return i;
        }
        return -1;
    }

    private boolean hasCraftGrid(int width, int height) {
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container instanceof CraftingContainer grid
                    && grid.getWidth() >= width && grid.getHeight() >= height) return true;
        }
        return false;
    }

    private static boolean requiresThreeByThree(RecipeHolder<?> holder) {
        if (!(holder.value() instanceof CraftingRecipe crafting)) return true;
        if (crafting instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return crafting.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).count() > 4;
    }

    @Override protected void cleanup() {
        if (surfaceChild != null) {
            surfaceChild.stop(player, Task.StopReason.REPLACED);
            surfaceChild = null;
        }
        surfaceRecord = null;
        surfaceDirective = null;
        openReceipt = null;
        if (stationDigger.current() != null) stationDigger.cancel();
        stationDrops.clear();
        if (player.containerMenu != player.inventoryMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                menuReceipt = context.menus().closeForTaskBoundary(
                        context, 20,
                        "the crafting task ended before its active menu transaction settled");
            } catch (RuntimeException closeFailure) {
                // The task is already terminal, so there is no later task tick to retry from. The
                // vanilla close path is the final safety net: it returns grid/cursor contents and
                // sends the normal close-container packet instead of leaving movement under a GUI.
                try {
                    player.closeContainer();
                } catch (RuntimeException ignored) { }
            }
        }
        menuReceipt = null;
        workstation.close();
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("recipe", r.recipeId.toString());
        data.put("crafted", crafted);
        data.put("crafting_table_placed", stationPlaced);
        data.put("crafting_table_recovery_attempted", stationRecoveryAttempted);
        data.put("crafting_table_recovered", stationRecovered);
        if (stationRecoveryDetail != null) {
            data.put("crafting_table_recovery_detail", stationRecoveryDetail);
        }
        if (surfaceFailureCode != null) {
            data.put("failure_code", surfaceFailureCode);
            data.put("failure_detail", surfaceFailureDetail == null ? "" : surfaceFailureDetail);
            if (surfaceFailureCode.startsWith("crafting_surface_")) {
                data.put("crafting_surface_prerequisite_item_ids",
                        CraftingWorkstationCoordinator.prerequisiteItemIds().stream()
                                .map(Object::toString).toList());
            }
        }
        if (station != null) {
            data.put("crafting_station", Map.of(
                    "x", station.getX(), "y", station.getY(), "z", station.getZ()));
        }
        return data;
    }
    @Override protected String successMessage() { return "crafted " + crafted + " item(s) via " + r.recipeId; }
    @Override protected String cancelledMessage() { return "craft interrupted"; }

    private String childId(String label) {
        return r.getToolCallId() + "-craft-surface-" + label + "-" + (++surfaceChildSerial);
    }

    private long childDeadline() {
        return r.getDeadlineGameTime();
    }

    private void renewProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
    }
}
