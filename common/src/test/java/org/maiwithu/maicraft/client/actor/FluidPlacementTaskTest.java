// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementRules;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementAim;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTask;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;

/** 用真实角色动作端口与原版桶射线验证只倒一次，显式延迟服务器确认；不把测试区块变化冒称实机验收。 */
public final class FluidPlacementTaskTest {
    private static final BlockPos AT = new BlockPos(3,1,3);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        originalBucketAndAcknowledgement(); reuseAndFailures(); irregularContainment(); cancellationDoesNotPourAgain();
        replayOffsetStance(); arrivedStanceCannotWaitForever();
        System.out.println("FluidPlacementTaskTest: passed");
    }

    private static void originalBucketAndAcknowledgement() throws Exception {
        try (var f = fixture()) {
            f.inventory.setItem(0,new ItemStack(Items.WATER_BUCKET)); installUse(f);
            var task = task(); var running = new FluidPlacementTask(f.player,task); running.start(f.player);
            submit(f,running);
            check(f.itemUses()==1 && f.blockUses()==0, "填源必须沿原生桶 USE_ITEM，不能点击背后的围挡");
            f.nextTick(); check(running.tick(f.player)==TaskState.RUNNING, "客户端预测源格和桶变化不能提前当作服务器接受");
            f.level.acknowledgedSequence=f.level.blockSequence; f.nextTick();
            check(running.tick(f.player)==TaskState.SUCCESS, "对应原生序号确认后，源格与桶账应完成同一回执");
            var result=running.result(TaskState.SUCCESS);
            check(Boolean.TRUE.equals(result.data().get("native_effect_verified")) && f.itemUses()==1, "确认和收尾不能补倒第二桶");
            check(Boolean.TRUE.equals(result.data().get("mechanical_retry_allowed")), "已结清源格允许后续只读复用");
        }
    }

    private static void reuseAndFailures() throws Exception {
        try(var f=fixture()) {
            f.set(AT,Blocks.WATER.defaultBlockState());
            var running=new FluidPlacementTask(f.player,task());
            NavigationSafetyContext.withProtectedArea(List.of(AT),List.of(),()->{running.start(f.player);return null;});
            check(running.tick(f.player)==TaskState.SUCCESS && f.itemUses()==0, "受保护的正确已有源格也可只读复用，不需要另一桶");
            check(Boolean.TRUE.equals(running.result(TaskState.SUCCESS).data().get("mechanical_retry_allowed")), "已有源格复用不应留下未提交桶的禁重试标记");
        }
        try(var f=fixture()) {
            var missing=new FluidPlacementTask(f.player,task()); missing.start(f.player);
            check(missing.tick(f.player)==TaskState.FAILED && f.itemUses()==0, "缺少真实满桶时不能倒入虚构流体"); missing.result(TaskState.FAILED);
        }
        try(var f=fixture()) {
            f.inventory.setItem(0,new ItemStack(Items.WATER_BUCKET)); var guarded=new FluidPlacementTask(f.player,task());
            NavigationSafetyContext.withProtectedArea(List.of(AT),List.of(),()->{guarded.start(f.player);return null;});
            check(guarded.tick(f.player)==TaskState.FAILED && f.itemUses()==0, "新增源格不可绕过保护区域"); guarded.result(TaskState.FAILED);
        }
        try(var f=fixture()) {
            f.inventory.setItem(0,new ItemStack(Items.WATER_BUCKET)); var changed=new FluidPlacementTask(f.player,task()); changed.start(f.player);
            f.set(AT,Blocks.STONE.defaultBlockState());
            check(changed.tick(f.player)==TaskState.FAILED && f.itemUses()==0, "出手前目标变成存量方块时不能让桶自行破坏它"); changed.result(TaskState.FAILED);
        }
    }

    private static void irregularContainment() throws Exception {
        try(var f=fixture()) {
            Set<BlockPos> shape=Set.of(new BlockPos(6,1,6),new BlockPos(7,1,6),new BlockPos(7,1,7),new BlockPos(8,1,7));
            for(BlockPos at:shape) for(Direction side:Direction.Plane.HORIZONTAL)
                if(!shape.contains(at.relative(side))) f.set(at.relative(side),Blocks.GLASS.defaultBlockState());
            for(BlockPos at:shape) check(FluidPlacementRules.placementProblem(f.level,at,Blocks.WATER.defaultBlockState(),shape)==null,
                    "不规则多格池应根据声明区域逐边检查，而不是只识别固定单格模板");
            BlockPos boundary=new BlockPos(6,1,5); f.set(boundary,Blocks.AIR.defaultBlockState());
            check(FluidPlacementRules.placementProblem(f.level,new BlockPos(6,1,6),Blocks.WATER.defaultBlockState(),shape)!=null,
                    "缺少侧壁时必须先停，不能让桶向蓝图外漫流");
        }
    }

    private static void cancellationDoesNotPourAgain() throws Exception {
        try(var f=fixture()) {
            f.inventory.setItem(0,new ItemStack(Items.WATER_BUCKET)); installUse(f);
            var running=new FluidPlacementTask(f.player,task()); running.start(f.player); submit(f,running); f.nextTick();
            running.stop(f.player,Task.StopReason.REPLACED); var result=running.result(TaskState.CANCELLED);
            check(f.itemUses()==1 && Boolean.TRUE.equals(result.data().get("outcome_uncertain"))
                            && Boolean.FALSE.equals(result.data().get("mechanical_retry_allowed")),
                    "未确认时取消只结束旧操作，保留不确定结果，不能再倒一桶或假称撤销");
        }
    }

    private static void replayOffsetStance() throws Exception {
        try(var f=fixture()) {
            // 复现实机 90 格固体与 8 格源流体的布局；只把坐标整体平移到夹具区块，不移动角色到理想格中心。
            Set<BlockPos> sources=Set.of(new BlockPos(10,1,5),new BlockPos(10,1,6),new BlockPos(11,1,4),new BlockPos(11,1,5),
                    new BlockPos(11,1,6),new BlockPos(12,1,4),new BlockPos(12,1,5),new BlockPos(12,1,6));
            Set<BlockPos> installation=new HashSet<>();
            for(int x=8;x<=14;x++)for(int z=2;z<=8;z++)for(int y=0;y<=1;y++) {
                BlockPos at=new BlockPos(x,y,z); installation.add(at);
                f.set(at,sources.contains(at)?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState());
            }
            BlockPos target=new BlockPos(11,1,4); f.position(new Vec3(12.716992959374187,2,7.715705611363118));
            f.inventory.setItem(0,new ItemStack(Items.WATER_BUCKET));
            check(FirstPersonInteractionTargeting.visibleBucketHit(f.level,f.player,f.player.getEyePosition(),
                            target,4.5,Items.WATER_BUCKET)==null,"原中心射线应重现实机超出远墙触及范围的问题");
            var hit=FluidPlacementAim.find(f.level,f.player,f.player.getEyePosition(),target,4.5,Items.WATER_BUCKET);
            check(hit!=null && hit.getBlockPos().relative(hit.getDirection()).equals(target)
                            && hit.getLocation().distanceTo(f.player.getEyePosition())<4.5,
                    "真实偏心站位仍可命中近侧支撑面，并必须落到原声明源格");
            f.mode.itemUse=player->{f.level.blockSequence++;f.set(target,Blocks.WATER.defaultBlockState());f.inventory.setItem(0,new ItemStack(Items.BUCKET));};
            var running=new FluidPlacementTask(f.player,new FluidPlacementTaskRecord("offset-stance",1000,target,
                    Blocks.WATER.defaultBlockState(),sources,installation)); running.start(f.player); submit(f,running);
            check(f.itemUses()==1 && Boolean.TRUE.equals(running.progress().get("actual_bucket_ray_available")),
                    "实际站位应进入瞄准和原生桶提交，不能再次停在导航到达判断中");
            f.level.acknowledgedSequence=f.level.blockSequence;f.nextTick();
            check(running.tick(f.player)==TaskState.SUCCESS,"偏心站位仍须经过服务器确认和桶账核验");running.result(TaskState.SUCCESS);
        }
    }

    private static void arrivedStanceCannotWaitForever() throws Exception {
        try(var f=fixture()) {
            // 已选站位抵达后可能因实际眼位或环境变化而点不到；这个阶段必须拒绝该格，而不是继续请求同格路线。
            BlockPos stale=new BlockPos(6,2,6);f.set(stale.below(),Blocks.STONE.defaultBlockState());f.position(new Vec3(6.85,2,6.85));
            var running=new FluidPlacementTask(f.player,task());
            ActorControlTestHarness.field(FluidPlacementTask.class,"stance").set(running,stale);
            var approach=FluidPlacementTask.class.getDeclaredMethod("approach");approach.setAccessible(true);approach.invoke(running);
            check(ActorControlTestHarness.field(FluidPlacementTask.class,"stance").get(running)==null
                            && running.progress().get("phase").equals("reselecting_stance")
                            && ((Number)running.progress().get("rejected_stances")).intValue()>0 && f.itemUses()==0,
                    "实际桶射线不可用的已抵达格须有界换站，不能假成功或继续无进展等待");
            running.result(TaskState.CANCELLED);
        }
    }

    private static InteractionWorldTestHarness fixture() throws Exception {
        var f=new InteractionWorldTestHarness();
        var dimension=new DimensionType(OptionalLong.empty(),true,false,false,true,1.0,true,false,0,16,16,
                BlockTags.INFINIBURN_OVERWORLD,ResourceLocation.withDefaultNamespace("overworld"),0,
                new DimensionType.MonsterSettings(false,false,ConstantInt.of(0),0));
        ActorControlTestHarness.field(Level.class,"dimensionTypeRegistration").set(f.level,Holder.direct(dimension));
        for(Direction side:Direction.Plane.HORIZONTAL) f.set(AT.relative(side),Blocks.GLASS.defaultBlockState());
        f.position(new Vec3(2.5,2,3.5)); return f;
    }
    private static FluidPlacementTaskRecord task() {
        Set<BlockPos> installation=new HashSet<>(Set.of(AT,AT.below()));
        for(Direction side:Direction.Plane.HORIZONTAL) installation.add(AT.relative(side));
        return new FluidPlacementTaskRecord("fluid-test",1000,AT,Blocks.WATER.defaultBlockState(),Set.of(AT),installation);
    }
    private static void installUse(InteractionWorldTestHarness f) {
        // 夹具模拟原版预测和分包同步；生产代码仍只通过 NativeActionPort 发出一次实际 useItem。
        f.mode.itemUse=player->{f.level.blockSequence++;f.set(AT,Blocks.WATER.defaultBlockState());f.inventory.setItem(0,new ItemStack(Items.BUCKET));};
    }
    private static void submit(InteractionWorldTestHarness f,FluidPlacementTask running) throws Exception {
        for(int i=0;i<12&&f.itemUses()==0;i++) {
            check(running.tick(f.player)==TaskState.RUNNING,"准备桶或视线时不能提前结束");
            Vec3 aim=(Vec3)ActorControlTestHarness.field(FluidPlacementTask.class,"aim").get(running);
            if(aim!=null){Vec3 direction=aim.subtract(f.player.getEyePosition());f.player.setYRot((float)Math.toDegrees(Math.atan2(-direction.x,direction.z)));
                f.player.setXRot((float)-Math.toDegrees(Math.atan2(direction.y,Math.sqrt(direction.horizontalDistanceSqr()))));}
            if(f.itemUses()==0)f.nextTick();
        }
        check(f.itemUses()==1,"实际相机对齐后必须只提交一次桶动作");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
