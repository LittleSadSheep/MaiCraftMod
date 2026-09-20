// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.maiwithu.maicraft.server.inventory.NativeItemPort;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 物品沿漏斗移动时，先核原生抽取／推出方向，再检查实际端口；库存与时序流量另行验收。 */
final class HopperConnectionInspection {
    private static final String PROVENANCE = "HopperBlockEntity.pull_above+HopperBlock.FACING+ENABLED+sided_item_port";
    record Route(HopperBlockEntity hopper, BlockPos endpoint, Direction side, boolean pull) {
        boolean enabled() { return hopper.getBlockState().getValue(HopperBlock.ENABLED); }
    }
    private HopperConnectionInspection() {}

    static boolean isHopper(BlockEntity entity) {
        // 原版方块及其原生实体同时匹配才使用漏斗规则，不把相似名字的模组设备当成漏斗。
        return entity instanceof HopperBlockEntity && entity.getBlockState().is(Blocks.HOPPER);
    }

    static List<Route> routes(BlockEntity from, BlockEntity to) {
        List<Route> routes = new ArrayList<>();
        if (from == null || to == null) return routes;
        if (isHopper(from)) {
            var hopper = (HopperBlockEntity) from;
            Direction facing = hopper.getBlockState().getValue(HopperBlock.FACING);
            if (from.getBlockPos().relative(facing).equals(to.getBlockPos()))
                routes.add(new Route(hopper, to.getBlockPos(), facing.getOpposite(), false));
        }
        if (isHopper(to) && to.getBlockPos().above().equals(from.getBlockPos()))
            routes.add(new Route((HopperBlockEntity) to, from.getBlockPos(), Direction.DOWN, true));
        return List.copyOf(routes);
    }

    static ConnectionEvidence edge(ServerPlayer player, BlockEntity from, BlockEntity to, JsonObject body) {
        List<Route> routes = routes(from, to);
        if (routes.isEmpty()) return evidence("planned", false, false, "hopper_has_no_directed_pull_or_push_edge");
        ConnectionEvidence best = null;
        // 上下两个漏斗可能由上方推出或下方抽取；逐条验证，不能因第一条被红石锁定就否定另一条。
        for (Route route : routes) {
            ConnectionEvidence next = inspectRoute(player, route, body);
            if (best == null || score(next) > score(best)) best = next;
        }
        return best;
    }

    private static ConnectionEvidence inspectRoute(ServerPlayer player, Route route, JsonObject body) {
        BlockEntity endpoint = player.serverLevel().getBlockEntity(route.endpoint());
        if (route.hopper().getLootTable() != null
                || endpoint instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null)
            return evidence("unknown", false, false, "hopper_endpoint_loot_not_opened");
        NativeItemPort port = endpointPort(player, endpoint, route.side());
        if (port == null) return evidence("planned", false, false, "hopper_sided_inventory_port_missing");
        if (port.slots() <= 0 || port.slots() > 128)
            return evidence("unknown", false, false, "hopper_endpoint_slots_outside_inspection_budget");
        var result = evidence(route.enabled() ? "verified" : "planned", true, route.enabled(),
                route.enabled() ? "native_hopper_direction_and_sided_port_present" : "hopper_disabled_by_redstone");
        result.details().addProperty("transfer_mechanism", route.pull() ? "hopper_pull_above" : "hopper_push_facing");
        result.details().addProperty("endpoint_face", route.side().getSerializedName());
        result.details().addProperty("hopper_enabled", route.enabled());
        JsonObject probe = HopperItemRouteEvidence.probe(player.registryAccess(), route, port, body);
        result.details().add("resource_probe", probe);
        result.details().addProperty("specific_resource_acceptance_verified", "verified".equals(probe.get("status").getAsString()));
        return result;
    }

    private static NativeItemPort endpointPort(ServerPlayer player, BlockEntity endpoint, Direction side) {
        if (endpoint == null) return null;
        // NeoForge 漏斗优先使用这个面的实际 BLOCK capability；不拿另一面或实体矿车替代作者声明的方块端点。
        if (NativeApi.present("net.neoforged.neoforge.items.VanillaInventoryCodeHooks"))
            return NativeItemPort.find(player.serverLevel(), endpoint.getBlockPos(), side);
        // 没有能力桥时只检查明确的单容器。双箱还涉及未声明的另一半，保留未知而不扩大读取范围。
        if (endpoint instanceof Container container && !(endpoint instanceof ChestBlockEntity))
            return new NativeItemPort.ContainerPort(container, side);
        return null;
    }

    static ConnectionEvidence transit(BlockEntity entity) {
        if (!isHopper(entity)) return evidence("unknown", false, false, "intermediate_inventory_pass_through_unproven");
        // 两条边已经核对实际抽入与推出方向；原版漏斗在同一个五格库存中转，不存在暗中的跨槽设备加工。
        return evidence("verified", true, true, "native_hopper_single_inventory_transit");
    }

    private static int score(ConnectionEvidence evidence) {
        int score = evidence.connected() ? 4 : 0;
        if (evidence.operational()) score += 2;
        JsonObject probe = evidence.details().getAsJsonObject("resource_probe");
        if (probe != null && "verified".equals(probe.get("status").getAsString())) score++;
        return score;
    }

    private static ConnectionEvidence evidence(String status, boolean connected, boolean operational, String reason) {
        ConnectionEvidence evidence = ConnectionEvidence.of(status, connected, operational, reason, PROVENANCE);
        evidence.details().addProperty("adapter", "vanilla_hopper_items");
        evidence.details().addProperty("specific_resource_acceptance_verified", false);
        return evidence;
    }
}
