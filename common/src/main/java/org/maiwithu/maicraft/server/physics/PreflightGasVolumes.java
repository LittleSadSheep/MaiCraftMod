package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.maiwithu.maicraft.core.integration.physics.balance.BalloonEnvelope;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 起飞前连同未形成的气球一起检查；补上最后一块蒙皮也能预测，多台气源共享同一容积。 */
final class PreflightGasVolumes {
    private static final String GAS="dev.eriksonn.aeronautics.content.blocks.hot_air.BlockEntityLiftingGasProvider";
    private static final TagKey<Block> AIRTIGHT=TagKey.create(Registries.BLOCK,ResourceLocation.parse("aeronautics:airtight"));
    private record Supply(BlockEntity source,double amount,double strength,Object balloon) {}
    private static final class Volume {
        final BalloonEnvelope.Result envelope; final List<Supply> supply=new ArrayList<>();
        Volume(BalloonEnvelope.Result envelope) { this.envelope=envelope; }
    }
    private PreflightGasVolumes() {}
    static PhysicsBody model(Object ship,PhysicsBody body,PhysicsBlockEdits view,boolean target) {
        if(!NativeApi.present(GAS)) return body;
        var unknowns=new ArrayList<>(body.unknowns()); var loads=new ArrayList<>(body.loads());
        Object plot=NativeApi.call(ship,null,"getPlot"),box=NativeApi.call(plot,null,"getBoundingBox");
        BlockPos min=point(box,"min").subtract(view.origin),max=point(box,"max").subtract(view.origin);
        for(BlockPos pos:view.replacements.keySet()) {
            BlockPos p=pos.subtract(view.origin);
            min=new BlockPos(Math.min(min.getX(),p.getX()),Math.min(min.getY(),p.getY()),Math.min(min.getZ(),p.getZ()));
            max=new BlockPos(Math.max(max.getX(),p.getX()),Math.max(max.getY(),p.getY()),Math.max(max.getZ(),p.getZ()));
        }
        var bounds=new BalloonEnvelope.Bounds(cell(min.offset(-1,-1,-1)),cell(max.offset(1,1,1)));
        Map<Set<BalloonEnvelope.Cell>,Volume> volumes=new HashMap<>();
        Set<BlockPos> seen=new HashSet<>();
        for(Object holder:(Iterable<?>)NativeApi.call(plot,null,"getLoadedChunks")) {
            LevelChunk chunk=(LevelChunk)NativeApi.call(holder,null,"getChunk");
            if(chunk==null) { unknowns.add("unmodeled:气源所在结构区块尚未加载");continue; }
            for(BlockEntity source:chunk.getBlockEntities().values()) {
                BlockPos pos=source.getBlockPos();
                if(!seen.add(pos)||!NativeApi.is(source,GAS)) continue;
                if(view.replacements.containsKey(pos)&&view.getBlockState(pos).getBlock()!=source.getBlockState().getBlock()) continue;
                try {
                    BlockPos start=seed(source,view);
                    if(start==null) { unknowns.add("气源 "+pos.subtract(view.origin)+" 上方没有可接收气体的蒙皮");continue; }
                    var result=BalloonEnvelope.inspect(p->{
                        BlockPos storage=new BlockPos(p.x(),p.y(),p.z()).offset(view.origin);
                        if(!view.level.hasChunkAt(storage)) return BalloonEnvelope.Kind.UNKNOWN;
                        var state=view.getBlockState(storage);
                        return state.is(AIRTIGHT)?BalloonEnvelope.Kind.AIRTIGHT:state.isAir()?BalloonEnvelope.Kind.AIR:BalloonEnvelope.Kind.SOLID;
                    },bounds,cell(start.subtract(view.origin)),32768);
                    if(!result.state().equals("enclosed")) {
                        unknowns.add((result.state().equals("leaking")?"":"unmodeled:")+"气源 "+pos.subtract(view.origin)+" 容气状态: "+result.state());continue;
                    }
                    double amount=((Number)NativeApi.call(source,null,"getGasOutput")).doubleValue();
                    if(target&&amount==0&&source.getClass().getSimpleName().equals("HotAirBurnerBlockEntity")) {
                        Object type=NativeApi.constant("com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour","TYPE");
                        Object setting=NativeApi.call(source,null,"getBehaviour",type);
                        amount=((Number)NativeApi.call(setting,null,"getValue")).doubleValue();
                        unknowns.add("气源 "+pos.subtract(view.origin)+" 按当前旋钮设置启用后的供气量预测；实际红石未被改变");
                    }
                    Object gas=NativeApi.call(source,null,"getLiftingGasType"),balloon=NativeApi.call(source,null,"getBalloon");
                    double strength=((Number)NativeApi.call(gas,null,"getLiftStrength")).doubleValue();
                    volumes.computeIfAbsent(result.cells(),key->new Volume(result)).supply.add(new Supply(source,amount,strength,balloon));
                } catch(RuntimeException unavailable) { unknowns.add("unmodeled:气源 "+pos.subtract(view.origin)+": "+unavailable.getMessage()); }
            }
        }
        for(Volume volume:volumes.values()) {
            volume.supply.sort(Comparator.comparingLong(supply->supply.source().getBlockPos().asLong()));
            double lift=0,amount=0;
            if(target) {
                for(var supply:volume.supply) { amount+=supply.amount();lift+=supply.amount()*supply.strength(); }
                if(amount>0) lift*=Math.min(1,volume.envelope.capacity()/amount);
            } else {
                Set<Object> counted=Collections.newSetFromMap(new IdentityHashMap<>());
                for(var supply:volume.supply) if(supply.balloon()!=null&&counted.add(supply.balloon())) {
                    lift+=((Number)NativeApi.call(supply.balloon(),null,"getTotalLift")).doubleValue();
                    amount+=((Number)NativeApi.call(supply.balloon(),null,"getTotalFilledVolume")).doubleValue();
                }
                if(amount>0) lift*=Math.min(1,volume.envelope.capacity()/amount);
            }
            PhysicsVector point=volume.envelope.center();
            PhysicsVector world=body.position().add(body.rotation().world(point.subtract(body.center())));
            double pressure=((Number)NativeApi.call(null,"dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData",
                    "getAirPressure",view.level,world.mutable())).doubleValue();
            BlockPos source=volume.supply.getFirst().source().getBlockPos().subtract(view.origin);
            loads.add(new PhysicsBody.Load("balloon:"+source.getX()+","+source.getY()+","+source.getZ(),"sable:balloon_lift",
                    point,body.gravity().scale(-lift*pressure),PhysicsVector.ZERO,PhysicsBody.Frame.WORLD,false,0));
        }
        unknowns.add("气球布局预测使用目标稳态容气量；充气、泄漏过程及气压梯度引起的瞬态变化尚未复演");
        return new PhysicsBody(body.structureId(),body.dimension(),body.tick(),body.mass(),body.center(),body.inertia(),body.rotation(),
                body.position(),body.velocity(),body.angularVelocity(),body.gravity(),loads,unknowns);
    }
    private static BlockPos seed(BlockEntity source,PhysicsBlockEdits view) {
        Object server=NativeApi.call(null,"dev.eriksonn.aeronautics.config.AeroConfig","server");
        Object blocks=NativeApi.field(server,null,"blocks");
        String key=source.getClass().getSimpleName().equals("HotAirBurnerBlockEntity")?"hotAirBurnerMaxRange":"steamVentMaxRange";
        int range=((Number)NativeApi.call(NativeApi.field(blocks,null,key),null,"get")).intValue();
        if(range<0||range>512) throw new IllegalArgumentException("气源原生射程超出有界预览范围");
        for(int dy=1;dy<=range;dy++) {
            BlockPos pos=source.getBlockPos().above(dy);
            if(!view.level.hasChunkAt(pos)) throw new IllegalArgumentException("气源视线经过未加载区域");
            var state=view.getBlockState(pos);
            if(state.getCollisionShape(view,pos).toAabbs().stream().noneMatch(b->b.minX<=.5&&b.maxX>=.5&&b.minZ<=.5&&b.maxZ>=.5)) continue;
            return state.is(AIRTIGHT)?pos.below():null;
        }
        return null;
    }
    private static BlockPos point(Object box,String prefix) {
        return new BlockPos(((Number)NativeApi.call(box,null,prefix+"X")).intValue(),
                ((Number)NativeApi.call(box,null,prefix+"Y")).intValue(),((Number)NativeApi.call(box,null,prefix+"Z")).intValue());
    }
    private static BalloonEnvelope.Cell cell(BlockPos pos) { return new BalloonEnvelope.Cell(pos.getX(),pos.getY(),pos.getZ()); }
}
