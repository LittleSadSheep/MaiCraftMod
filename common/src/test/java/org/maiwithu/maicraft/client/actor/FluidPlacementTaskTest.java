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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.integration.machine.assembly.LavaPlacementSafety;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementRules;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementAim;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTask;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.assembly.AssemblyInteractionGeometry;
import net.minecraft.world.entity.Pose;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;

/** 用真实角色动作端口与原版桶射线验证只倒一次，显式延迟服务器确认；不把测试区块变化冒称实机验收。 */
public final class FluidPlacementTaskTest {
    private static final BlockPos AT = new BlockPos(3,1,3);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        originalBucketAndAcknowledgement(); reuseAndFailures(); openFlowChannels(); cancellationDoesNotPourAgain();
        replayOffsetStance(); arrivedStanceCannotWaitForever();
        newlyUsableFootingReplacesStaleNavigation();
        lavaPlacementAvoidsFutureFlow();
        sourceRemovalUsesOneAcknowledgedBucket();
        nativeReactionRemainsConfirmed();
        castingNeverEncasesThePlayer();
        sourceCanBeCollectedFromHighBank();
        System.out.println("FluidPlacementTaskTest: passed");
    }

    private static void nativeReactionRemainsConfirmed() throws Exception {
        try (var f = fixture()) {
            // 火把的选择框不等于桶落点；应找到邻接面的合法射线，再让原生水流替换火把。
            f.set(AT, Blocks.TORCH.defaultBlockState()); f.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET)); installUse(f);
            var running = new FluidPlacementTask(f.player, task()); running.start(f.player); submit(f, running);
            f.level.acknowledgedSequence = f.level.blockSequence; f.nextTick();
            check(running.tick(f.player) == TaskState.SUCCESS && f.itemUses() == 1,
                    "可被原生水替换的占用不要求先提交人工清障");
            running.result(TaskState.SUCCESS);
        }
        try (var f = fixture()) {
            // 对已被岩浆占据的目标真实提交一次水桶，夹具重放服务器结算成黑曜石及返桶，不能提前门控。
            f.set(AT, Blocks.LAVA.defaultBlockState()); f.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            f.mode.itemUse = player -> {
                f.level.blockSequence++; f.set(AT, Blocks.OBSIDIAN.defaultBlockState());
                f.inventory.setItem(0, new ItemStack(Items.BUCKET));
            };
            var running = new FluidPlacementTask(f.player, task()); running.start(f.player); submit(f, running);
            f.nextTick(); check(running.tick(f.player) == TaskState.RUNNING, "凝固和返桶预测不能绕过服务器确认");
            f.level.acknowledgedSequence = f.level.blockSequence; f.nextTick();
            check(running.tick(f.player) == TaskState.SUCCESS && f.itemUses() == 1, "已确认的原生反应不是未知操作，也不补发第二桶");
            var result = running.result(TaskState.SUCCESS);
            check(Boolean.TRUE.equals(result.data().get("native_effect_verified"))
                            && Boolean.FALSE.equals(result.data().get("outcome_uncertain"))
                            && Boolean.FALSE.equals(result.data().get("source_fluid_verified"))
                            && "minecraft:obsidian".equals(result.data().get("observed_block_id")),
                    "动作成功、源格未达成和实际黑曜石必须分别反馈");
        }
    }

    private static void sourceCanBeCollectedFromHighBank() throws Exception {
        try (var f = fixture()) {
            // 湖面附近没有水面高度的干燥站位，唯一岸台高两格；空桶仍能从岸台用原生射线触及水源。
            for (int x = 0; x <= 9; x++) for (int z = 0; z <= 9; z++)
                f.set(new BlockPos(x, 1, z), Blocks.WATER.defaultBlockState());
            BlockPos feet = new BlockPos(5, 3, 3); f.set(feet.below(), Blocks.STONE.defaultBlockState());
            f.position(new Vec3(8.5, 3, 7.5));
            var search = AssemblyInteractionGeometry.searchStand(f.player, AT, Set.of(), eye -> {
                var hit = FluidPlacementAim.find(f.level, f.player, eye, AT, 4.5, Items.BUCKET);
                return hit == null ? null : hit.getLocation();
            }, Pose.STANDING, 4.5);
            check(feet.equals(search.position()) && search.standingCandidates() == 1 && search.visibleCandidates() == 1,
                    "native source collection includes a reachable bank two blocks above the water cell");
        }
    }

    private static void castingNeverEncasesThePlayer() throws Exception {
        for (boolean lava : new boolean[]{false, true}) try (var f = fixture()) {
            // 复现实机事故：角色站在待浇筑格里，即使能瞄到脚下支撑面，也必须先走到格外。
            f.set(AT.below(), Blocks.STONE.defaultBlockState()); f.position(new Vec3(lava ? 3.5 : 2.65, 1, 3.5));
            // 水桶用例从边缘滑入目标，岩浆桶用例已站在格内，两者都不能先提交再等身体反射救场。
            if (!lava) f.player.setDeltaMovement(.1, 0, 0);
            f.inventory.setItem(0, new ItemStack(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET));
            f.mode.itemUse = player -> {
                f.level.blockSequence++; f.set(AT, Blocks.OBSIDIAN.defaultBlockState());
                f.inventory.setItem(0, new ItemStack(Items.BUCKET));
            };
            var record = new FluidPlacementTaskRecord("occupied-casting-cell", 1000, AT,
                    (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState(), task().installation);
            var running = new FluidPlacementTask(f.player, record); running.start(f.player);
            ActorControlTestHarness.field(FluidPlacementTask.class, "selected").setBoolean(running, true);
            check(running.tick(f.player) == TaskState.RUNNING && f.itemUses() == 0
                    && "leaving_bucket_target".equals(running.progress().get("phase")),
                    "occupied casting cell relocates without submitting or rejecting the design");
            // 挪到旁边围挡顶面后继续同一张任务单；实际凝固仍通过服务器确认，而不是更换落点。
            f.position(new Vec3(2.5, 2, 3.5)); f.player.setDeltaMovement(Vec3.ZERO); f.nextTick(); submit(f, running);
            f.level.acknowledgedSequence = f.level.blockSequence; f.nextTick();
            check(running.tick(f.player) == TaskState.SUCCESS && f.itemUses() == 1,
                    "the same target is poured once after leaving the player body");
            running.result(TaskState.SUCCESS);
        }
    }

    private static void sourceRemovalUsesOneAcknowledgedBucket() throws Exception {
        for (boolean lava : new boolean[]{false,true}) try (var f = fixture()) {
            // 修改机器的旧水源或岩浆源先用空桶回收，服务器确认前既不能说拆完，也不能多取一次。
            var source = lava ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
            f.set(AT,source); f.inventory.setItem(0,new ItemStack(Items.BUCKET));
            f.mode.itemUse = player -> {
                f.level.blockSequence++; f.set(AT,Blocks.AIR.defaultBlockState());
                f.inventory.setItem(0,new ItemStack(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET));
            };
            var record = FluidPlacementTaskRecord.removeSource("source-removal",1000,AT,source,task().installation);
            var running = new FluidPlacementTask(f.player,record); running.start(f.player); submit(f,running);
            f.nextTick(); check(running.tick(f.player) == TaskState.RUNNING, "source disappearance alone cannot replace server acknowledgement");
            f.level.acknowledgedSequence = f.level.blockSequence; f.nextTick();
            check(running.tick(f.player) == TaskState.SUCCESS && f.itemUses() == 1 && f.blockUses() == 0,
                    "source removal settles exactly one native bucket use");
            var result = running.result(TaskState.SUCCESS);
            check(Boolean.TRUE.equals(result.data().get("source_fluid_removed"))
                    && Boolean.FALSE.equals(result.data().get("source_fluid_verified")), "removal receipt cannot claim a source was placed");
            var reuse = new FluidPlacementTask(f.player,record); reuse.start(f.player);
            check(reuse.tick(f.player) == TaskState.SUCCESS && f.itemUses() == 1, "already absent source does not consume another empty bucket");
            reuse.result(TaskState.SUCCESS);
        }
        try (var f = fixture()) {
            var source = Blocks.WATER.defaultBlockState(); f.set(AT,source); f.inventory.setItem(0,new ItemStack(Items.BUCKET));
            var running = new FluidPlacementTask(f.player,FluidPlacementTaskRecord.removeSource("changed-source",1000,AT,source,task().installation));
            running.start(f.player); f.set(AT,Blocks.LAVA.defaultBlockState());
            check(running.tick(f.player) == TaskState.FAILED && f.itemUses() == 0, "changed source cannot inherit the original pickup target");
            running.result(TaskState.FAILED);
        }
    }

    private static void newlyUsableFootingReplacesStaleNavigation() throws Exception {
        try (var f = fixture()) {
            // 上次站位被拒绝后，角色已来到能点到源格的新位置；不应仍强求到达旧的高处候选格。
            f.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET)); installUse(f);
            var running = new FluidPlacementTask(f.player, task()); running.start(f.player);
            ActorControlTestHarness.field(FluidPlacementTask.class, "relocate").setBoolean(running, true);
            ActorControlTestHarness.field(FluidPlacementTask.class, "stance").set(running, new BlockPos(10, 8, 10));
            submit(f, running);
            check(f.itemUses() == 1 && ((Number) running.progress().get("stance_attempts")).intValue() == 0,
                    "实际眼位可倒桶时直接提交原生动作，不再围绕旧候选寻路");
            f.level.acknowledgedSequence = f.level.blockSequence; f.nextTick();
            check(running.tick(f.player) == TaskState.SUCCESS, "新脚位仍经过完整源格与桶账核验");
            running.result(TaskState.SUCCESS);
        }
    }

    private static void originalBucketAndAcknowledgement() throws Exception {
        try (var f = fixture()) {
            // 复现刷石机的开放沟槽：水源旁留一格流水通道，另一端放岩浆；原生倒桶不能被未封闭围挡拦住。
            f.set(AT.east(), Blocks.AIR.defaultBlockState());
            f.set(AT.east(2), Blocks.LAVA.defaultBlockState());
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
            check(changed.tick(f.player)==TaskState.RUNNING && f.itemUses()==0, "目标占用不再提前否决，后续仍需真实射线与原生使用"); changed.result(TaskState.CANCELLED);
        }
    }

    private static void openFlowChannels() throws Exception {
        try(var f=fixture()) {
            // 多源池和开放沟槽都按真实落桶格施工；拆掉一面侧壁不应令未倒出的水提前失败。
            Set<BlockPos> shape=Set.of(new BlockPos(6,1,6),new BlockPos(7,1,6),new BlockPos(7,1,7),new BlockPos(8,1,7));
            for(BlockPos at:shape) for(Direction side:Direction.Plane.HORIZONTAL)
                if(!shape.contains(at.relative(side))) f.set(at.relative(side),Blocks.GLASS.defaultBlockState());
            for(BlockPos at:shape) check(FluidPlacementRules.placementProblem(f.level,at,Blocks.WATER.defaultBlockState())==null,
                    "不规则多格池的空源格应可直接倒桶");
            BlockPos boundary=new BlockPos(6,1,5); f.set(boundary,Blocks.AIR.defaultBlockState());
            check(FluidPlacementRules.placementProblem(f.level,new BlockPos(6,1,6),Blocks.WATER.defaultBlockState())==null,
                    "水应允许流入未声明为源格的开放通道");
            // 第二桶岩浆落在空格时，相邻流动水也不能提前否决；水与岩浆的相遇结果留给游戏结算。
            f.set(boundary,Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,3));
            check(FluidPlacementRules.placementProblem(f.level,new BlockPos(6,1,6),Blocks.LAVA.defaultBlockState())==null,
                    "相邻水流不能阻止在空目标格倒岩浆");
            check(FluidPlacementRules.placementProblem(f.level,boundary,Blocks.LAVA.defaultBlockState())==null,
                    "目标格本身已有水流也交给原生桶结算");
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
            // 单一中心射线仍会错过有效面；共享桶瞄准现在也应找到近侧支撑，不能再把旧缺陷作为预期结果。
            Vec3 eyes = f.player.getEyePosition();
            var centerHit = FirstPersonInteractionTargeting.bucketRay(f.level, f.player, eyes,
                    eyes.add(Vec3.atCenterOf(target).subtract(eyes).normalize().scale(4.5)), Items.WATER_BUCKET);
            check(!FirstPersonInteractionTargeting.acceptsBucketHit(f.level,target,Items.WATER_BUCKET,centerHit),
                    "原中心射线应重现实机超出远墙触及范围的问题");
            check(FirstPersonInteractionTargeting.visibleBucketHit(f.level,f.player,eyes,target,4.5,Items.WATER_BUCKET)!=null,
                    "共享交互与施工都应支持偏心站位上的真实近侧支撑面");
            var hit=FluidPlacementAim.find(f.level,f.player,f.player.getEyePosition(),target,4.5,Items.WATER_BUCKET);
            check(hit!=null && hit.getBlockPos().relative(hit.getDirection()).equals(target)
                            && hit.getLocation().distanceTo(f.player.getEyePosition())<4.5,
                    "真实偏心站位仍可命中近侧支撑面，并必须落到原声明源格");
            f.mode.itemUse=player->{f.level.blockSequence++;f.set(target,Blocks.WATER.defaultBlockState());f.inventory.setItem(0,new ItemStack(Items.BUCKET));};
            var running=new FluidPlacementTask(f.player,new FluidPlacementTaskRecord("offset-stance",1000,target,
                    Blocks.WATER.defaultBlockState(),installation)); running.start(f.player); submit(f,running);
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

    // 复现采收后站在干燥产物格、向邻格倒岩浆的情形：先阻止点击，再到台沿完成同一任务。
    private static void lavaPlacementAvoidsFutureFlow() throws Exception {
        try (var f = fixture()) {
            f.set(AT.below(), Blocks.STONE.defaultBlockState());
            f.set(AT.west().below(), Blocks.STONE.defaultBlockState());
            f.set(AT.west(), Blocks.AIR.defaultBlockState()); f.position(new Vec3(2.5, 1, 3.5));
            check(LavaPlacementSafety.mayReachBody(f.level, AT, f.player.getBoundingBox()),
                    "a dry neighboring production cell will be reached by the newly poured lava");
            var behind = new AABB(1.2, 1, 3.2, 1.8, 2.8, 3.8);
            check(LavaPlacementSafety.mayReachBody(f.level, AT, behind), "the check follows more than the immediate source cell");
            f.set(AT.west(), Blocks.GLASS.defaultBlockState());
            check(!LavaPlacementSafety.mayReachBody(f.level, AT, behind), "an intact wall can protect a lower standing area");
            f.set(AT.west(), Blocks.AIR.defaultBlockState());
            check(LavaPlacementSafety.mayReachBody(f.level, AT.above(3), new AABB(3.2, 1, 3.2, 3.8, 2.8, 3.8)),
                    "a possible downward flow cannot be ignored when the body is below the source");
            check(!LavaPlacementSafety.mayReachBody(f.level, new BlockPos(7, 1, 7), new AABB(11.2, 1, 7.2, 11.8, 2.8, 7.8)),
                    "ordinary horizontal decay bounds the conservative flow region");
            f.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            f.mode.itemUse = player -> {
                f.level.blockSequence++; f.set(AT, Blocks.LAVA.defaultBlockState());
                f.inventory.setItem(0, new ItemStack(Items.BUCKET));
            };
            var running = new FluidPlacementTask(f.player, new FluidPlacementTaskRecord(
                    "lava-safe-stance", 1000, AT, Blocks.LAVA.defaultBlockState(), task().installation));
            running.start(f.player); running.tick(f.player); f.nextTick();
            // 把已有候选设为当前脚位，单独验证到位与直接倒桶门槛；夹具不启动完整 Baritone 客户端。
            ActorControlTestHarness.field(FluidPlacementTask.class, "stance").set(running, f.player.blockPosition());
            check(running.tick(f.player) == TaskState.RUNNING && f.itemUses() == 0
                    && Boolean.TRUE.equals(running.progress().get("body_in_possible_lava_flow")),
                    "a visible bucket ray must not bypass the new lava body safety gate");
            f.position(new Vec3(3.5, 2, 2.5)); f.nextTick();
            check(!LavaPlacementSafety.mayReachBody(f.level, AT, f.player.getBoundingBox()),
                    "standing on the surrounding rim stays above the predicted flow");
            submit(f, running); f.level.acknowledgedSequence = f.level.blockSequence; f.nextTick();
            check(running.tick(f.player) == TaskState.SUCCESS && f.itemUses() == 1,
                    "the same task pours once from a safe rim and still requires native confirmation");
            running.result(TaskState.SUCCESS);
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
        return new FluidPlacementTaskRecord("fluid-test",1000,AT,Blocks.WATER.defaultBlockState(),installation);
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
