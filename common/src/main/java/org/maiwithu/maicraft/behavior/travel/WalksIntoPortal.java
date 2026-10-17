// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.navigation.GroundSteering;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 直走进门：面向门格中心一路走过去，身体进了门格就停住。
 *
 * <p>这一步不走寻路：寻路把门内格当不可走方块，走进去要靠第一人称的直走——
 * 看向门格中心、持续按住前进，等身体真的踩进门格。下界门要站着等几秒才传，
 * 末地门碰到就传，等待归任务判断；20 秒进不去的期限也归任务管，动作只报告
 * 进没进、门还在不在。正在过门（身体已沾着门格）时停下不安全，不被生存需求切碎。
 */
public final class WalksIntoPortal implements Action {

    private final DimensionWorldView world;
    private final Supplier<PlayerContext> context;
    private final Block portalBlock;
    private final BlockPos entrance;
    private final Set<Long> portalCells;
    /** 离门正前方那一格的中心这么近就算站好了。 */
    private static final double ALIGNED_WITHIN = 0.3;
    /** 已经站到门正前方那一格的中间了，接下来正对着门走进去。 */
    private boolean aligned;

    public WalksIntoPortal(DimensionWorldView world, Supplier<PlayerContext> context, Block portalBlock,
            BlockPos entrance, Set<Long> portalCells) {
        this.world = world;
        this.context = context;
        this.portalBlock = portalBlock;
        this.entrance = entrance.immutable();
        this.portalCells = Set.copyOf(portalCells);
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        PlayerContext current = context.get();
        if (current == null || current.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR,
                    "角色的输入入口不在，直走不了"));
        }
        PlayerInput input = current.input();
        LocalPlayer player = current.localPlayer();
        if (world.touchesPortal(portalBlock, portalCells)) {
            // 身体进了门格：停住站在门里，维度交接由任务等。
            input.halt(player);
            return ActionStatus.done();
        }
        if (!world.portalAlive(portalBlock, entrance)) {
            input.halt(player);
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE,
                    "走近时门还在，直走时门方块没了（被人拆了或塌了）"));
        }
        if (player == null) return ActionStatus.running();
        // 先走到门正前方那一格的中间，再正对着门走进去：斜着朝门格中心走，身子会蹭在门框的黑曜石上，
        // 实机 10-14 两个门口都"直走了 20 秒没能走进门格"。
        Vec3 front = aligned ? null : frontOf(player);
        if (front != null && Math.hypot(front.x - player.getX(), front.z - player.getZ()) > ALIGNED_WITHIN) {
            input.lookAt(player, new Vec3(front.x, player.getEyeY(), front.z));
            return walkToward(current, player, front, false);
        }
        aligned = true;
        input.lookAt(player, Vec3.atCenterOf(entrance));
        // 门框底下那一排黑曜石把门抬高了一格：站在地上要跳一下才迈得进去，像人一样跳进门。
        boolean jump = entrance.getY() > player.getY() + 0.5 && player.onGround();
        return walkToward(current, player, Vec3.atCenterOf(entrance), jump);
    }

    // 朝地上一点挪：朝着目标按键、离得近按得轻、前面是岩浆或火就停下不迈（要进的门本身不算）。
    // 实机 10-15 下界门口原先按"镜头朝哪就往前走"，转身那几秒一路往西走进岩浆湖烧死。
    private ActionStatus walkToward(PlayerContext current, LocalPlayer player, Vec3 target, boolean jump) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (GroundSteering.hazardAhead(player.level(), player.position(), dx, dz, state -> state.is(portalBlock))) {
            current.input().halt(player);
            return ActionStatus.running();
        }
        // 按键交给角色输入要用本刻的客户端刻号，不是世界时间：原先传世界时间，直走进门第一刻就内部错误
        // "input request is not for the active tick"，实机 10-14 三次都进不了下界。
        current.input().applySteering(yaw -> GroundSteering.keys(dx, dz, yaw, jump), player.getYRot(),
                current.clientTick());
        return ActionStatus.running();
    }

    // 门正前方那一格的中心（脚的高度）：竖着的下界门按门面朝向，取角色这一侧；平躺的门（末地门）没有正前方。
    private Vec3 frontOf(LocalPlayer player) {
        if (player == null) return null;
        BlockState state = player.level().getBlockState(entrance);
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) return null;
        Direction.Axis axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        BlockPos front = axis == Direction.Axis.X
                ? entrance.relative(player.getZ() < entrance.getZ() + 0.5 ? Direction.NORTH : Direction.SOUTH)
                : entrance.relative(player.getX() < entrance.getX() + 0.5 ? Direction.WEST : Direction.EAST);
        return new Vec3(front.getX() + 0.5, player.getY(), front.getZ() + 0.5);
    }

    @Override
    public void pause() {
        releaseControls();
    }

    @Override
    public void close() {
        releaseControls();
    }

    private void releaseControls() {
        PlayerContext current = context.get();
        if (current != null && current.input() != null) {
            current.input().halt(current.localPlayer());
        }
    }

    @Override
    public Interruptibility interruptibility() {
        // 正在过门停下不安全：半个人在门里被切碎，下次接着走会被重入冷却或半程传送卡住。
        if (world.touchesPortal(portalBlock, portalCells)) {
            return Interruptibility.UNSAFE_TO_STOP;
        }
        return Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return "直走进门格 (" + entrance.getX() + ", " + entrance.getY() + ", " + entrance.getZ() + ")";
    }

    /** 身体有没有沾着门格：脚位或头位这一刻还是活的门方块。生产读端与动作共用。 */
    static boolean touchesPortal(ClientLevel level, BlockPos feet, Set<Long> portalCells, Block portalBlock) {
        for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
            if (portalCells.contains(cell.asLong()) && PortalFinder.alive(level, cell, portalBlock)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 到达纪律：先离门口再结算。从门口出发挑一格干燥能站的落脚格——
     * 沾着门格的不算（站在门里结算会被重入冷却再传回去），按离人近到远挑。
     */
    static Optional<BlockPos> stepAsideCell(BlockGetter level, Set<Long> portalCells, BlockPos from) {
        Optional<BlockPos> chosen = Optional.empty();
        double best = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = from.offset(dx, dy, dz);
                    if (portalCells.contains(candidate.asLong())
                            || portalCells.contains(candidate.above().asLong())) {
                        continue;
                    }
                    if (!BlockPassability.isDryStandable(level, candidate)) {
                        continue;
                    }
                    double distance = candidate.distSqr(from);
                    if (distance < best) {
                        best = distance;
                        chosen = Optional.of(candidate);
                    }
                }
            }
        }
        return chosen;
    }
}
