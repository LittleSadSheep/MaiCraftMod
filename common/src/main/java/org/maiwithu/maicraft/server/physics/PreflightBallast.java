package org.maiwithu.maicraft.server.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsTrim;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;

/** 起飞前只推荐有连接面且当前为空的配重格，重量从服务端当前物理配置读取。 */
final class PreflightBallast {
    private PreflightBallast() {}
    static List<PhysicsTrim.Ballast> candidates(PhysicsBlockEdits view, PhysicsBody body, JsonArray requested) {
        var result = new ArrayList<PhysicsTrim.Ballast>();
        if (requested != null) {
            if (requested.size() > 64) throw new IllegalArgumentException("最多声明 64 个配重候选格");
            for (var raw : requested) {
                JsonObject row = raw.getAsJsonObject(); BlockPos local = PhysicsBlockEdits.local(row.getAsJsonObject("position"));
                add(result,view,local,PhysicsBlockEdits.state(row),row.has("id") ? row.get("id").getAsString() : local.toShortString());
            }
        } else {
            // 默认在质心附近及下方找附着位置，搜索范围显式固定；模型仍可声明自己的其他候选格。
            BlockPos center = BlockPos.containing(body.center().x(),body.center().y(),body.center().z());
            for (int radius=1;radius<=6;radius++) for(int y=-radius;y<=0;y++) for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
                if (Math.max(Math.abs(x),Math.max(-y,Math.abs(z))) != radius) continue;
                BlockPos local = center.offset(x,y,z);
                add(result,view,local,Blocks.IRON_BLOCK.defaultBlockState(),"iron:"+local.toShortString());
                if (result.size() >= 64) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }
    private static void add(List<PhysicsTrim.Ballast> result, PhysicsBlockEdits view, BlockPos local,
                            BlockState state, String id) {
        if(Math.abs(local.getX())>256||Math.abs(local.getY())>256||Math.abs(local.getZ())>256) return;
        BlockPos pos = local.offset(view.origin);
        if (!view.level.hasChunkAt(pos) || !view.getBlockState(pos).isAir()) return;
        boolean attached = false;
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.relative(side);
            if (view.level.hasChunkAt(neighbor) && !view.getBlockState(neighbor).getCollisionShape(view,neighbor).isEmpty()) attached = true;
        }
        if (!attached) return;
        double mass = PhysicsBlockEdits.mass(view,pos,state);
        if (mass <= 0) return;
        result.add(new PhysicsTrim.Ballast(id,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                new PhysicsVector(local.getX()+.5,local.getY()+.5,local.getZ()+.5),mass,1));
    }
}
