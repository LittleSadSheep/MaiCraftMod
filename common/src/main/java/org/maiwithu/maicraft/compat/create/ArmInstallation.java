// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.HandThenClick;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 放机械臂并选好它的取放点：手里拿着机械臂物品，挨个右键每个取放点（点一下是取、点两下是放，Create 记在客户端），
 * 选完再把机械臂放下去，Create 在放下时把选好的点交给服务器。机械臂那一格不写进蓝图的 cells，由这一段放：
 * 先放好就没法选点了。放下后机械臂记着的点和要的一样才算装成。
 *
 * <p>offsets 第一格是机械臂，后面是取放点；roles 和它一一对应，第一格写 arm，取放点写 take 或 deposit。
 * 选点时不换手里那一叠：Create 认的是手里那一件物品本身，换一叠前面选的就清空了。
 */
final class ArmInstallation implements CreateInstallation {

    static final String KIND = "create:mechanical_arm";
    static final String ARM = "arm";
    static final String TAKE = "take";
    static final String DEPOSIT = "deposit";
    private static final String ARM_ITEM = "create:mechanical_arm";
    /** 放下后等服务器把取放点同步回来最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;
    private static final double FACE_DEPTH = 0.02;
    private static final double FACE_INSET = 0.2;

    private final CreateCompat compat;
    private final PlayerServices services;

    ArmInstallation(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String kind() {
        return KIND;
    }

    @Override public Optional<String> problem(Installation installation) {
        PlayerContext current = services.context().get();
        int range = current == null ? 0 : compat.armRange();
        return shapeProblem(installation.cells(), installation.roles(), range);
    }

    /**
     * 写法对不对，纯函数：至少一个取放点；roles 第一格 arm、其余 take / deposit；点不重复、不是机械臂那一格；
     * 读得到范围时每个点都要在范围以内（Create 放下时把范围外的点丢掉）。
     */
    static Optional<String> shapeProblem(List<BlockPos> cells, List<String> roles, int range) {
        if (cells.size() < 2) return Optional.of("机械臂至少要一个取放点：offsets 第一格写机械臂，后面写取放点");
        if (roles.size() != cells.size()) return Optional.of("机械臂要写 roles：第一格 arm，取放点写 take 或 deposit");
        if (!roles.getFirst().equals(ARM)) return Optional.of("roles 第一格写 arm（offsets 第一格是机械臂）");
        Set<BlockPos> seen = new HashSet<>();
        for (int i = 1; i < cells.size(); i++) {
            if (!roles.get(i).equals(TAKE) && !roles.get(i).equals(DEPOSIT)) {
                return Optional.of("取放点的 roles 只能写 take 或 deposit：" + roles.get(i));
            }
            if (cells.get(i).equals(cells.getFirst()) || !seen.add(cells.get(i))) {
                return Optional.of("取放点不能重复，也不能是机械臂那一格");
            }
            if (range > 0 && !cells.get(i).closerThan(cells.getFirst(), range)) {
                return Optional.of("取放点 " + cells.get(i).toShortString() + " 离机械臂超过 " + range + " 格（服务器配置）");
            }
        }
        return Optional.empty();
    }

    // 拿机械臂 → 挨个点取放点（取点一下、放点两下）→ 放下机械臂 → 等它记着的点同步回来。
    @Override public Action install(Installation installation, Permissions permissions) {
        BlockPos arm = installation.cells().getFirst();
        List<StepsInOrder.Step> steps = new ArrayList<>();
        steps.add(new StepsInOrder.Step("拿机械臂", () -> holdingArm() ? null
                : services.toMainhand().moveToMainhand(ARM_ITEM).orElseGet(() -> failing(Problem.Kind.NEED_ITEM, "身上没有机械臂"))));
        for (int i = 1; i < installation.cells().size(); i++) {
            BlockPos point = installation.cells().get(i);
            int clicks = installation.roles().get(i).equals(TAKE) ? 1 : 2;
            for (int click = 0; click < clicks; click++) {
                steps.add(new StepsInOrder.Step("点取放点 " + point.toShortString(), () -> selectPoint(point, permissions)));
            }
        }
        steps.add(new StepsInOrder.Step("放下机械臂", () -> placeArm(arm, permissions)));
        steps.add(new StepsInOrder.Step("等取放点同步回来", () -> new WaitsForPoints(arm, installation)));
        return new StepsInOrder("放机械臂 " + arm.toShortString(), steps);
    }

