package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorBridge.WorldLink;

/**
 * 找能呼叫指定楼层的红石发射端和按钮；频率、接收模式、原生传输范围及按钮供电关系都要匹配。
 */
final class ElevatorCallLinks {
    private static final int RADIUS = 32, BLOCK_ENTITY_BUDGET = 4096, LINK_BUDGET = 96, BUTTON_BUDGET = 128;
    private final LocalPlayerContext ctx;
    private final CreateElevatorBridge bridge;
    private final Map<BlockPos, List<BlockPos>> buttons = new HashMap<>();
    private final List<Map<String, Object>> matchedRoutes = new ArrayList<>();
    private List<WorldLink> transmitters;
    private int visited, testedButtons;
    private boolean truncated;

    ElevatorCallLinks(LocalPlayerContext ctx, CreateElevatorBridge bridge) { this.ctx = ctx; this.bridge = bridge; }

    List<WorldLink> matching(WorldLink receiver) {
        if (transmitters == null) scan();
        List<WorldLink> found = new ArrayList<>();
        for (WorldLink link : transmitters) if (bridge.linksMatch(link, receiver)) {
            found.add(link);
            if (matchedRoutes.size() < 12) matchedRoutes.add(Map.of("transmitter", link.position().toShortString(),
                    "receiver", receiver.position().toShortString(), "frequency_items", receiver.frequencyItems()));
        }
        return List.copyOf(found);
    }

    // 这里传入的 hasChunkAt 在原版客户端不能确认区块已加载；相应未知区域判断目前不能依赖它。
    List<BlockPos> buttons(WorldLink transmitter) {
        return buttons.computeIfAbsent(transmitter.position(), pos -> {
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos candidate : buttonCandidates(pos)) {
                if (!ctx.level().hasChunkAt(candidate)) continue;
                if (!buttonFeeds(ctx.level(), pos, candidate)) continue;
                if (testedButtons >= BUTTON_BUDGET) { truncated = true; break; }
                testedButtons++;
                found.add(candidate);
            }
            found.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(ctx.player().position())));
            return List.copyOf(found);
        });
    }

    Map<String, Object> evidence() {
        return Map.of("radius", RADIUS, "block_entities_visited", visited,
                "transmitters_found", transmitters == null ? 0 : transmitters.size(), "buttons_checked", testedButtons,
                "truncated", truncated, "matching_routes_sample", List.copyOf(matchedRoutes), "scope", "loaded_client_state_no_server_ack");
    }

    private void scan() {
        List<WorldLink> found = new ArrayList<>();
        var center = ctx.player().chunkPosition();
        // Near chunks first; the fixed budget also bounds dense machine/storage bases.
        search: for (int ring = 0; ring <= 2; ring++) for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            var chunk = ctx.level().getChunkSource().getChunkNow(center.x + dx, center.z + dz);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                if (visited >= BLOCK_ENTITY_BUDGET || found.size() >= LINK_BUDGET) { truncated = true; break search; }
                visited++;
                if (entry.getKey().distToCenterSqr(ctx.player().position()) > RADIUS * RADIUS || !bridge.isWorldLink(entry.getValue())) continue;
                WorldLink link = bridge.worldLink(ctx, entry.getKey());
                if (link != null && !link.receiver()) found.add(link);
            }
        }
        found.sort(Comparator.comparingDouble(link -> link.position().distToCenterSqr(ctx.player().position())));
        transmitters = List.copyOf(found);
    }

    static boolean matches(WorldLink transmitter, WorldLink receiver, boolean withinNativeRange) {
        return transmitter != null && receiver != null && !transmitter.receiver() && receiver.receiver()
                && transmitter.frequency().equals(receiver.frequency()) && withinNativeRange;
    }

    /** A button is either adjacent, or strongly powers an adjacent conducting support: at most 24 positions. */
    static List<BlockPos> buttonCandidates(BlockPos transmitter) {
        var candidates = new LinkedHashSet<BlockPos>();
        for (Direction side : Direction.values()) {
            BlockPos adjacent = transmitter.relative(side);
            candidates.add(adjacent);
            for (Direction attachment : Direction.values()) candidates.add(adjacent.relative(attachment));
        }
        candidates.remove(transmitter);
        return List.copyOf(candidates);
    }

    static boolean buttonFeeds(BlockGetter world, BlockPos transmitter, BlockPos button) {
        var state = world.getBlockState(button);
        return state.getBlock() instanceof ButtonBlock && ElevatorSurvey.feeds(world, transmitter, button, state, true);
    }
}
