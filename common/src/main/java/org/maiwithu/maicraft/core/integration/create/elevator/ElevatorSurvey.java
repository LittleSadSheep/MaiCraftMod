package org.maiwithu.maicraft.core.integration.create.elevator;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorBridge.Cabin;
import org.maiwithu.maicraft.core.integration.create.elevator.ElevatorGeometry.Landing;

/** Associations come from synchronized cabin floor lists and real local redstone connections. */
final class ElevatorSurvey {
    record CallInput(BlockPos position, Vec3 stance, int inventorySlot, int channel) { boolean remote() { return channel >= 0; } }
    record Plan(UUID cabin, int fromFloor, int toFloor, BlockPos control, Vec3 controlStance,
                Landing board, Landing exit, CallInput call, boolean aboard, double score) {}

    static Plan find(LocalPlayerContext ctx, BlockPos destination, LongSet forbidden, CreateElevatorBridge bridge, Cabin cabin) {
        if (cabin.floors().isEmpty()) return null;
        double width = ctx.player().getBbWidth(), height = ctx.player().getBbHeight(), step = ctx.player().maxUpStep();
        ElevatorGeometry geometry = new ElevatorGeometry(cabin.blocks(), cabin.view(), width, height);
        Vec3 player = ctx.player().position();
        boolean aboard = geometry.carries(cabin.local(player));
        double deck = deckHeight(geometry.stances);
        if (!Double.isFinite(deck)) return null;
        List<Plan> plans = new ArrayList<>();
        for (var target : cabin.floors()) {
            if (!cabin.serves(target.contactY()) || Math.abs(target.contactY() - cabin.offset() + deck - destination.getY()) > 1.25) continue;
            Vec3 targetOrigin = cabin.originAt(target.contactY());
            Landing exit = geometry.landings(ctx.level(), ctx.level()::hasChunkAt, targetOrigin, step, forbidden).stream()
                    .filter(l -> Math.abs(l.inside().y - deck) <= step)
                    .min(Comparator.comparingDouble(l -> l.outside().distanceToSqr(Vec3.atBottomCenterOf(destination)))).orElse(null);
            if (exit == null) continue;
            for (var source : cabin.floors()) {
                if (!aboard && Math.abs(source.contactY() - cabin.offset() + deck - player.y) > 1.25) continue;
                if (!cabin.serves(source.contactY())) continue;
                Vec3 sourceOrigin = cabin.originAt(source.contactY());
                Landing board = aboard ? null : geometry.landings(ctx.level(), ctx.level()::hasChunkAt, sourceOrigin, step, forbidden).stream()
                        .filter(l -> Math.abs(l.inside().y - deck) <= step)
                        .min(Comparator.comparingDouble(l -> l.outside().distanceToSqr(player))).orElse(null);
                if (!aboard && board == null) continue;
                Vec3 localStart = aboard ? cabin.local(player) : board.inside();
                if ((aboard && cabin.aligned(target.contactY()) || !aboard && source.contactY() == target.contactY())
                        && !geometry.path(localStart, exit.inside(), targetOrigin, step, forbidden).isEmpty()) {
                    CallInput call = aboard || cabin.targetY() == source.contactY() ? null : callInput(ctx, bridge, cabin, source.contactY());
                    if (aboard || cabin.targetY() == source.contactY() || call != null) plans.add(new Plan(cabin.entity().getUUID(),
                            source.contactY(), target.contactY(), null, exit.inside(), board, exit, call, aboard,
                            (board == null ? 0 : board.outside().distanceToSqr(player)) + exit.outside().distanceToSqr(Vec3.atBottomCenterOf(destination))));
                    continue;
                }
                for (BlockPos controller : cabin.controls()) {
                    Vec3 dial = bridge.controlAim(cabin, controller).subtract(cabin.origin());
                    List<Vec3> path = geometry.pathTo(localStart,
                            p -> Math.abs(p.y - deck) <= step && p.add(0, ctx.player().getEyeHeight(), 0).distanceTo(dial) <= ctx.player().blockInteractionRange()
                                    && visible(cabin, controller, p.add(0, ctx.player().getEyeHeight(), 0), dial), sourceOrigin, step, forbidden);
                    Vec3 controlStance = path.isEmpty() ? null : path.getLast();
                    if (controlStance == null || geometry.path(controlStance, exit.inside(), targetOrigin, step, forbidden).isEmpty()) continue;
                    CallInput call = aboard || cabin.aligned(source.contactY()) ? null : callInput(ctx, bridge, cabin, source.contactY());
                    if (!aboard && !cabin.aligned(source.contactY()) && cabin.targetY() != source.contactY() && call == null) continue;
                    if (!rideAllowed(controlStance, sourceOrigin, targetOrigin, width, height, forbidden)) continue;
                    double score = (board == null ? 0 : board.outside().distanceToSqr(player))
                            + exit.outside().distanceToSqr(Vec3.atBottomCenterOf(destination));
                    plans.add(new Plan(cabin.entity().getUUID(), source.contactY(), target.contactY(), controller.immutable(),
                            controlStance, board, exit, call, aboard, score));
                }
            }
        }
        return plans.stream().min(Comparator.comparingDouble(Plan::score)).orElse(null);
    }