    // 机械臂那一格不写进 cells、由这一段放下：拆整台时跟着拆；取放点是别的方块（置物台、别的机器），不拆。
    @Override public List<BlockPos> placedCells(Installation installation) {
        return List.of(installation.cells().getFirst());
    }

    @Override public boolean installed(ClientLevel level, Installation installation) {
        return matches(compat.armPoints(level, installation.cells().getFirst()), installation);
    }

    /** 放好的机械臂记着的点和要的一样，纯函数：位置与取放一一对上，不多不少。 */
    static boolean matches(List<CreateStations.ArmPoint> points, Installation installation) {
        if (points.size() != installation.cells().size() - 1) return false;
        for (int i = 1; i < installation.cells().size(); i++) {
            BlockPos cell = installation.cells().get(i);
            String role = installation.roles().get(i);
            if (points.stream().noneMatch(point -> point.pos().equals(cell) && point.mode().equals(role))) return false;
        }
        return true;
    }

    // 点一下取放点：Create 只在客户端记下，游戏没有确认可等，游戏接下这一下右键就算点上；手里不换那一叠。
    private Action selectPoint(BlockPos point, Permissions permissions) {
        return new HandThenClick(services, "用机械臂点取放点 " + point.toShortString(), point, new AABB(point), permissions,
                () -> holdingArm() ? Optional.empty() : Optional.of(failing(Problem.Kind.STUCK, "手里的机械臂不见了，选的点作废")),
                () -> context -> InteractionConfirmation.Verdict.APPLIED);
    }

    // 放下机械臂：下面一格有东西就点它的顶面（立在地上），不然点上面一格的底面（倒挂）；那一格变成机械臂才算放下。
    private Action placeArm(BlockPos arm, Permissions permissions) {
        PlayerContext current = services.context().get();
        boolean floor = current == null || current.level() == null || !current.level().getBlockState(arm.below()).isAir();
        BlockPos support = floor ? arm.below() : arm.above();
        AABB face = faceOf(support, floor ? Direction.UP : Direction.DOWN);
        return new HandThenClick(services, "放下机械臂 " + arm.toShortString(), support, face, permissions,
                () -> holdingArm() ? Optional.empty() : Optional.of(failing(Problem.Kind.STUCK, "手里的机械臂不见了，选的点作废")),
                () -> context -> blockId(context.level().getBlockState(arm).getBlock()).equals(ARM_ITEM)
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
    }

    private static AABB faceOf(BlockPos cell, Direction face) {
        AABB slab = face == Direction.UP ? new AABB(0, 1 - FACE_DEPTH, 0, 1, 1, 1) : new AABB(0, 0, 0, 1, FACE_DEPTH, 1);
        return slab.deflate(FACE_INSET, 0, FACE_INSET).move(cell.getX(), cell.getY(), cell.getZ());
    }

    private boolean holdingArm() {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null && !current.localPlayer().getMainHandItem().isEmpty()
                && itemId(current.localPlayer().getMainHandItem().getItem()).equals(ARM_ITEM);
    }

    private static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Action failing(Problem.Kind kind, String reason) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(kind, reason));
            }

            @Override public String describe() {
                return reason;
            }
        };
    }

    /** 放下后等服务器把取放点同步回来：对上了就完，等满了也完，装没装成由核对说。 */
    private final class WaitsForPoints implements Action {
        private final BlockPos arm;
        private final Installation installation;
        private int waited;

        WaitsForPoints(BlockPos arm, Installation installation) {
            this.arm = arm;
            this.installation = installation;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (matches(compat.armPoints(context.player().level(), arm), installation) || ++waited >= SYNC_WAIT_TICKS) {
                return ActionStatus.done();
            }
            return ActionStatus.running();
        }

        @Override public String describe() {
            return "等机械臂的取放点同步回来";
        }
    }
}
