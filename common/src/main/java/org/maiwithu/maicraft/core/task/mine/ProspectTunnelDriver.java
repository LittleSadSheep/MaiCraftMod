// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineBreak;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSession;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;

/** 按当前通行缺口选方块与命中面；原生线段挖断后重新补齐，实际走通后才推进下一段。 */
final class ProspectTunnelDriver implements AutoCloseable {
    record Effect(BlockPos origin, UltimineBreak.Result result) {}
    private static final org.slf4j.Logger LOG = Constants.LOG;
    private final LocalPlayer player;
    private final int targetY;
    /** 掘进朝向不是一次性决定：当前朝向扫不到可挖前沿时原地转 90° 重扫，真无路才收手。 */
    private Direction heading;
    private final BlockDigger probe;
    private final List<Effect> effects = new ArrayList<>();
    private ProspectTunnelPlan plan;
    private UltimineBreak action;
    private PlayerNav nav;
    private BlockPos destination;
    private String failure;
    private boolean uncertain;
    /** 连续扫不到可挖前沿的次数；原地重扫超过预算仍无果才换向，单次空扫不构成永久结论。 */
    private int fruitlessScans;
    private BlockPos lastScanAnchor;
    private static final int MAX_FRUITLESS_SCANS = 2;
    /** 同一站位允许的换向次数：四个水平朝向都扫过仍无可挖断面，才算真无路。 */
    private int pivots;
    private static final int MAX_PIVOTS = 3;
    /** 无掩体/无断面时先向下掘入的深度；下掘后的工作层仍处生成带内，转水平隧道继续探。 */
    private int workingY;
    private boolean burrowed;
    private static final int BURROW_DEPTH = 3;
    /** 历段导航的原生地形账汇总：探矿中途收手时，垫块消耗凭它对账，不再无声消失。 */
    private final TerrainBill terrainBill = new TerrainBill();
    private Map<String, Object> interrupted = Map.of();
    private int removed;

    private void absorbBill(PlayerNav finished) {
        if (finished != null) terrainBill.addAll(finished.ledger());
    }

