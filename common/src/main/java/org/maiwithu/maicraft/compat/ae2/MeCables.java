// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * ME 线缆与部件怎么装：线缆不是能直接放的方块，是装在线缆格中间的部件——手持线缆潜行右键旁边那一格朝这边的面，
 * AE2 发现那一格装不下就放到这一格（像放火把）；部件是手持部件潜行右键线缆格那一面的那一截，AE2 按点到的那一截认面。
 * 一格一格接、一个一个装，每一下都看现场出现了线缆、那一面出现了部件才算数；潜行是为了不把右键交给那台设备开界面。
 */
final class MeCables {

    private final Ae2Compat compat;
    private final PlayerServices services;

    MeCables(Ae2Compat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    /** 一段线缆的形状对不对：种类是线缆、格子一格接一格。 */
    Optional<String> problem(Installation installation) {
        if (!compat.cableItem(installation.kind())) {
            return Optional.of(installation.kind() + " 不是 AE2 的线缆：ME 线缆的安装段种类写线缆物品 ID，例如 ae2:fluix_glass_cable");
        }
        return chainProblem(installation.cells());
    }

    /** 格子要一格接一格（相邻两格只差一步），纯函数。 */
    static Optional<String> chainProblem(List<BlockPos> cells) {
        for (int i = 1; i < cells.size(); i++) {
            if (cells.get(i).distManhattan(cells.get(i - 1)) != 1) {
                return Optional.of("线缆的格子要一格接一格，第 " + (i + 1) + " 格没挨着上一格");
            }
        }
        return Optional.empty();
    }

    /** 这一段每格都装着这种线缆才算装成。 */
    boolean installed(ClientLevel level, Installation installation) {
        for (BlockPos cell : installation.cells()) {
            Optional<Ae2Grid.GridCell> here = compat.gridCell(level, cell);
            if (here.isEmpty() || !here.get().cableItem().map(installation.kind()::equals).orElse(false)) return false;
        }
        return true;
    }

    /** 一格一格接线缆：先拿够线缆，再从第一格起，每格点它前一格（第一格点挨着它的设备或线缆）朝它的那一面。 */
    Action lay(Installation installation, Permissions permissions) {
        List<StepsInOrder.Step> steps = new ArrayList<>();
        steps.add(new StepsInOrder.Step("拿 " + installation.cells().size() + " 根线缆", () -> services.needs()
                .actionFor(new ItemRequest(WantedItem.ofItem(installation.kind()), installation.cells().size(),
                        "接 ME 线缆"), permissions)));
        for (int i = 0; i < installation.cells().size(); i++) {
            int index = i;
            steps.add(new StepsInOrder.Step("接第 " + (i + 1) + " 格", () -> layOne(installation, index, permissions)));
        }
        return new StepsInOrder("接 ME 线缆", steps);
    }

    // 接一格：这一格已经有线缆就跳过；点前一格（或挨着的设备）朝这一格的面，这一格出现线缆才算接上。
    private Action layOne(Installation installation, int index, Permissions permissions) {
        ClientLevel level = level();
        BlockPos cell = installation.cells().get(index);
        if (level == null || compat.gridCell(level, cell).flatMap(Ae2Grid.GridCell::cableItem).isPresent()) {
            return null;
        }
        Optional<BlockPos> from = index > 0 ? Optional.of(installation.cells().get(index - 1)) : supportOf(level, cell);
        if (from.isEmpty()) return null;
        Direction toward = directionTo(from.get(), cell);
        boolean onCable = compat.gridCell(level, from.get()).map(Ae2Grid.GridCell::cableBus).orElse(false);
        AABB face = onCable ? CableGeometry.coreFace(from.get(), toward) : PlacementSpots.faceBox(from.get(), toward);
        String cable = installation.kind();
        return services.sneak().sneaking(new HandThenClick(services, "接线缆到 " + cell.toShortString(), from.get(),
                face, permissions, () -> holding(cable) ? Optional.empty() : services.toMainhand().moveToMainhand(cable),
                () -> context -> compat.gridCell(context.level(), cell).flatMap(Ae2Grid.GridCell::cableItem).isPresent()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING));
    }

    // 第一格挨着的、能点的那一格：先找 ME 设备（来源那一头），没有就找任意实心方块。
    private Optional<BlockPos> supportOf(ClientLevel level, BlockPos cell) {
        for (Direction side : Direction.values()) {
            if (compat.gridCell(level, cell.relative(side)).isPresent()) return Optional.of(cell.relative(side));
        }
        for (Direction side : Direction.values()) {
            BlockPos beside = cell.relative(side);
            if (!level.getBlockState(beside).isAir() && !level.getBlockState(beside).canBeReplaced()) {
                return Optional.of(beside);
            }
        }
        return Optional.empty();
    }

    /** 这个部件装不装得上这一格的这一面：宿主要是线缆格、那一面空着、物品是 AE2 的部件。 */
    Optional<String> partProblem(PartCell part, String hostBlockId) {
        if (!compat.partItem(part.itemId())) return Optional.of(part.itemId() + " 不是 AE2 的部件");
        if (!hostBlockId.equals(Ae2MachineCatalog.CABLE_BUS)) {
            return Optional.of("AE2 的部件装在线缆上：宿主要是一格 ME 线缆，现在是 " + hostBlockId);
        }
        ClientLevel level = level();
        if (level != null) {
            Optional<Ae2Grid.PartView> taken = compat.gridCell(level, part.host())
                    .map(cell -> cell.parts().get(part.side()));
            if (taken.isPresent() && !taken.get().itemId().equals(part.itemId())) {
                return Optional.of("那一面已经装着 " + taken.get().itemId());
            }
        }
        return Optional.empty();
    }

    /** 这一面现在是不是装着这个部件。 */
    boolean mounted(ClientLevel level, PartCell part) {
        return compat.gridCell(level, part.host()).map(cell -> cell.parts().get(part.side()))
                .map(view -> view.itemId().equals(part.itemId())).orElse(false);
    }

    /** 装一个部件：拿到它、换到主手、走到看得见那一面的地方、潜行右键那一截，那一面出现它才算装上。 */
    Action mount(PartCell part, Permissions permissions) {
        return new StepsInOrder("把 " + part.itemId() + " 装到 " + part.host().toShortString(), List.of(
                new StepsInOrder.Step("拿一个 " + part.itemId(), () -> services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(part.itemId()), 1, "装 ME 部件"), permissions)),
                new StepsInOrder.Step("潜行右键线缆那一面", () -> services.sneak().sneaking(new HandThenClick(services,
                        "装 " + part.itemId(), part.host(), CableGeometry.arm(part.host(), part.side()), permissions,
                        () -> holding(part.itemId()) ? Optional.empty()
                                : services.toMainhand().moveToMainhand(part.itemId()),
                        () -> context -> mounted(context.level(), part)
                                ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING)))));
    }

    private boolean holding(String itemId) {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return false;
        ItemStack held = current.localPlayer().getMainHandItem();
        return !held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId);
    }

    private static Direction directionTo(BlockPos from, BlockPos to) {
        BlockPos delta = to.subtract(from);
        Direction direction = Direction.fromDelta(delta.getX(), delta.getY(), delta.getZ());
        return direction == null ? Direction.UP : direction;
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
