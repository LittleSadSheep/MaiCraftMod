// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机械动力里"放东西的地方"怎么用：置物台手持东西右键顶面放上去一整叠（原有的先收回背包），空手右键顶面收回；
 * 工作盆空手右键取出盆里的东西。压机、机械手、鼓风机以它们加工处的置物台为投料口与出口，
 * 搅拌器以正下方第二格的工作盆为出口。
 *
 * <p>看得见的才读：台面、盆里、带上画出来的东西。料扔进工作盆、扔进石磨这类"扔进去"的投法现在不做，
 * 投料时如实给不出动作。
 */
final class StationHandling {

    /** 顶面的点击框往里收这么多（格）：点在边上容易打到侧面，置物台只认点在顶面上的右键。 */
    private static final double FACE_INSET = 0.15;
    /** 顶面点击框的厚度（格）。 */
    private static final double FACE_DEPTH = 0.02;

    private final CreateCompat compat;
    private final PlayerServices services;

    StationHandling(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 这一格画出来的东西：台面上的、盆里的、带上的；加工机器看它加工处的那一格。 */
    List<ItemStack> shownAt(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at) {
        return switch (work) {
            case DEPOT -> compat.depotItems(level, at).orElse(List.of());
            case BASIN -> compat.basinItems(level, at).orElse(List.of());
            case BELT -> compat.beltItems(level, at).orElse(List.of());
            case PRESS, MIXER, DEPLOYER, FAN -> ProcessingSpots.workCell(work, state, at)
                    .map(cell -> itemsAt(level, cell)).orElse(List.of());
            default -> List.of();
        };
    }

    /** 出口现在有什么：置物台与工作盆就是自己，加工机器是加工处的置物台、工作盆或传送带。 */
    List<ItemStack> outputAt(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at) {
        return shownAt(work, level, state, at);
    }

    /** 往这台机器投料的动作：置物台放在自己顶面，压机、机械手、鼓风机放在加工处的置物台上；别的给不出。 */
    Optional<Action> feed(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at,
            String itemId, Permissions permissions) {
        return depotFor(work, level, state, at).map(depot -> putOnDepot(level, depot, itemId, permissions));
    }

    /** 从出口取东西的动作：置物台空手右键顶面；工作盆（搅拌器下方那个也算）空手右键；别的给不出。 */
    Optional<Action> take(CreateMachineCatalog.Work work, ClientLevel level, BlockState state, BlockPos at,
            Permissions permissions) {
        Optional<BlockPos> depot = depotFor(work, level, state, at);
        if (depot.isPresent()) {
            return Optional.of(emptyHandTake(level, depot.get(), "从置物台取回东西",
                    () -> compat.depotItems(level, depot.get()).orElse(List.of()), permissions));
        }
        Optional<BlockPos> basin = basinFor(work, state, at).filter(cell -> compat.basinItems(level, cell).isPresent());
        return basin.map(cell -> emptyHandTake(level, cell, "从工作盆取出东西",
                () -> compat.basinItems(level, cell).orElse(List.of()), permissions));
    }

    /** 库存请求器的状态：上次读到的现货摘要与多久以前读的；现货是放在网络里的东西，制作预览不算。 */
    MachineState stockState(ClientLevel level, BlockPos at) {
        Optional<CreateStations.StockSnapshot> snapshot = compat.stock(level, at);
        if (snapshot.isEmpty()) return MachineState.unknown("这一格读不到库存请求器");
        List<MachineState.Shown> shown = snapshot.get().entries().stream()
                .map(entry -> new MachineState.Shown(entry.itemId(), (int) Math.min(Integer.MAX_VALUE, entry.count())))
                .toList();
        String note = shown.isEmpty()
                ? "库存请求器还没读到现货：打开它的界面会向物流网络要一份"
                : "上次读到的现货，" + snapshot.get().ticksSinceUpdate() / 20 + " 秒前";
        return CreateReadings.still(shown, note);
    }

    // 加工处的那一格放着置物台时它就是投料口与出口；置物台自己就是自己。
    private Optional<BlockPos> depotFor(CreateMachineCatalog.Work work, ClientLevel level, BlockState state,
            BlockPos at) {
        Optional<BlockPos> cell = switch (work) {
            case DEPOT -> Optional.of(at);
            case PRESS, DEPLOYER, FAN -> ProcessingSpots.workCell(work, state, at);
            default -> Optional.empty();
        };
        return cell.filter(spot -> compat.depotItems(level, spot).isPresent());
    }

    private static Optional<BlockPos> basinFor(CreateMachineCatalog.Work work, BlockState state, BlockPos at) {
        return switch (work) {
            case BASIN -> Optional.of(at);
            case MIXER, PRESS -> ProcessingSpots.workCell(work, state, at);
            default -> Optional.empty();
        };
    }

    // 一格里看得见的东西：置物台、工作盆、传送带按这个顺序认。
    private List<ItemStack> itemsAt(ClientLevel level, BlockPos cell) {
        return compat.depotItems(level, cell)
                .or(() -> compat.basinItems(level, cell))
                .or(() -> compat.beltItems(level, cell))
                .orElse(List.of());
    }

    // 放上置物台：先把这样东西换到主手，走到看得见顶面的地方，对准顶面右键；
    // 台上出现了这样东西、手上那叠不在了才算放上去（原生会先把台上原有的收回背包，再放手上这一整叠）。
    private Action putOnDepot(ClientLevel level, BlockPos depot, String itemId, Permissions permissions) {
        return new HandThenClick(services, "把 " + itemId + " 放上置物台", depot, topFace(level, depot), permissions,
                () -> holding(itemId) ? Optional.empty() : services.toMainhand().moveToMainhand(itemId),
                () -> context -> {
                    boolean onDepot = compat.depotItems(context.level(), depot).orElse(List.of()).stream()
                            .anyMatch(stack -> idOf(stack).equals(itemId));
                    boolean leftHand = !idOf(context.localPlayer().getMainHandItem()).equals(itemId);
                    return onDepot && leftHand ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING;
                });
    }

    // 空手取：先腾空手（手里拿着东西右键就成了放），走过去对准顶面右键；那一格的东西少了才算取到。
    private Action emptyHandTake(ClientLevel level, BlockPos cell, String what,
            Supplier<List<ItemStack>> contents, Permissions permissions) {
        return new HandThenClick(services, what, cell, topFace(level, cell), permissions,
                () -> mainhandEmpty() ? Optional.empty() : services.toMainhand().emptyMainhand(),
                () -> {
                    int before = total(contents.get());
                    return context -> total(contents.get()) < before ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING;
                });
    }

    /**
     * 一格方块顶面的点击框（世界坐标）：贴着方块形状最高处，四边往里收一点。
     * 置物台、工作盆都比一整格矮，点在整格顶上会打空。
     */
    static AABB topFace(ClientLevel level, BlockPos at) {
        VoxelShape shape = level.getBlockState(at).getShape(level, at);
        double top = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        return new AABB(at.getX() + FACE_INSET, at.getY() + top - FACE_DEPTH, at.getZ() + FACE_INSET,
                at.getX() + 1 - FACE_INSET, at.getY() + top, at.getZ() + 1 - FACE_INSET);
    }

    private boolean holding(String itemId) {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null
                && idOf(current.localPlayer().getMainHandItem()).equals(itemId);
    }

    private boolean mainhandEmpty() {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null && current.localPlayer().getMainHandItem().isEmpty();
    }

    private static int total(List<ItemStack> stacks) {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
