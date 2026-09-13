// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;

public final class CreateMechanicalTerminalSupportTest {
    public static void main(String[] args) throws Exception {
        BlockPos source = new BlockPos(4, 2, 4), destination = new BlockPos(10, 2, 4);
        var from = endpoint(source, Direction.UP, true);
        var to = endpoint(destination, Direction.UP, false);
        List<BlockPos> positions = new ArrayList<>(); for (int x = 4; x <= 10; x++) positions.add(new BlockPos(x, 3, 4));
        var route = new CreateMechanicalPlanner.TentativeRoute(from, to, destination.above(), List.copyOf(positions), "row", "test");
        check(route.supportFor(0).equals(source), "source end must use its actual port block");
        check(route.supportFor(3).equals(positions.get(2)), "intermediate construction retains its contiguous predecessor");
        check(route.supportFor(6).equals(destination), "last cell must click the declared destination port rather than an occluded predecessor");
        check(CreateMechanicalPlan.between(route.supportFor(6), positions.getLast()) == Direction.UP, "terminal support face remains the exact declared UP");
        var free = new CreateMechanicalPlanner.TentativeRoute(from, null, positions.getLast(), positions, "row", "test");
        check(free.supportFor(6).equals(positions.get(5)), "free receiver has no machine support to invent");
        var single = new CreateMechanicalPlanner.TentativeRoute(from, endpoint(source.above(2), Direction.DOWN, false), source.above(), List.of(source.above()), "one", "test");
        check(single.supportFor(0).equals(source), "a single-cell bridge keeps its source support");
        try (var h = new InteractionWorldTestHarness()) {
            var border = Level.class.getDeclaredField("worldBorder"); border.setAccessible(true); border.set(h.level, new WorldBorder());
            h.set(destination.below(), Blocks.STONE.defaultBlockState()); h.set(destination, Blocks.END_ROD.defaultBlockState());
            h.set(destination.east(), Blocks.STONE.defaultBlockState()); // Existing machine beside the receiving shaft.
            BlockPos elevated = CreateMechanicalPlanner.findPlacementStand(h.level, positions.getLast(), Set.copyOf(positions), true);
            check(elevated != null && elevated.getY() > h.player.blockPosition().getY(),
                    "distance-only sorting reproduces the unwanted machine-roof stance: " + elevated);
            BlockPos stand = CreateMechanicalPlanner.findPlacementStand(h.level, positions.getLast(), Set.copyOf(positions), true, h.player.blockPosition());
            check(stand != null && stand.getY() == h.player.blockPosition().getY()
                            && stand.getX() == destination.getX() && stand.getZ() != destination.getZ(),
                    "working-floor stance must avoid the machine roof, built prefix and old diagonal: " + stand);
            h.set(positions.get(5), Blocks.STONE.defaultBlockState()); // Sixth native chain cube now exists.
            Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, 2.87, 0), top = Vec3.atCenterOf(destination).add(0, .5, 0);
            var hit = h.level.clip(new ClipContext(eye, eye.add(top.subtract(eye).normalize().scale(4.5)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, h.player));
            check(hit.getBlockPos().equals(destination) && hit.getDirection() == Direction.UP,
                    "jumping from the terminal stance must visibly hit its shaft top despite the preceding full chain cube");
            prefixReport(h, route, stand);
        }
        System.out.println("CreateMechanicalTerminalSupportTest: exact terminal support, visible jump stance and current prefix quantities passed");
    }
    @SuppressWarnings("unchecked")
    private static void prefixReport(InteractionWorldTestHarness h, CreateMechanicalPlanner.TentativeRoute route, BlockPos stand) throws Exception {
        List<CreateMechanicalPlan.RouteCell> cells = new ArrayList<>();
        for (int index = 0; index < route.positions().size(); index++) {
            BlockPos support = route.supportFor(index), position = route.positions().get(index);
            cells.add(new CreateMechanicalPlan.RouteCell(position, support, CreateMechanicalPlan.between(support, position), stand));
        }
        var request = CreateMechanicalPower.Request.preserving(new CreateMechanicalPower.Endpoint("city", route.source().position()),
                new CreateMechanicalPower.Endpoint("input", route.destination().position()));
        var record = new CreateMechanicalPowerTaskRecord("prefix-report", 1000, request, null, MaterialPolicy.INVENTORY_ONLY, List.of(), false, List.of());
        var task = new CreateMechanicalPowerTask(h.player, record);
        field("plan").set(task, new CreateMechanicalPlan(route.source(), route.destination(), route.receiver(), cells, "row", "test", true, 64, false));
        field("cursor").setInt(task, 6); field("initialInventoryCount").setInt(task, 30);
        var data = (Map<String, Object>) field("data").get(task);
        data.put("remaining_route_chain_drives", 7); data.put("available_chain_drives", 30); data.put("required_chain_drives_this_attempt", 7);
        data.put("continuation_confirmed_prefix", 6);
        var report = task.resultData();
        check(report.get("remaining_route_chain_drives").equals(1) && report.get("confirmed_placements").equals(6), "remaining counts must follow the confirmed cursor");
        check(report.get("batch_initial_chain_drives").equals(30) && report.get("expected_carried_chain_drives").equals(24), "initial budget and accounted remaining inventory remain distinct");
        check(!report.containsKey("available_chain_drives"), "start inventory cannot masquerade as a current observation");
        field("nativeOutcomeUncertain").setBoolean(task, true);
        check(!task.resultData().containsKey("expected_carried_chain_drives"), "an unsettled native effect prevents exact inventory accounting");
    }
    private static CreateMechanicalPlan.KineticEndpoint endpoint(BlockPos at, Direction face, boolean powered) {
        return new CreateMechanicalPlan.KineticEndpoint(at, Blocks.END_ROD.defaultBlockState(), face, powered ? 64 : 0, powered);
    }
    private static java.lang.reflect.Field field(String name) throws Exception {
        var field = CreateMechanicalPowerTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
