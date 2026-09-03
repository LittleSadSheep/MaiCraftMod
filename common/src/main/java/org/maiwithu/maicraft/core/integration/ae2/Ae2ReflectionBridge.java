// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuVisibility;

/**
 * Reflection-only access to the AE2 client menu protocol.
 *
 * <p>The common module deliberately has no AE2 linkage. Loading is all-or-nothing: if the
 * installed AE2 build does not expose the exact protocol used here, the integration reports
 * itself unavailable instead of discovering the mismatch after a transaction has started.</p>
 */
final class Ae2ReflectionBridge {
    record Entry(ResourceLocation itemId, long serial, long storedAmount,
                 boolean craftable, ItemStack sample) {
        Entry {
            if (itemId == null || sample == null || sample.isEmpty()) {
                throw new IllegalArgumentException("AE2 entry identity and sample are required");
            }
            storedAmount = Math.max(0L, storedAmount);
            sample = sample.copyWithCount(1);
        }
    }

    record Availability(Optional<Ae2ReflectionBridge> bridge, String detail) {
        boolean available() { return bridge.isPresent(); }
    }

    private static final Availability AVAILABILITY = load();

    private final Class<?> storageMenuClass;
    private final Class<?> craftAmountMenuClass;
    private final Class<?> craftConfirmMenuClass;
    private final Class<?> itemKeyClass;
    private final Class<?> cableBusBlockEntityClass;
    private final Class<?> terminalPartClass;
    private final Method getClientRepo;
    private final Method getLinkStatus;
    private final Method linkConnected;
    private final Method getAllEntries;
    private final Method entryGetSerial;
    private final Method entryGetWhat;
    private final Method entryGetStoredAmount;
    private final Method entryIsCraftable;
    private final Method keyGetId;
    private final Method keyToStack;
    private final Method handleInteraction;
    private final Object pickupSingle;
    private final Object pickupOrSetDown;
    private final Object autoCraft;
    private final Method confirmCraftAmount;
    private final Method craftConfirmHasNoCpu;
    private final Method craftConfirmGetPlan;
    private final Method craftConfirmStartJob;
    private final Field craftConfirmSubmitError;
    private final Method submitErrorResult;
    private final Method submitResultSuccessful;
    private final Method submitResultErrorCode;
    private final Method cableBusGetPart;

    private Ae2ReflectionBridge(
            Class<?> storageMenuClass,
            Class<?> craftAmountMenuClass,
            Class<?> craftConfirmMenuClass,
            Class<?> itemKeyClass,
            Class<?> cableBusBlockEntityClass,
            Class<?> terminalPartClass,
            Method getClientRepo,
            Method getLinkStatus,
            Method linkConnected,
            Method getAllEntries,
            Method entryGetSerial,
            Method entryGetWhat,
            Method entryGetStoredAmount,
            Method entryIsCraftable,
            Method keyGetId,
            Method keyToStack,
            Method handleInteraction,
            Object pickupSingle,
            Object pickupOrSetDown,
            Object autoCraft,
            Method confirmCraftAmount,
            Method craftConfirmHasNoCpu,
            Method craftConfirmGetPlan,
            Method craftConfirmStartJob,
            Field craftConfirmSubmitError,
            Method submitErrorResult,
            Method submitResultSuccessful,
            Method submitResultErrorCode,
            Method cableBusGetPart) {
        this.storageMenuClass = storageMenuClass;
        this.craftAmountMenuClass = craftAmountMenuClass;
        this.craftConfirmMenuClass = craftConfirmMenuClass;
        this.itemKeyClass = itemKeyClass;
        this.cableBusBlockEntityClass = cableBusBlockEntityClass;
        this.terminalPartClass = terminalPartClass;
        this.getClientRepo = getClientRepo;
        this.getLinkStatus = getLinkStatus;
        this.linkConnected = linkConnected;
        this.getAllEntries = getAllEntries;
        this.entryGetSerial = entryGetSerial;
        this.entryGetWhat = entryGetWhat;
        this.entryGetStoredAmount = entryGetStoredAmount;
        this.entryIsCraftable = entryIsCraftable;
        this.keyGetId = keyGetId;
        this.keyToStack = keyToStack;
        this.handleInteraction = handleInteraction;
        this.pickupSingle = pickupSingle;
        this.pickupOrSetDown = pickupOrSetDown;
        this.autoCraft = autoCraft;
        this.confirmCraftAmount = confirmCraftAmount;
        this.craftConfirmHasNoCpu = craftConfirmHasNoCpu;
        this.craftConfirmGetPlan = craftConfirmGetPlan;
        this.craftConfirmStartJob = craftConfirmStartJob;
        this.craftConfirmSubmitError = craftConfirmSubmitError;
        this.submitErrorResult = submitErrorResult;
        this.submitResultSuccessful = submitResultSuccessful;
        this.submitResultErrorCode = submitResultErrorCode;
        this.cableBusGetPart = cableBusGetPart;
    }

