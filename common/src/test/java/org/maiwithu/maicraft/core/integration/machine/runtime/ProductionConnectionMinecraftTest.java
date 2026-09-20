// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.network.MachineConnectionSystems;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionFixture.*;

/** 原生漏斗状态与实体驱动客户端发现，再验证确实发出中立系统请求；合成回执只测试协议，不宣称实机连通。 */
public final class ProductionConnectionMinecraftTest {
    private static final BlockPos HOPPER = new BlockPos(4, 65, 4);
    private static final List<BlockPos> PATH = List.of(HOPPER.above(), HOPPER, HOPPER.below());
    private ProductionConnectionMinecraftTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        realHopperDiscoveryIssuesAnItemsRequest();
        mismatchedStateAndOrdinaryInventoriesDoNotInventTransport();
        unsupportedNativeReplyRemainsUnsupported();
        neutralSystemRetainsStrictResponseBoundaries();
        System.out.println("ProductionConnectionMinecraftTest: 4 discovery/request/response groups passed; native world transfer not exercised");
    }

    private static void realHopperDiscoveryIssuesAnItemsRequest() {
        BlockState state = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN);
        var entity = new HopperBlockEntity(HOPPER, state);
        check("minecraft".equals(ProductionConnectionSurvey.nativeSystemAt(state, entity)), "real hopper exposes the neutral item transport system");
        var fixture = new ProductionConnectionFixture(PATH, "items", null);
        var survey = survey(fixture, Map.of(HOPPER, state), Map.of(HOPPER, entity));
        JsonObject result = settle(fixture, survey);
        check(fixture.sent.size() == 1, "hopper path must reach the server request boundary");
        JsonObject query = fixture.sent.getFirst();
        check("minecraft".equals(query.get("system").getAsString()) && "items".equals(query.get("medium").getAsString()), "hopper cannot masquerade as Create or Mekanism");
        check(query.getAsJsonArray("path").size() == 3 && query.get("from_face").getAsString().equals("down")
                && query.get("to_face").getAsString().equals("up"), "the exact barrel-hopper-machine direction remains explicit");
        check(query.get("resource_id").getAsString().equals(fixture.exact), "opaque component identity remains unchanged");
        check(result.get("verified_connection").getAsBoolean() && result.getAsJsonArray("intermediate").isEmpty(), "minecraft reply uses ordinary non-AE wire shape");
        check(!result.get("flow_verified").getAsBoolean() && !result.get("production_verified").getAsBoolean(), "discovery and simulated reply cannot become real flow");
    }

    private static void mismatchedStateAndOrdinaryInventoriesDoNotInventTransport() {
        var hopper = new HopperBlockEntity(HOPPER, Blocks.HOPPER.defaultBlockState());
        var barrel = new BarrelBlockEntity(HOPPER, Blocks.BARREL.defaultBlockState());
        check(ProductionConnectionSurvey.nativeSystemAt(Blocks.HOPPER.defaultBlockState(), barrel) == null, "hopper-looking state alone is not a native hopper");
        check(ProductionConnectionSurvey.nativeSystemAt(Blocks.BARREL.defaultBlockState(), hopper) == null, "stale hopper entity cannot override a barrel state");
        check(ProductionConnectionSurvey.nativeSystemAt(Blocks.HOPPER.defaultBlockState(), null) == null, "missing native entity keeps discovery unknown");
        check(ProductionConnectionSurvey.nativeSystemAt(Blocks.BARREL.defaultBlockState(), barrel) == null, "ordinary container does not imply transport");
        var unknown = new ProductionConnectionFixture(PATH, "items", null);
        JsonObject result = settle(unknown, survey(unknown, Map.of(HOPPER, Blocks.BARREL.defaultBlockState()), Map.of(HOPPER, barrel)));
        check(unknown.sent.isEmpty() && !result.get("verified_connection").getAsBoolean(), "unknown paths cannot issue a guessed transport request");
        var fluids = new ProductionConnectionFixture(PATH, "fluids", null);
        settle(fluids, survey(fluids, Map.of(HOPPER, Blocks.HOPPER.defaultBlockState()), Map.of(HOPPER, hopper)));
        check(fluids.sent.isEmpty(), "an item hopper does not discover a native fluid route");
    }

    private static void unsupportedNativeReplyRemainsUnsupported() {
        var fixture = new ProductionConnectionFixture(PATH, "items", null);
        fixture.replyEdit = response -> {
            response.addProperty("status", "unsupported"); response.addProperty("verified_connection", false);
            response.addProperty("operational", false); response.addProperty("resource_compatibility", "unknown");
            for (var raw : response.getAsJsonArray("edges")) {
                var edge = raw.getAsJsonObject(); edge.addProperty("status", "unsupported"); edge.addProperty("native_support", false);
                edge.addProperty("verified_connection", false); edge.addProperty("operational", false);
                edge.addProperty("reason", "no_supported_native_item_transport_on_edge");
            }
            return response;
        };
        var entity = new HopperBlockEntity(HOPPER, Blocks.HOPPER.defaultBlockState());
        JsonObject result = settle(fixture, survey(fixture, Map.of(HOPPER, entity.getBlockState()), Map.of(HOPPER, entity)));
        check(fixture.sent.size() == 1 && result.get("status").getAsString().equals("unsupported")
                && !result.get("verified_connection").getAsBoolean(), "client discovery never overrules a server rejection");
    }

    private static void neutralSystemRetainsStrictResponseBoundaries() {
        check(MachineConnectionSystems.supports("minecraft", "items"), "new pair must be accepted on both ends");
        for (String medium : List.of("energy", "fluids", "chemicals", "kinetic"))
            check(!MachineConnectionSystems.supports("minecraft", medium), "neutral item transport cannot accept other media");
        for (String system : List.of("create", "ae2", "mekanism"))
            check(MachineConnectionSystems.supports(system, "items") && MachineConnectionSystems.supports(system, "energy"), "old accepted system pairs remain compatible");
        var fixture = new ProductionConnectionFixture(PATH, "items", "minecraft");
        fixture.replyEdit = response -> { response.addProperty("system", "create"); return response; };
        check(!fixture.settle().get("verified_connection").getAsBoolean(), "a mismatched system reply remains rejected");
        var wrongMedium = new ProductionConnectionFixture(PATH, "items", "minecraft");
        wrongMedium.replyEdit = response -> { response.addProperty("medium", "energy"); return response; };
        check(!wrongMedium.settle().get("verified_connection").getAsBoolean(), "a mismatched medium reply remains rejected");
    }

    private static ProductionConnectionSurvey survey(ProductionConnectionFixture fixture, Map<BlockPos, BlockState> states,
                                                     Map<BlockPos, BlockEntity> entities) {
        return new ProductionConnectionSurvey(fixture.plan, fixture, fixture::binding, () -> fixture.dimension, () -> fixture.tick,
                position -> ProductionConnectionSurvey.nativeSystemAt(states.getOrDefault(position, Blocks.BARREL.defaultBlockState()), entities.get(position)));
    }
    private static JsonObject settle(ProductionConnectionFixture fixture, ProductionConnectionSurvey survey) {
        for (int tick = 0; tick < 100; tick++) {
            fixture.tick++;
            JsonObject result = survey.tick(fixture.plan.manifest().links().getFirst());
            if (result != null) return result;
        }
        throw new AssertionError("minecraft connection request did not settle");
    }
}