    ProspectTunnelDriver(LocalPlayer player, int targetY) {
        this.player = player; this.targetY = targetY; workingY = targetY;
        heading = player.getDirection(); probe = new BlockDigger(player);
    }
    boolean tick(int blockBudget) {
        if (failure != null) return false;
        if (action != null) {
            var result = action.tick();
            if (result.status() == UltimineBreak.Status.RUNNING) return true;
            effects.add(new Effect(action.origin(), result)); removed += result.removed().size();
            action.close(); action = null;
            if (result.status() != UltimineBreak.Status.COMPLETE || result.uncertain()) {
                failure = "native_tunnel_break: " + result.evidence().get("reason"); uncertain |= result.uncertain(); return false;
            }
            return true;
        }
        if (nav != null) {
            return switch (nav.tick()) {
                case RUNNING -> true;
                case ARRIVED -> { absorbBill(nav); nav.stop(); nav = null; plan = null; yield true; }
                case FAILED -> { absorbBill(nav); failure = "tunnel_walk: " + nav.failReason(); nav.stop(); nav = null; yield false; }
            };
        }
        BlockPos feet = PlayerNav.playerFeet(player);
        if (!player.onGround()) return true;
        if (feet.getY() < workingY) {
            // 采矿绕到较低洞室后先复用普通导航回到工作层，不能把水平通道继续挖在错误高度。
            destination = new BlockPos(feet.getX(), workingY, feet.getZ());
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), 1, () -> false, PlayerNav.ContextProvider.TERRAFORM);
            return true;
        }
        // 断面几何以锚定脚位为基准：走路到达、外放回收或被推移后脚位与锚点脱节时，
        // 沿用旧计划会把射线对准角色已经不在的位置，实心地形里也会判出“无处可挖”。
        if (plan != null && !plan.anchor().equals(feet)) plan = null;
        if (plan == null) {
            plan = new ProspectTunnelPlan(feet, heading, workingY, player.getBoundingBox().getYsize(), Math.max(1, blockBudget));
            // 脚位变了才清空重扫与换向计数；同锚点的原地重建不清零，否则空扫与重建互相喂活成死循环。
            if (!feet.equals(lastScanAnchor)) { fruitlessScans = 0; pivots = 0; }
        }
        BlockPos walk = plan.walkableEnd(this::bodyClear, this::safeFloor);
        if (walk != null) {
            // 只有完整断面和实底连续成立才移动；走通道用保留地形导航，不能顺路把未补齐的地方当作已完成。
            destination = walk;
            nav = PlayerNav.toGoal(player, () -> NavGoal.exact(destination), 1, () -> false, PlayerNav.ContextProvider.DEFAULT).walkingOnly();
            return true;
        }
        int reach = (int) Math.ceil(AimGeometry.blockReachDistance(player));
        for (BlockPos at : plan.obstacles(this::read, reach)) {
            if (!allowed(at)) continue;
            BlockHitResult hit = hit(at, heading.getOpposite());
            if (hit == null && plan.descending()) hit = hit(at, Direction.UP);
            // 站位与前沿斜向错开（到达容差、外放回收、腔内自掘形态）时，可见面未必朝向隧道轴；
            // 射线确实落在该格上的任何一个面都等价于真人转头点一下，不构成放弃这条断面的理由。
            if (hit == null) hit = hit(at, null);
            if (hit == null) continue;
            var mode = plan.descending() ? UltimineSession.Mode.DESCENDING_TUNNEL : UltimineSession.Mode.SMALL_TUNNEL;
            action = new UltimineBreak(player, at, hit.getDirection(), mode, this::allowed,
                    cell -> cell.equals(PlayerNav.playerFeet(player).below())).maximumBlocks(blockBudget);
            fruitlessScans = 0;
            return true;
        }
        // “此刻扫不到可挖目标”与站位和世界瞬态相关，不是地形结论：原地以新锚点有限重扫，
        // 期间世界同步（流体、掉落、区块更新）可能改变可达性；重扫预算用尽后先换向再下掘，
        // 全部用尽仍无果才如实失败。
        lastScanAnchor = feet;
        if (fruitlessScans < MAX_FRUITLESS_SCANS) {
            fruitlessScans++;
            plan = null;
            return true;
        }
        // 换向重锚：腔内失锚、站位与前沿斜向错开都只否决当前朝向；原地转 90° 重新扫前沿，
        // 四个水平朝向都扫过仍无可挖断面，“当前朝向无路”才升级为“真无路”。
        if (pivots < MAX_PIVOTS) {
            Direction rejected = heading;
            pivots++;
            heading = heading.getClockWise();
            fruitlessScans = 0;
            plan = null;
            LOG.info("[maicraft-prospect] no diggable frontier toward {} at {} after {} fruitless scans;"
                            + " pivoting heading to {} (pivot {}/{})",
                    rejected.getName(), feet.toShortString(), MAX_FRUITLESS_SCANS,
                    heading.getName(), pivots, MAX_PIVOTS);
            return true;
        }
        // 无掩体形态（露天带内表面、崖边空腔）：四个朝向都没有可挖断面，先垂直下掘几格
        // 取得掩体与实底，再在低一层转水平隧道——这是起步子步骤，不是对“无路”的虚报。
        int burrowFloor = feet.getY() - BURROW_DEPTH;
        if (!burrowed && burrowFloor >= player.level().getMinBuildHeight()) {
            burrowed = true;
            workingY = burrowFloor;
            fruitlessScans = 0;
            pivots = 0;
            plan = null;
            LOG.info("[maicraft-prospect] no cover or diggable frontier at Y {} in all {} headings;"
                            + " burrowing down to Y {} before resuming the horizontal tunnel",
                    feet.getY(), MAX_PIVOTS + 1, workingY);
            return true;
        }
        failure = "no_reachable_obstacle_or_supported_passage_in_tunnel_direction";
        LOG.info("[maicraft-prospect] tunnel stops at {}: all {} headings fruitless{}, no honest dig left",
                feet.toShortString(), MAX_PIVOTS + 1, burrowed ? " and burrow attempted" : "");
        return false;
    }
    private BlockHitResult hit(BlockPos at, Direction face) { probe.requiredFace(face); return probe.reachableHit(at); }
    private BlockState read(BlockPos at) { return player.level().isLoaded(at) ? player.level().getBlockState(at) : null; }
    private boolean allowed(BlockPos at) {
        BlockState state = read(at);
        return plan != null && plan.contains(at) && state != null && ClearanceWhitelist.allows(state)
                && !NavigationSafetyContext.protectsMutation(at) && !state.hasBlockEntity()
                && state.getFluidState().isEmpty() && state.getDestroySpeed(player.level(), at) >= 0;
    }
    private boolean bodyClear(BlockPos at) {
        BlockState state = read(at);
        return state != null && !NavigationSafetyContext.forbidsBody(at) && state.getFluidState().isEmpty()
                && !MovementHelper.avoidWalkingInto(state) && state.getCollisionShape(player.level(), at).isEmpty();
    }
    private boolean safeFloor(BlockPos at) {
        BlockState state = read(at);
        return state != null && state.getFluidState().isEmpty() && !MovementHelper.avoidWalkingInto(state)
                && MovementHelper.canWalkOn(player.level(), at);
    }
    boolean breaking() { return action != null; }
    boolean yieldForMining() {
        // 先交接走路中尚未结清的原生动作，再允许矿工接管镜头和镐；回到通道时按新的脚位重建前沿。
        if (action != null || nav != null && !nav.yieldForExternalAction()) return false;
        if (nav != null) { absorbBill(nav); nav.stop(); nav = null; }
        plan = null; return true;
    }
    List<Effect> drainEffects() { var result = List.copyOf(effects); effects.clear(); return result; }
    String failure() { return failure; }
    boolean uncertain() { return uncertain; }
    Map<String, Object> evidence() {
        Map<String, Object> facts = new java.util.LinkedHashMap<>();
        facts.put("heading", heading.getName());
        facts.put("target_y", targetY);
        facts.put("working_y", workingY);
        facts.put("confirmed_excavation_blocks", removed);
        facts.put("phase", action != null ? "clearing_passage" : nav != null ? "walking_open_passage" : "checking_passage");
        facts.put("failure", failure == null ? "none" : failure);
        facts.put("fruitless_frontier_scans", fruitlessScans);
        facts.put("heading_pivots", pivots);
        facts.put("burrow_start", burrowed);
        facts.put("interrupted_action", interrupted);
        // 中途收手的消耗对账：原生确认的垫块（与开挖）按方块种类与位置全量交付，供调用方清理与补给。
        if (!terrainBill.isEmpty()) facts.put("terrain_bill", terrainBill.snapshot());
        return java.util.Collections.unmodifiableMap(facts);
    }
    @Override public void close() {
        if (action != null) {
            var result = action.interruptedResult(); interrupted = result.evidence(); uncertain |= result.uncertain();
            effects.add(new Effect(action.origin(), result)); removed += result.removed().size();
            action.close(); action = null;
            if (uncertain) failure = "native_tunnel_interrupted_with_unresolved_effects";
        }
        if (nav != null) { absorbBill(nav); nav.stop(); nav = null; }
        plan = null;
    }
}