    static Availability availability() {
        return AVAILABILITY;
    }

    boolean isStorageMenu(Object menu) {
        return menu != null && storageMenuClass.isInstance(menu);
    }

    boolean isCraftAmountMenu(Object menu) {
        return menu != null && craftAmountMenuClass.isInstance(menu);
    }

    boolean isCraftConfirmMenu(Object menu) {
        return menu != null && craftConfirmMenuClass.isInstance(menu);
    }

    List<Direction> fixedTerminalSides(Object blockEntity) {
        if (blockEntity == null || !cableBusBlockEntityClass.isInstance(blockEntity)) {
            return List.of();
        }
        List<Direction> result = new ArrayList<>();
        for (Direction side : Direction.values()) {
            Object part = invoke(cableBusGetPart, blockEntity, side);
            if (part != null && terminalPartClass.isInstance(part)) result.add(side);
        }
        return List.copyOf(result);
    }

    boolean hasFixedTerminal(Object blockEntity, Direction side) {
        if (blockEntity == null || !cableBusBlockEntityClass.isInstance(blockEntity)) return false;
        Object part = invoke(cableBusGetPart, blockEntity, side);
        return part != null && terminalPartClass.isInstance(part);
    }

    /** Returns {@code null} until the synchronized client repository exists. */
    List<Entry> entries(Object menu) {
        requireStorageMenu(menu);
        Object repository = invoke(getClientRepo, menu);
        if (repository == null) return null;
        Object raw = invoke(getAllEntries, repository);
        if (!(raw instanceof Iterable<?> iterable)) {
            throw new Ae2ProtocolException("AE2 repository entries are not iterable");
        }
        List<Entry> result = new ArrayList<>();
        for (Object value : iterable) {
            if (value == null) continue;
            Object what = invoke(entryGetWhat, value);
            if (what == null || !itemKeyClass.isInstance(what)) continue;
            Object rawId = invoke(keyGetId, what);
            if (!(rawId instanceof ResourceLocation id)) {
                throw new Ae2ProtocolException("AE2 item key did not expose a ResourceLocation");
            }
            Object rawSample = invoke(keyToStack, what, 1);
            if (!(rawSample instanceof ItemStack sample) || sample.isEmpty()) {
                throw new Ae2ProtocolException("AE2 item key did not expose an ItemStack sample");
            }
            result.add(new Entry(
                    id,
                    ((Number) invoke(entryGetSerial, value)).longValue(),
                    ((Number) invoke(entryGetStoredAmount, value)).longValue(),
                    (Boolean) invoke(entryIsCraftable, value),
                    sample));
        }
        return List.copyOf(result);
    }

    boolean connected(Object menu) {
        requireStorageMenu(menu);
        Object status = invoke(getLinkStatus, menu);
        if (status == null) throw new Ae2ProtocolException("AE2 storage menu returned no link status");
        return (Boolean) invoke(linkConnected, status);
    }

    void pickupSingle(Object menu, long serial) {
        requireStorageMenu(menu);
        requireVisibleMenu(menu);
        invoke(handleInteraction, menu, serial, pickupSingle);
    }

    void returnCarriedToNetwork(Object menu) {
        requireStorageMenu(menu);
        requireVisibleMenu(menu);
        invoke(handleInteraction, menu, -1L, pickupOrSetDown);
    }

    void startAutoCraft(Object menu, long serial) {
        requireStorageMenu(menu);
        requireVisibleMenu(menu);
        invoke(handleInteraction, menu, serial, autoCraft);
    }

    void confirmCraftAmount(Object menu, int amount) {
        if (!isCraftAmountMenu(menu)) {
            throw new Ae2ProtocolException("the active menu is not AE2 CraftAmountMenu");
        }
        // Auto-start stays disabled. The state machine waits for plan/CPU synchronization and
        // submits exactly one job itself.
        requireVisibleMenu(menu);
        invoke(confirmCraftAmount, menu, amount, false, false);
    }

    boolean craftConfirmHasNoCpu(Object menu) {
        requireCraftConfirmMenu(menu);
        return (Boolean) invoke(craftConfirmHasNoCpu, menu);
    }

    boolean craftConfirmPlanReady(Object menu) {
        requireCraftConfirmMenu(menu);
        return invoke(craftConfirmGetPlan, menu) != null;
    }

    void startCraftingJob(Object menu) {
        requireCraftConfirmMenu(menu);
        requireVisibleMenu(menu);
        invoke(craftConfirmStartJob, menu);
    }

    /** Returns {@code null} while no synchronized submission failure exists. */
    String craftConfirmSubmitFailure(Object menu) {
        requireCraftConfirmMenu(menu);
        Object wrapper = read(craftConfirmSubmitError, menu);
        if (wrapper == null) throw new Ae2ProtocolException("AE2 crafting submit status is unavailable");
        Object result = invoke(submitErrorResult, wrapper);
        if (result == null || (Boolean) invoke(submitResultSuccessful, result)) return null;
        Object code = invoke(submitResultErrorCode, result);
        return code instanceof Enum<?> value ? value.name().toLowerCase(Locale.ROOT) : "unknown";
    }

