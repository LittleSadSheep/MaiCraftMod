package org.maiwithu.maicraft.mcp;

import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class NearbyCollisionPerceptionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BlockPos feet = new BlockPos(0, 1, 0);
        AABB body = new AABB(0.2, 1, 0.2, 0.8, 2.8, 0.8);
        int[] reads = {0};
        BlockGetter world = (BlockGetter) Proxy.newProxyInstance(BlockGetter.class.getClassLoader(),
                new Class<?>[]{BlockGetter.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getBlockState")) {
                        reads[0]++;
                        BlockPos pos = (BlockPos) values[0];
                        if (pos.equals(new BlockPos(2, 1, 0)))
                            throw new AssertionError("unknown cells must not be queried");
                        return pos.equals(BlockPos.ZERO) ? Blocks.OAK_FENCE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState();
                    }
                    throw new AssertionError("unexpected world access: " + method.getName());
                });
        var result = NearbyCollisionPerception.observe(world, pos -> !pos.equals(new BlockPos(2, 1, 0)),
                feet, body, CollisionContext.empty());
        var row = result.getAsJsonArray("blocks").get(0).getAsJsonObject();
        check(row.get("block_id").getAsString().equals("minecraft:oak_fence"), "report the owning lower block");
        check(row.get("extends_outside_cell").getAsBoolean(), "fence extends above its block cell");
        check(row.get("intersects_player").getAsBoolean(), "air at feet does not mean the body is unobstructed");
        check(row.get("top_world_y").getAsDouble() == 1.5, "expose the actual collision top, not block height");
        check(result.get("unloaded_cells").getAsInt() == 1 && reads[0] == 124,
                "the bounded diagnostic skips unobserved world cells");
        check(result.get("shape_errors").getAsInt() == 0, "the real fence shape is readable");
        System.out.println("NearbyCollisionPerceptionTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
