// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 铺轨的两点连接：offsets 写两端的轨道，两端先各放一格轨道（蓝图 cells 里写），再手持轨道右键起点、右键终点，
 * Create 在两格之间铺出一段弯道或长直道，按长度扣轨道。起点方向 Create 自己朝终点翻；转太急、坡太陡、
 * 太远、轨道不够这些由服务器判，判不过时动作栏会说原因，这一段如实交回。两头的轨道互相记着对方才算铺上。
 * 依据 Create 6.0.11 的 TrackBlockItem.useOn 与 TrackPlacement.tryConnect。
 */
final class TrackInstallation implements CreateInstallation {

    static final String KIND = "create:track_connection";
    /** 一次铺轨两端最远多少格的硬上限（服务器配置 maxTrackPlacementLength 最大 128，默认 32）。类别：游戏事实。 */
    private static final int HARD_LIMIT = 128;
    /** 轨道的点击面：轨道只有四分之一格高，点它的顶面。 */
    private static final double TRACK_HEIGHT = 0.25;

    private final CreateCompat compat;
    private final PlayerServices services;

    TrackInstallation(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String kind() {
        return KIND;
    }

    @Override public Optional<String> problem(Installation installation) {
        return shapeProblem(installation.cells());
    }

    /** 写法对不对，纯函数：两端，不是同一格也不挨着，不超过铺轨的硬上限。 */
    static Optional<String> shapeProblem(List<BlockPos> cells) {
        if (cells.size() != 2) return Optional.of("铺轨写两端的轨道：offsets 两格");
        BlockPos from = cells.get(0);
        BlockPos to = cells.get(1);
        if (from.distSqr(to) <= 2) return Optional.of("两端要隔开至少一格：挨着的轨道放直了自己就连着，不用铺");
        if (from.distSqr(to) > (long) HARD_LIMIT * HARD_LIMIT) {
            return Optional.of("两端相距超过 " + HARD_LIMIT + " 格，一次铺不了：中间加一格轨道分成两段");
        }
        return Optional.empty();
    }

    // 看两端是轨道 → 拿够轨道 → 清掉手里记着的旧起点 → 点起点 → 点终点；上一步做完才建下一步。
    @Override public Action install(Installation installation, Permissions permissions) {
        BlockPos from = installation.cells().get(0);
        BlockPos to = installation.cells().get(1);
        return new StepsInOrder("铺轨 " + from.toShortString() + " → " + to.toShortString(), List.of(
                new StepsInOrder.Step("看两端是轨道", () -> endsReady(from, to)),
                new StepsInOrder.Step("拿轨道", () -> services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(trackItem(from)), tracksFor(from, to), "铺轨"), permissions)),
                new StepsInOrder.Step("清掉手里轨道记着的旧起点", () -> clearStale(from, permissions)),
                new StepsInOrder.Step("点起点", () -> clickFirst(from, permissions)),
                new StepsInOrder.Step("点终点", () -> clickSecond(from, to, permissions))));
    }

    @Override public boolean installed(ClientLevel level, Installation installation) {
        return installation.cells().size() == 2
                && compat.trackConnected(level, installation.cells().get(0), installation.cells().get(1));
    }

    /** 要备几件轨道，纯函数：按两端直线距离估（Create 直道一格一件、弯道每两段一件，不会多过这个数）。 */
    static int tracksFor(BlockPos from, BlockPos to) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(from.distSqr(to))));
    }

    // 两端各要一格轨道：轨道物品只从轨道上起铺。这是游戏事实，不是预判。
    private Action endsReady(BlockPos from, BlockPos to) {
        return (Check) context -> {
            ClientLevel level = context.player() == null ? null : context.player().level();
            if (level == null) return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "角色不在世界里，铺不了轨"));
            for (BlockPos end : List.of(from, to)) {
                if (!compat.track(level.getBlockState(end))) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            end.toShortString() + " 不是轨道：两端要先各放一格轨道", "在蓝图 cells 里两端写上 create:track"));
                }
            }
            return ActionStatus.done();
        };
    }

    // 手里的轨道记着别处的起点：潜行右键一下清掉（Create 的规矩），不清的话第一下会从那一格铺过来。
    private Action clearStale(BlockPos from, Permissions permissions) {
        Optional<BlockPos> stale = heldTrack(from).flatMap(compat::trackStart);
        if (stale.isEmpty() || stale.get().equals(from)) return null;
        return services.sneak().sneaking(new HandThenClick(services, "清掉手里轨道记着的旧起点", from, top(from), permissions,
                Optional::empty, () -> context -> heldTrack(from).flatMap(compat::trackStart).isEmpty()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING));
    }

    // 第一下：手里的轨道记下起点就算点上了；已经记着它就不用再点（再点会被当成终点）。
    private Action clickFirst(BlockPos from, Permissions permissions) {
        if (heldTrack(from).flatMap(compat::trackStart).filter(from::equals).isPresent()) return null;
        return new HandThenClick(services, "用轨道点起点", from, top(from), permissions, () -> trackToHand(from),
                () -> context -> heldTrack(from).flatMap(compat::trackStart).filter(from::equals).isPresent()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    // 第二下：两头的轨道互相记着对方才算铺上；服务器判不过时没有变化，等不到确认如实交回。
    private Action clickSecond(BlockPos from, BlockPos to, Permissions permissions) {
        return new HandThenClick(services, "用轨道点终点", to, top(to), permissions, () -> trackToHand(from),
                () -> context -> compat.trackConnected(context.level(), from, to)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    private Optional<Action> trackToHand(BlockPos from) {
        return heldTrack(from).isPresent() ? Optional.empty() : services.toMainhand().moveToMainhand(trackItem(from));
    }

    // 主手拿着的是不是起点那种轨道。
    private Optional<ItemStack> heldTrack(BlockPos from) {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return Optional.empty();
        ItemStack held = current.localPlayer().getMainHandItem();
        return !held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(trackItem(from))
                ? Optional.of(held) : Optional.empty();
    }

    // 铺哪种轨道：起点那格轨道自己的物品（安山轨道是 create:track，别的材质各有各的）。
    private String trackItem(BlockPos from) {
        PlayerContext current = services.context().get();
        if (current == null || current.level() == null) return CreateMachineCatalog.MOD_ID + ":track";
        return BuiltInRegistries.ITEM.getKey(current.level().getBlockState(from).getBlock().asItem()).toString();
    }

    private static AABB top(BlockPos at) {
        return new AABB(at.getX(), at.getY(), at.getZ(), at.getX() + 1, at.getY() + TRACK_HEIGHT, at.getZ() + 1);
    }

    /** 两步之间的瞬时检查：一刻就给出结论的动作。 */
    @FunctionalInterface
    private interface Check extends Action {
        @Override default String describe() {
            return "检查现场";
        }
    }
}
