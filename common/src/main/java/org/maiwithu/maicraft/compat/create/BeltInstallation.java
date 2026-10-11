// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 传送带：两端各一根同轴向的轴，手持传送带先右键第一根、再右键第二根，Create 在两根轴之间铺出整条带
 * （中间原有的同轴向的轴变成带轮），一条带只用掉一件传送带。
 *
 * <p>形状规则按 Create 自己的：沿东西或南北直走，或上下斜四十五度、或竖直；两端距离小于服务器配置的最长长度。
 * 手里的传送带记着别处的第一根轴时，先潜行右键清掉它，免得第一下就把旧的那根和这根连起来。
 * 装成没有按最终结构核对：每格都是同一条带、带长与格数一致、序号从带头数起。
 */
final class BeltInstallation implements CreateInstallation {

    static final String KIND = "create:belt";
    static final String CONNECTOR = "create:belt_connector";

    private final CreateCompat compat;
    private final PlayerServices services;

    BeltInstallation(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String kind() {
        return KIND;
    }

    @Override public Optional<String> problem(Installation installation) {
        Optional<Integer> limit = services.context().get() == null ? Optional.empty()
                : Optional.of(compat.beltMaxLength());
        return shapeProblem(installation.cells(), limit);
    }

    /**
     * 传送带形状合不合 Create 的规则，纯函数。
     *
     * @param cells     从一端到另一端的格
     * @param maxLength 服务器配置的最长长度；读不到（不在世界里）为空，这时不查长度
     */
    static Optional<String> shapeProblem(List<BlockPos> cells, Optional<Integer> maxLength) {
        if (cells.size() < 2) return Optional.of("传送带至少两格：两端各一根轴");
        BlockPos first = cells.getFirst();
        BlockPos last = cells.getLast();
        int dx = last.getX() - first.getX();
        int dy = last.getY() - first.getY();
        int dz = last.getZ() - first.getZ();
        if (dx != 0 && dz != 0) return Optional.of("传送带只能沿东西或南北方向走，不能在水平面上斜着走");
        int run = Math.max(Math.abs(dx), Math.abs(dz));
        if (dy != 0 && run != 0 && Math.abs(dy) != run) {
            return Optional.of("斜的传送带只能上下斜四十五度：每走一格升或降一格");
        }
        BlockPos step = new BlockPos(Integer.signum(dx), Integer.signum(dy), Integer.signum(dz));
        int distance = Math.max(run, Math.abs(dy));
        if (cells.size() != distance + 1) return Optional.of("格子要从一端连续排到另一端，一格不落");
        for (int i = 1; i < cells.size(); i++) {
            if (!cells.get(i).subtract(cells.get(i - 1)).equals(step)) {
                return Optional.of("格子要从一端连续排到另一端，第 " + (i + 1) + " 格没接上");
            }
        }
        if (maxLength.isPresent() && first.distSqr(last) >= (long) maxLength.get() * maxLength.get()) {
            return Optional.of("两端距离超过了服务器配置的传送带最长长度 " + maxLength.get() + "（要小于它）");
        }
        return Optional.empty();
    }

    @Override public Action install(Installation installation, Permissions permissions) {
        BlockPos first = installation.cells().getFirst();
        BlockPos last = installation.cells().getLast();
        // 拿一件传送带 → 两端都是轴 → 清掉手里记着的旧轴 → 点第一根 → 点第二根，上一步做完才建下一步。
        return new StepsInOrder("装传送带 " + first.toShortString() + " → " + last.toShortString(), List.of(
                new StepsInOrder.Step("拿一件传送带", () -> services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(CONNECTOR), 1, "装传送带"), permissions)),
                new StepsInOrder.Step("看两端的轴", () -> endsReady(first, last)),
                new StepsInOrder.Step("清掉手里记着的旧轴", () -> clearStale(first, permissions)),
                new StepsInOrder.Step("点第一根轴", () -> clickFirst(first, permissions)),
                new StepsInOrder.Step("点第二根轴", () -> clickSecond(installation, permissions))));
    }

    @Override public boolean installed(ClientLevel level, Installation installation) {
        return builtAlong(installation.cells(), at -> compat.beltSegment(level, at));
    }

    /**
     * 这些格是不是正好一整条带，纯函数：每格都读得到传送带、控制格是两端之一、带长等于格数、
     * 序号从控制格那一端数起。
     */
    static boolean builtAlong(List<BlockPos> cells, Function<BlockPos, Optional<CreateBelts.Segment>> segmentAt) {
        int n = cells.size();
        BlockPos controller = null;
        for (int i = 0; i < n; i++) {
            Optional<CreateBelts.Segment> segment = segmentAt.apply(cells.get(i));
            if (segment.isEmpty() || segment.get().length() != n) return false;
            if (controller == null) controller = segment.get().controller();
            if (!segment.get().controller().equals(controller)) return false;
            boolean fromFirst = controller.equals(cells.getFirst());
            if (!fromFirst && !controller.equals(cells.getLast())) return false;
            if (segment.get().index() != (fromFirst ? i : n - 1 - i)) return false;
        }
        return controller != null;
    }

    // 两端各要一根轴：传送带物品只认点在轴上，点别的方块 Create 直接不认。这是游戏事实，不是预判。
    private Action endsReady(BlockPos first, BlockPos last) {
        return (Check) context -> {
            ClientLevel level = context.player() == null ? null : context.player().level();
            if (level == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "角色不在世界里，装不了传送带"));
            }
            for (BlockPos end : List.of(first, last)) {
                if (!compat.shaftAt(level, end)) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            end.toShortString() + " 不是轴：传送带两端要先各放一根轴",
                            "在蓝图里两端写上与带子垂直的 create:shaft"));
                }
            }
            return ActionStatus.done();
        };
    }

    // 手里的传送带记着别处的轴：潜行右键一下就清掉（Create 的规矩），不清的话第一下会去连那一根。
    private Action clearStale(BlockPos first, Permissions permissions) {
        Optional<BlockPos> stale = heldConnector().flatMap(compat::beltFirstShaft);
        if (stale.isEmpty() || stale.get().equals(first)) return null;
        return services.sneak().sneaking(new HandThenClick(services, "清掉手里传送带记着的旧轴", first,
                new AABB(first), permissions, Optional::empty,
                () -> context -> heldConnector().flatMap(compat::beltFirstShaft).isEmpty()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING));
    }

    // 第一下：手里那件传送带记下这根轴就算点上了；已经记着它就不用再点。
    private Action clickFirst(BlockPos first, Permissions permissions) {
        if (heldConnector().flatMap(compat::beltFirstShaft).filter(first::equals).isPresent()) return null;
        return new HandThenClick(services, "用传送带点第一根轴", first, new AABB(first), permissions,
                this::connectorToHand,
                () -> context -> heldConnector().flatMap(compat::beltFirstShaft).filter(first::equals).isPresent()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    // 第二下：两根轴之间出现整条带才算装上；没出现就是 Create 不认这两根轴，如实交回。
    private Action clickSecond(Installation installation, Permissions permissions) {
        BlockPos last = installation.cells().getLast();
        return new HandThenClick(services, "用传送带点第二根轴", last, new AABB(last), permissions,
                this::connectorToHand,
                () -> context -> installed(context.level(), installation)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    private Optional<Action> connectorToHand() {
        return heldConnector().isPresent() ? Optional.empty() : services.toMainhand().moveToMainhand(CONNECTOR);
    }

    private Optional<ItemStack> heldConnector() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return Optional.empty();
        ItemStack held = current.localPlayer().getMainHandItem();
        return !held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(CONNECTOR)
                ? Optional.of(held) : Optional.empty();
    }

    /** 两步之间的瞬时检查：一刻就给出结论的动作。 */
    @FunctionalInterface
    private interface Check extends Action {
        @Override default String describe() {
            return "检查现场";
        }
    }
}