    private void requireStorageMenu(Object menu) {
        if (!isStorageMenu(menu)) throw new Ae2ProtocolException("the active menu is not AE2 MEStorageMenu");
    }

    private static void requireVisibleMenu(Object menu) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(menu instanceof AbstractContainerMenu container) || minecraft.player == null
                || minecraft.player.containerMenu != container || !MenuVisibility.matches(minecraft, container)) {
            throw new Ae2ProtocolException("the corresponding AE2 GUI must be visibly open before an operation");
        }
    }

    private void requireCraftConfirmMenu(Object menu) {
        if (!isCraftConfirmMenu(menu)) {
            throw new Ae2ProtocolException("the active menu is not AE2 CraftConfirmMenu");
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getTargetException() : failure;
            throw new Ae2ProtocolException("AE2 invocation failed: " + method.getName(), cause);
        } catch (RuntimeException failure) {
            throw new Ae2ProtocolException("AE2 invocation failed: " + method.getName(), failure);
        }
    }

    private static Object read(Field field, Object receiver) {
        try {
            return field.get(receiver);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new Ae2ProtocolException("AE2 field access failed: " + field.getName(), failure);
        }
    }

    private static Availability load() {
        try {
            Class<?> storageMenu = Class.forName("appeng.menu.me.common.MEStorageMenu");
            Class<?> clientRepo = Class.forName("appeng.menu.me.common.IClientRepo");
            Class<?> entry = Class.forName("appeng.menu.me.common.GridInventoryEntry");
            Class<?> itemKey = Class.forName("appeng.api.stacks.AEItemKey");
            Class<?> inventoryAction = Class.forName("appeng.helpers.InventoryAction");
            Class<?> linkStatus = Class.forName("appeng.api.storage.ILinkStatus");
            Class<?> craftAmount = Class.forName("appeng.menu.me.crafting.CraftAmountMenu");
            Class<?> craftConfirm = Class.forName("appeng.menu.me.crafting.CraftConfirmMenu");
            Class<?> cableBus = Class.forName("appeng.blockentity.networking.CableBusBlockEntity");
            Class<?> terminalPart = Class.forName("appeng.parts.reporting.AbstractTerminalPart");
            Class<?> syncableSubmitResult =
                    Class.forName("appeng.menu.me.crafting.CraftConfirmMenu$SyncableSubmitResult");
            Class<?> craftingSubmitResult =
                    Class.forName("appeng.api.networking.crafting.ICraftingSubmitResult");
            Map<String, Object> actions = Arrays.stream(inventoryAction.getEnumConstants())
                    .collect(Collectors.toMap(value -> ((Enum<?>) value).name(), Function.identity()));
            Ae2ReflectionBridge bridge = new Ae2ReflectionBridge(
                    storageMenu,
                    craftAmount,
                    craftConfirm,
                    itemKey,
                    cableBus,
                    terminalPart,
                    storageMenu.getMethod("getClientRepo"),
                    storageMenu.getMethod("getLinkStatus"),
                    linkStatus.getMethod("connected"),
                    clientRepo.getMethod("getAllEntries"),
                    entry.getMethod("getSerial"),
                    entry.getMethod("getWhat"),
                    entry.getMethod("getStoredAmount"),
                    entry.getMethod("isCraftable"),
                    itemKey.getMethod("getId"),
                    itemKey.getMethod("toStack", Integer.TYPE),
                    storageMenu.getMethod("handleInteraction", Long.TYPE, inventoryAction),
                    requireAction(actions, "PICKUP_SINGLE"),
                    requireAction(actions, "PICKUP_OR_SET_DOWN"),
                    requireAction(actions, "AUTO_CRAFT"),
                    craftAmount.getMethod(
                            "confirm", Integer.TYPE, Boolean.TYPE, Boolean.TYPE),
                    craftConfirm.getMethod("hasNoCPU"),
                    craftConfirm.getMethod("getPlan"),
                    craftConfirm.getMethod("startJob"),
                    craftConfirm.getField("submitError"),
                    syncableSubmitResult.getMethod("result"),
                    craftingSubmitResult.getMethod("successful"),
                    craftingSubmitResult.getMethod("errorCode"),
                    cableBus.getMethod("getPart", Direction.class));
            return new Availability(Optional.of(bridge), "available");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            String detail = failure.getClass().getSimpleName();
            if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                detail += ": " + failure.getMessage();
            }
            return new Availability(Optional.empty(), detail);
        }
    }

    private static Object requireAction(Map<String, Object> actions, String name) {
        Object value = actions.get(name);
        if (value == null) throw new IllegalStateException("AE2 InventoryAction is missing " + name);
        return value;
    }
}

final class Ae2ProtocolException extends RuntimeException {
    Ae2ProtocolException(String message) {
        super(message);
    }

    Ae2ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
