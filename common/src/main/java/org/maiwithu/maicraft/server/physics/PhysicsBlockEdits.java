package org.maiwithu.maicraft.server.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** 放铁块、换蒙皮及拆除只改这份方块视图；真实施工由客户端原生操作另行执行。 */
final class PhysicsBlockEdits implements BlockGetter {
    private static final String PROPERTIES = "dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper";
    final ServerLevel level; final BlockPos origin;
    final Map<BlockPos, BlockState> replacements = new LinkedHashMap<>();
    PhysicsBlockEdits(ServerLevel level, BlockPos origin, JsonArray edits) {
        this.level = level; this.origin = origin;
        if (edits.size() > 128) throw new IllegalArgumentException("单次结构补丁最多 128 格");
        for (var raw : edits) {
            JsonObject edit = raw.getAsJsonObject();
            BlockPos pos = local(edit.getAsJsonObject("position")).offset(origin);
            if (!level.hasChunkAt(pos)) throw new IllegalArgumentException("补丁涉及未加载的结构方块");
            if (replacements.putIfAbsent(pos, state(edit)) != null) throw new IllegalArgumentException("补丁重复声明同一格");
        }
    }
    static BlockPos local(JsonObject point) {
        return new BlockPos(ServerAccess.integer(point,"x",-256,256), ServerAccess.integer(point,"y",-256,256),
                ServerAccess.integer(point,"z",-256,256));
    }
    static BlockState state(JsonObject edit) {
        var id = ResourceLocation.parse(ServerAccess.text(edit,"block_id"));
        BlockState state = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(() -> new IllegalArgumentException("未知方块: " + id)).defaultBlockState();
        if (edit.has("properties")) for (var property : edit.getAsJsonObject("properties").entrySet())
            state = property(state, property.getKey(), property.getValue().getAsString());
        return state;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state, String key, String text) {
        @SuppressWarnings("unchecked") Property<T> property = (Property<T>) state.getBlock().getStateDefinition().getProperty(key);
        if (property == null) throw new IllegalArgumentException("未知方块属性: " + key);
        return state.setValue(property, property.getValue(text).orElseThrow(() -> new IllegalArgumentException("无效属性值: " + text)));
    }
    PhysicsBody apply(PhysicsBody source) {
        PhysicsBody body = source;
        var unknowns = new ArrayList<>(source.unknowns());
        for (var entry : replacements.entrySet()) {
            BlockPos pos = entry.getKey(); BlockState before = level.getBlockState(pos), after = entry.getValue();
            if (before.equals(after)) continue;
            double oldMass = mass(level,pos,before), newMass = mass(this,pos,after);
            PhysicsVector point = new PhysicsVector(pos.getX()-origin.getX()+.5,pos.getY()-origin.getY()+.5,pos.getZ()-origin.getZ()+.5);
            // 一次性相减可保留“替换唯一方块”的合法质量，不会出现临时零质量的虚构刚体。
            Matrix3d delta = inertia(this,pos,after,newMass).sub(inertia(level,pos,before,oldMass));
            body = body.ballast(newMass-oldMass, point, delta);
            if (before.hasBlockEntity() || after.hasBlockEntity()) unknowns.add("unmodeled:补丁 " + pos.subtract(origin) + " 改动方块实体，配置、动力接线及内部载荷须在施工后重新读取");
        }
        var loads = body.loads().stream().filter(load -> {
            BlockPos pos = BlockPos.containing(load.point().x()+origin.getX(),load.point().y()+origin.getY(),load.point().z()+origin.getZ());
            return !load.propulsion() || !replacements.containsKey(pos) || level.getBlockState(pos).getBlock()==replacements.get(pos).getBlock();
        }).toList();
        return new PhysicsBody(body.structureId(),body.dimension(),body.tick(),body.mass(),body.center(),body.inertia(),body.rotation(),
                body.position(),body.velocity(),body.angularVelocity(),body.gravity(),loads,unknowns);
    }
    static double mass(BlockGetter world, BlockPos p, BlockState state) {
        if (state.isAir()) return 0;
        return ((Number) NativeApi.call(null,PROPERTIES,"getMass",world,p,state)).doubleValue();
    }
    private static Matrix3d inertia(BlockGetter world, BlockPos p, BlockState state, double mass) {
        if (mass == 0) return new Matrix3d().zero();
        Vec3 multiplier = (Vec3) NativeApi.call(null,PROPERTIES,"getInertia",world,p,state);
        return multiplier == null ? new Matrix3d().scaling(mass/6)
                : new Matrix3d().scaling(multiplier.x*mass,multiplier.y*mass,multiplier.z*mass);
    }
    public BlockState getBlockState(BlockPos pos) {
        BlockState proposed=replacements.get(pos); return proposed==null?level.getBlockState(pos):proposed;
    }
    public BlockEntity getBlockEntity(BlockPos pos) { return replacements.containsKey(pos) ? null : level.getBlockEntity(pos); }
    public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    public int getHeight() { return level.getHeight(); }
    public int getMinBuildHeight() { return level.getMinBuildHeight(); }
}
