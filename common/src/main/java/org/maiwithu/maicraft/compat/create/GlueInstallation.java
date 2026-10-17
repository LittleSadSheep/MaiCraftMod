// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用强力胶粘一片：offsets 写这一片的两个对角。粘上的方块在装置动起来时跟着一起动（轴承、活塞、滑轮拖得走它们），
 * 载具组装也用这一段。Create 的做法是手持强力胶点第一角、再点第二角确认，确认时客户端发一包给服务器；
 * 这一段拿到胶、走到第二角跟前，照那一下发同一包。服务器核对两角离得不远、中间方块连着、身上有胶，
 * 扣一点胶耐久生成一块胶；正好盖住这一片的胶出现了才算粘上。
 */
final class GlueInstallation implements CreateInstallation {

    static final String KIND = "create:super_glue";
    private static final String GLUE = "create:super_glue";
    /** 两角最远不到这么多格（服务器核对的上限）。类别：游戏事实（SuperGlueSelectionPacket）。 */
    private static final int MAX_SPAN = 25;
    /** 发了以后等那块胶同步过来最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;

    private final CreateCompat compat;
    private final PlayerServices services;

    GlueInstallation(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String kind() {
        return KIND;
    }

    @Override public Optional<String> problem(Installation installation) {
        return shapeProblem(installation.cells());
    }

    /** 写法对不对，纯函数：两个对角，相距不到 25 格。 */
    static Optional<String> shapeProblem(List<BlockPos> cells) {
        if (cells.size() != 2) return Optional.of("强力胶写这一片的两个对角：offsets 两格");
        if (!cells.get(1).closerThan(cells.get(0), MAX_SPAN)) {
            return Optional.of("两个对角相距要不到 " + MAX_SPAN + " 格");
        }
        return Optional.empty();
    }

    // 拿一支强力胶 → 走到第二角跟前 → 照 Create 选区确认那一下发包 → 等那块胶出现。
    @Override public Action install(Installation installation, Permissions permissions) {
        BlockPos from = installation.cells().get(0);
        BlockPos to = installation.cells().get(1);
        return new StepsInOrder("用强力胶粘 " + from.toShortString() + " 到 " + to.toShortString(), List.of(
                new StepsInOrder.Step("拿强力胶", () -> services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(GLUE), 1, "粘装置"), permissions)),
                new StepsInOrder.Step("走到第二角跟前", () -> services.approach().toward(ApproachTarget.ofBlock(to), permissions)),
                new StepsInOrder.Step("粘上", () -> new Glues(from, to))));
    }

    @Override public boolean installed(ClientLevel level, Installation installation) {
        return installation.cells().size() == 2 && compat.glued(level, installation.cells().get(0), installation.cells().get(1));
    }

    /** 发一次粘的包，等正好盖住这一片的胶出现；等不到如实说，不再发第二次（再发会多扣一点胶）。 */
    private final class Glues implements Action {
        private final BlockPos from;
        private final BlockPos to;
        private boolean sent;
        private int waited;

        Glues(BlockPos from, BlockPos to) {
            this.from = from;
            this.to = to;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (!sent) {
                compat.glue(from, to);
                sent = true;
                return ActionStatus.progressed();
            }
            if (compat.glued(context.player().level(), from, to)) return ActionStatus.done();
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "发了强力胶的选区，胶没出现：两角之间的方块要连着，身上要有强力胶，人要站在第二角跟前"));
        }

        @Override public String describe() {
            return sent ? "等强力胶粘上" : "用强力胶粘这一片";
        }
    }
}