    static double deckHeight(List<Vec3> stances) {
        Map<Double, Integer> counts = new HashMap<>();
        for (Vec3 stance : stances) counts.merge(stance.y, 1, Integer::sum);
        return counts.entrySet().stream().min(Comparator.<Map.Entry<Double, Integer>>comparingInt(e -> -e.getValue())
                .thenComparingDouble(Map.Entry::getKey)).map(Map.Entry::getKey).orElse(Double.NaN);
    }

    static boolean rideAllowed(Vec3 local, Vec3 fromOrigin, Vec3 toOrigin, double width, double height, LongSet forbidden) {
        int steps = Math.max(1, (int) Math.ceil(Math.abs(toOrigin.y - fromOrigin.y) * 2));
        for (int i = 0; i <= steps; i++) if (ElevatorGeometry.forbidden(local.add(fromOrigin.lerp(toOrigin, (double) i / steps)), width, height, forbidden)) return false;
        return true;
    }

    private static boolean visible(Cabin cabin, BlockPos controller, Vec3 eye, Vec3 dial) {
        var hit = cabin.view().clip(new net.minecraft.world.level.ClipContext(eye, dial,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(controller);
    }

    private static CallInput callInput(LocalPlayerContext ctx, CreateElevatorBridge bridge, Cabin cabin, int floor) {
        BlockPos contact = cabin.column().at(floor);
        if (!bridge.isContact(ctx, contact, cabin.column())) return null;
        List<BlockPos> buttons = new ArrayList<>(), receivers = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(contact.offset(-2, -2, -2), contact.offset(2, 2, 2))) {
            if (!ctx.level().hasChunkAt(pos)) continue;
            BlockState state = ctx.level().getBlockState(pos);
            boolean button = state.getBlock() instanceof ButtonBlock;
            boolean receiver = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:redstone_link")
                    && state.getValues().entrySet().stream().anyMatch(e -> e.getKey().getName().equals("receiver") && Boolean.TRUE.equals(e.getValue()));
            if (!(button || receiver) || !feeds(ctx.level(), contact, pos, state, button)) continue;
            boolean ambiguous = cabin.floors().stream().anyMatch(f -> f.contactY() != floor
                    && feeds(ctx.level(), cabin.column().at(f.contactY()), pos, state, button));
            if (ambiguous) continue;
            (button ? buttons : receivers).add(pos.immutable());
        }
        buttons.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(ctx.player().position())));
        for (BlockPos button : buttons) {
            Vec3 stance = callStance(ctx, button);
            if (stance != null) return new CallInput(button, stance, -1, -1);
        }
        for (BlockPos receiver : receivers) {
            for (int slot = 0; slot < 36; slot++) {
                int channel = bridge.remoteChannel(ctx, receiver, ctx.player().getInventory().getItem(slot));
                if (channel >= 0) return new CallInput(receiver, ctx.player().position(), slot, channel);
            }
        }
        return null;
    }

    private static Vec3 callStance(LocalPlayerContext ctx, BlockPos button) {
        var shape = ctx.level().getBlockState(button).getShape(ctx.level(), button);
        Vec3 aim = shape.isEmpty() ? Vec3.atCenterOf(button) : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(button));
        Vec3 best = null; double distance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Vec3 stance = ElevatorGeometry.staticStance(ctx.level(), ctx.level()::hasChunkAt,
                    new Vec3(button.getX() + dx + 0.5, ctx.player().getY(), button.getZ() + dz + 0.5),
                    ctx.player().getBbWidth(), ctx.player().getBbHeight(), ctx.player().maxUpStep());
            if (stance == null) continue;
            Vec3 eye = stance.add(0, ctx.player().getEyeHeight(), 0);
            if (eye.distanceTo(aim) > ctx.player().blockInteractionRange()) continue;
            var hit = ctx.level().clip(new net.minecraft.world.level.ClipContext(eye, aim,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(button)) continue;
            double next = stance.distanceToSqr(ctx.player().position());
            if (next < distance) { best = stance; distance = next; }
        }
        return best;
    }

    /** A native button/receiver either weakly powers the contact or strongly powers its adjacent support. */
    static boolean feeds(BlockGetter world, BlockPos contact, BlockPos source, BlockState state, boolean button) {
        if (source.distManhattan(contact) == 1) return true;
        Direction facing;
        if (button) {
            facing = switch (state.getValue(BlockStateProperties.ATTACH_FACE)) {
                case FLOOR -> Direction.UP;
                case CEILING -> Direction.DOWN;
                case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            };
        } else {
            if (!state.hasProperty(BlockStateProperties.FACING)) return false;
            facing = state.getValue(BlockStateProperties.FACING);
        }
        BlockPos support = source.relative(facing.getOpposite());
        return support.distManhattan(contact) == 1 && world.getBlockState(support).isRedstoneConductor(world, support);
    }
}
