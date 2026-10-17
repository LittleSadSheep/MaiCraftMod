// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 连两台相邻机械合成器的入口：拿扳手右键其中一台某一面上靠另一台的那条边，两台的入口归成一组，
 * 往哪一台送料都分到这一组里。点的那一面外面不能贴着实心面（一般是背面）。两台归成同一组才算连上。
 */
final class CrafterLinkInstallation implements CreateInstallation {

    static final String KIND = "create:crafter_link";
    private static final String WRENCH = "create:wrench";

    private final CreateCompat compat;
    private final PlayerServices services;

    CrafterLinkInstallation(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String kind() {
        return KIND;
    }

    @Override public Optional<String> problem(Installation installation) {
        return shapeProblem(installation.cells());
    }

    /** 形状对不对，纯函数：两格，紧挨着。 */
    static Optional<String> shapeProblem(List<BlockPos> cells) {
        if (cells.size() != 2) return Optional.of("连入口只连两台机械合成器：cells 写这两台");
        if (cells.get(0).distManhattan(cells.get(1)) != 1) return Optional.of("连入口的两台机械合成器要紧挨着");
        return Optional.empty();
    }

    // 拿扳手 → 点第一台靠第二台那条边：点哪一面在出手前按现场挑（Create 认的那几面里第一个）。
    @Override public Action install(Installation installation, Permissions permissions) {
        BlockPos first = installation.cells().get(0);
        BlockPos second = installation.cells().get(1);
        Direction toward = Direction.fromDelta(second.getX() - first.getX(), second.getY() - first.getY(),
                second.getZ() - first.getZ());
        PlayerContext current = services.context().get();
        List<Direction> faces = current == null || current.level() == null || toward == null ? List.of()
                : compat.crafterLinkFaces(current.level(), first, toward);
        if (faces.isEmpty()) {
            return failing("两台机械合成器之间没有能点的边：点的那一面外面要空着（一般点背面）");
        }
        AABB edge = CrafterSettings.edge(first, faces.getFirst(), toward);
        return new HandThenClick(services, "用扳手连两台机械合成器的入口", first, edge, permissions,
                () -> holdingWrench() ? Optional.empty()
                        : services.toMainhand().moveToMainhand(WRENCH).or(() -> Optional.of(failing("身上没有扳手"))),
                () -> context -> compat.craftersConnected(context.level(), first, second)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    @Override public boolean installed(ClientLevel level, Installation installation) {
        return installation.cells().size() == 2
                && compat.craftersConnected(level, installation.cells().get(0), installation.cells().get(1));
    }

    private boolean holdingWrench() {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null && !current.localPlayer().getMainHandItem().isEmpty()
                && BuiltInRegistries.ITEM.getKey(current.localPlayer().getMainHandItem().getItem()).toString().equals(WRENCH);
    }

    private static Action failing(String reason) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, reason));
            }

            @Override public String describe() {
                return reason;
            }
        };
    }
}
