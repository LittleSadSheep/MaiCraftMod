// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 缺少精确输入证据时必须就地停止，不能向无关机器扩展搜索。 */
public final class CreateExactUtilityEndpointTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var old = new CreateMechanicalPower.Endpoint("legacy anchor", BlockPos.ZERO);
        check(old.exactFace() == null, "legacy anchors retain nearest-endpoint survey semantics");
        // 区域中的近轴不能冒充明确点名的方块类型；普通工地名称继续只负责提供调查锚点。
        var typed=new CreateMechanicalPower.Endpoint("minecraft:hopper",BlockPos.ZERO);
        check(typed.accepts(Blocks.HOPPER.defaultBlockState()) && !typed.accepts(Blocks.STONE.defaultBlockState()),
                "registered block labels constrain actual endpoint state");
        check(old.accepts(Blocks.STONE.defaultBlockState()),"ordinary names preserve regional discovery");
        var request=new CreateMechanicalPower.Request(old,typed,CreateMechanicalPower.Transmission.CHAIN_CONVEYOR,true,false);
        var record=new CreateMechanicalPowerTaskRecord("typed",1000,request,null,null,List.of(),false,List.of());
        var source=new CreateMechanicalPlan.KineticEndpoint(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),Direction.UP,16,true);
        var wrong=new CreateMechanicalPlan.KineticEndpoint(new BlockPos(4,1,4),Blocks.STONE.defaultBlockState(),Direction.UP,0,false);
        try { CreateEconomicEndpointBridge.resolved(record,"minecraft:overworld",source,wrong); throw new AssertionError("wrong cached receiver accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().equals("mechanical_endpoint_type_mismatch"),"resolved handoff repeats type admission"); }
        try (var h = new InteractionWorldTestHarness()) {
            var exact = new CreateMechanicalPower.Endpoint("machine input", new BlockPos(4, 1, 4), Direction.UP);
            var search = new CreateEndpointEvidenceSearch(exact, false, false);
            check(search.tick(h.level) == CreateEndpointEvidenceSearch.Status.EXHAUSTED, "missing exact shaft must fail in one local observation");
            check(search.snapshot().endpoints().isEmpty() && search.snapshot().missingChunks() == 0,
                    "exact input absence cannot initiate unrelated chunk exploration");
            check(h.blockUses() == 0 && h.itemUses() == 0, "missing input must fail without actions");
        }
        CreateMechanicalPlacementGeometryTest.main(args);
        System.out.println("CreateExactUtilityEndpointTest: legacy and exact interface boundaries passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
