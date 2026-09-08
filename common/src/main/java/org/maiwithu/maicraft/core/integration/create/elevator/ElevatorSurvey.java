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
    record CallInput(BlockPos position, Vec3 stance, int inventorySlot, int channel,
                     BlockPos transmitter, BlockPos receiver, List<Map<String, Object>> frequencyItems) {
        CallInput(BlockPos position, Vec3 stance, int inventorySlot, int channel) {
            this(position, stance, inventorySlot, channel, null, null, List.of());
        }
        boolean remote() { return channel >= 0; }
        Map<String, Object> evidence() {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("source", remote() ? "handheld_linked_controller" : transmitter == null ? "direct_button" : "world_redstone_link_button");
            result.put("position", coordinates(position));
            if (transmitter != null) result.put("transmitter", coordinates(transmitter));
            if (receiver != null) result.put("receiver", coordinates(receiver));
            if (!frequencyItems.isEmpty()) result.put("frequency_items", frequencyItems);
            result.put("scope", "loaded_client_state_no_server_ack");
            return Map.copyOf(result);
        }
    }
    record Plan(UUID cabin, int fromFloor, int toFloor, BlockPos control, Vec3 controlStance,
                Landing board, Landing exit, CallInput call, boolean aboard, double score) {}

    static Plan find(LocalPlayerContext ctx, BlockPos destination, LongSet forbidden, CreateElevatorBridge bridge, Cabin cabin, Map<String, Object> evidence) {
        return find(ctx,destination,forbidden,bridge,cabin,evidence,null);
    }
    static Plan find(LocalPlayerContext ctx, BlockPos destination, LongSet forbidden, CreateElevatorBridge bridge, Cabin cabin, Map<String, Object> evidence,Integer selectedFloor) {
        evidence.put("cabin_uuid", cabin.entity().getUUID().toString());
        Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
        evidence.put("rejected_candidates", rejected);
        if (cabin.floors().isEmpty()) { reject(rejected, "floor_list_missing"); return null; }
        double width = ctx.player().getBbWidth(), height = ctx.player().getBbHeight(), step = ctx.player().maxUpStep();
        ElevatorGeometry geometry = new ElevatorGeometry(cabin.blocks(), cabin.view(), width, height);
        Vec3 player = ctx.player().position();
        boolean aboard = geometry.carries(cabin.local(player));
        evidence.put("support_layers", supportLayers(geometry.stances));
        evidence.put("source_feet_y", player.y); evidence.put("destination_feet_y", destination==null ? "selected_native_floor" : destination.getY());
        evidence.put("floor_projections", cabin.floors().stream().map(f -> Map.of("contact_y", f.contactY(),
                "origin_y", cabin.originAt(f.contactY()).y, "source_decks", deckCandidates(geometry.stances, cabin.originAt(f.contactY()).y, player.y),
                "target_decks", targetDecks(geometry.stances,cabin.originAt(f.contactY()).y,destination))).toList());
        Map<Integer, ElevatorArrivalView> arrivals = new HashMap<>();
        Map<Integer, Object> doorEvidence = new java.util.LinkedHashMap<>();
        evidence.put("floor_doors", doorEvidence);
        if (geometry.stances.isEmpty()) { reject(rejected, "no_supported_body_clearance"); return null; }
        List<Plan> plans = new ArrayList<>();
        var radio = new ElevatorCallLinks(ctx, bridge);
        Map<Integer, java.util.Optional<CallInput>> callCache = new HashMap<>();
        java.util.function.IntFunction<CallInput> calls = floor -> callCache.computeIfAbsent(floor,
                y -> java.util.Optional.ofNullable(callInput(ctx, bridge, cabin, y, radio))).orElse(null);
        for (var target : cabin.floors()) {
            if (!cabin.serves(target.contactY()) || selectedFloor!=null && target.contactY()!=selectedFloor) continue;
            Vec3 targetOrigin = cabin.originAt(target.contactY());
            List<Double> decks = targetDecks(geometry.stances,targetOrigin.y,destination);
            if (decks.isEmpty()) { reject(rejected, "target_height_has_no_supported_layer"); continue; }
            for (double deck : decks) {
                Vec3 targetReference=destination==null ? new Vec3(player.x,targetOrigin.y+deck,player.z) : Vec3.atBottomCenterOf(destination);
                ElevatorArrivalView targetView = arrivals.computeIfAbsent(target.contactY(), y -> arrival(ctx, bridge, cabin, y, doorEvidence));
                Landing exit = geometry.landings(targetView, ctx.level()::hasChunkAt, targetOrigin, step, forbidden, deck).stream()
                        .min(Comparator.comparingDouble(l -> l.outside().distanceToSqr(targetReference))).orElse(null);
                if (exit == null) { reject(rejected, "target_landing_missing_or_obstructed"); continue; }
                var sources = cabin.floors().stream().filter(f -> cabin.serves(f.contactY())
                        && (aboard || Math.abs(cabin.originAt(f.contactY()).y + deck - player.y) <= 1.25)).toList();
                if (sources.isEmpty()) { reject(rejected, "source_height_has_no_supported_layer"); continue; }
                for (var source : sources) {
                    Vec3 sourceOrigin = cabin.originAt(source.contactY());
                    ElevatorArrivalView sourceView = arrivals.computeIfAbsent(source.contactY(), y -> arrival(ctx, bridge, cabin, y, doorEvidence));
                    Landing board = aboard ? null : geometry.landings(sourceView, ctx.level()::hasChunkAt, sourceOrigin, step, forbidden, deck).stream()
                            .min(Comparator.comparingDouble(l -> l.outside().distanceToSqr(player))).orElse(null);
                    if (!aboard && board == null) { reject(rejected, "source_landing_missing_or_obstructed"); continue; }
                    Vec3 localStart = aboard ? cabin.local(player) : board.inside();
                    if ((aboard && cabin.aligned(target.contactY()) || !aboard && source.contactY() == target.contactY())
                            && !geometry.path(localStart, exit.inside(), targetOrigin, step, forbidden).isEmpty()) {
                        CallInput call = aboard || cabin.targetY() == source.contactY() ? null : calls.apply(source.contactY());
                        if (aboard || cabin.targetY() == source.contactY() || call != null) plans.add(new Plan(cabin.entity().getUUID(),
                                source.contactY(), target.contactY(), null, exit.inside(), board, exit, call, aboard,
                                (board == null ? 0 : board.outside().distanceToSqr(player)) + exit.outside().distanceToSqr(targetReference)));
                        else reject(rejected, "no_proven_native_call_input");
                        continue;
                    }
                    if (cabin.controls().isEmpty()) reject(rejected, "no_native_controller");
                    for (BlockPos controller : cabin.controls()) {
                        Vec3 dial = bridge.controlAim(cabin, controller).subtract(cabin.origin());
                        List<Vec3> path = geometry.interiorPath(localStart, board == null ? exit.inside() : board.inside(), exit.inside(),
                                p -> p.add(0, ctx.player().getEyeHeight(), 0).distanceTo(dial) <= ctx.player().blockInteractionRange()
                                        && visible(cabin, controller, p.add(0, ctx.player().getEyeHeight(), 0), dial)
                                        && rideAllowed(p, sourceOrigin, targetOrigin, width, height, forbidden),
                                sourceOrigin, targetOrigin, deck, step, forbidden);
                        Vec3 controlStance = path.isEmpty() ? null : path.getLast();
                        if (controlStance == null) { reject(rejected, "no_reachable_visible_controller"); continue; }
                        CallInput call = aboard || cabin.aligned(source.contactY()) ? null : calls.apply(source.contactY());
                        if (!aboard && !cabin.aligned(source.contactY()) && cabin.targetY() != source.contactY() && call == null) { reject(rejected, "no_proven_native_call_input"); continue; }
                        double score = (board == null ? 0 : board.outside().distanceToSqr(player))
                                + exit.outside().distanceToSqr(targetReference);
                        plans.add(new Plan(cabin.entity().getUUID(), source.contactY(), target.contactY(), controller.immutable(),
                                controlStance, board, exit, call, aboard, score));
                    }
                }
            }
        }
        evidence.put("plans_found", plans.size());
        evidence.put("call_search", radio.evidence());
        Plan selected = plans.stream().min(Comparator.comparingDouble(Plan::score)).orElse(null);
        if (selected != null && selected.call() != null) evidence.put("call_input", selected.call().evidence());
        return selected;
    }

    static List<Map<String, Object>> supportLayers(List<Vec3> stances) {
        Map<Double, Integer> counts = new HashMap<>();
        for (Vec3 stance : stances) counts.merge(stance.y, 1, Integer::sum);
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> Map.<String, Object>of("local_feet_y", e.getKey(), "stance_count", e.getValue())).toList();
    }

    static List<Double> deckCandidates(List<Vec3> stances, double originY, double feetY) {
        return stances.stream().map(p -> p.y).distinct().filter(y -> Math.abs(originY + y - feetY) <= 1.25).sorted().toList();
    }

    private static void reject(Map<String, Integer> rejected, String reason) { rejected.merge(reason, 1, Integer::sum); }

    private static ElevatorArrivalView arrival(LocalPlayerContext ctx, CreateElevatorBridge bridge, Cabin cabin, int floor, Map<Integer, Object> evidence) {
        var doors = bridge.arrivalDoors(ctx.player(), cabin, floor);
        var view = ElevatorArrivalView.predict(ctx.level(), ctx.level()::hasChunkAt, doors.pairs());
        evidence.put(floor, Map.of("mode", doors.mode(), "predicted_open_cells", view.predictedDoors().keySet().stream()
                .map(p -> Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ())).toList()));
        return view;
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

    private static CallInput callInput(LocalPlayerContext ctx, CreateElevatorBridge bridge, Cabin cabin, int floor, ElevatorCallLinks radio) {
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
            var link = bridge.worldLink(ctx, receiver);
            if (link == null || !link.receiver()) continue;
            for (int slot = 0; slot < 36; slot++) {
                int channel = bridge.remoteChannel(ctx, receiver, ctx.player().getInventory().getItem(slot));
                if (channel >= 0) return new CallInput(receiver, ctx.player().position(), slot, channel,
                        null, receiver, link.frequencyItems());
            }
            for (var transmitter : radio.matching(link)) for (BlockPos button : radio.buttons(transmitter)) {
                Vec3 stance = callStance(ctx, button);
                if (stance != null) return new CallInput(button, stance, -1, -1,
                        transmitter.position(), receiver, link.frequencyItems());
            }
        }
        return null;
    }
    private static List<Double> targetDecks(List<Vec3> stances,double originY,BlockPos destination) {
        return destination==null ? stances.stream().map(p->p.y).distinct().sorted().toList()
                : deckCandidates(stances,originY,destination.getY());
    }

    /** Re-read the actual two-ended association immediately before the normal native button gesture. */
    static boolean callStillAssociated(LocalPlayerContext ctx, CreateElevatorBridge bridge, Cabin cabin, int floor, CallInput input) {
        BlockPos contact = cabin.column().at(floor);
        if (!bridge.isContact(ctx, contact, cabin.column()) || !ctx.level().hasChunkAt(input.position())) return false;
        if (input.transmitter() == null) return feeds(ctx.level(), contact, input.position(),
                ctx.level().getBlockState(input.position()), !input.remote());
        var transmitter = bridge.worldLink(ctx, input.transmitter());
        var receiver = bridge.worldLink(ctx, input.receiver());
        return bridge.linksMatch(transmitter, receiver) && receiver.frequencyItems().equals(input.frequencyItems())
                && ElevatorCallLinks.buttonFeeds(ctx.level(), input.transmitter(), input.position())
                && feeds(ctx.level(), contact, input.receiver(), ctx.level().getBlockState(input.receiver()), false);
    }

    private static Map<String, Integer> coordinates(BlockPos pos) { return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()); }

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
