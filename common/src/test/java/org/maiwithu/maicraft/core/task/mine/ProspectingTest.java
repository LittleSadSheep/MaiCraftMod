// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 授权探矿：生成带小表只认公开生成层数据（表外如实拒绝）、探矿决策按授权与维度分步、
 * 掘进驱动在空候选时继续挖主矿道与分支，预算耗尽后诚实收手；途中暴露的目标仍走
 * 既有公平闸门与挖掘循环。下降 + 全链采集进包留给实机验收，不在夹具里硬凑。
 */
public final class ProspectingTest {
    private static final ResourceLocation DIAMOND = ResourceLocation.parse("minecraft:diamond");
    private static final ResourceLocation IRON_INGOT = ResourceLocation.parse("minecraft:iron_ingot");
    private static final ResourceLocation RAW_IRON = ResourceLocation.parse("minecraft:raw_iron");
    private static final ResourceLocation COAL = ResourceLocation.parse("minecraft:coal");
    private static final ResourceLocation DEBRIS = ResourceLocation.parse("minecraft:ancient_debris");
    private static final ResourceLocation STICK = ResourceLocation.parse("minecraft:stick");
    // 调用方的自然表达是矿方块："我要挖铁矿" = minecraft:iron_ore，产物 ID 是反面。
    private static final ResourceLocation IRON_ORE = ResourceLocation.parse("minecraft:iron_ore");
    private static final ResourceLocation COAL_ORE = ResourceLocation.parse("minecraft:coal_ore");
    private static final ResourceLocation DEEPSLATE_DIAMOND_ORE =
            ResourceLocation.parse("minecraft:deepslate_diamond_ore");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation DIRT = ResourceLocation.parse("minecraft:dirt");
    /** 类初始化先于 Bootstrap，目标方块族在 main 引导后再解析，不能放进静态字段。 */
    private static Set<net.minecraft.world.level.block.Block> diamondOres() {
        return Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 无服务器数据包的夹具需要装入镐采集标签，与 ExactHarvestTest 同款绕开工具层级判定。
        var tags = new java.util.HashMap<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>,
                java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var previous = Map.copyOf(tags);
        tags.put(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE,
                List.of(Blocks.STONE.builtInRegistryHolder(), Blocks.DIAMOND_ORE.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try { bandTable(); decision(); driver(); exposedTargetMined(); }
        finally { BuiltInRegistries.BLOCK.bindTags(previous); }
        System.out.println("ProspectingTest: passed");
    }


    // ---- 生成带小表：公开生成层数据，推荐探矿 Y 与维度如实；表外拒绝猜测深度 ----

    private static void bandTable() {
        check(OreGenerationBand.forItem(DIAMOND).prospectY() == -59, "钻石推荐探矿层为 -59");
        check(OreGenerationBand.forItem(IRON_INGOT).prospectY() == 16, "铁（锭变体同带）推荐探矿层为 16");
        check(OreGenerationBand.forItem(RAW_IRON).prospectY() == 16, "粗铁与铁锭共用同一条生成带");
        check(OreGenerationBand.forItem(COAL).prospectY() == 96, "煤推荐探矿层为 96");
        check(OreGenerationBand.forItem(DIAMOND).matchesDimension(OreGenerationBand.OVERWORLD),
                "钻石生成带在主世界");
        check(OreGenerationBand.forItem(DEBRIS).matchesDimension(OreGenerationBand.NETHER),
                "远古残骸生成带在下界，主世界不得下降找它");
        var blocks = OreGenerationBand.forItem(DIAMOND).targetBlocks();
        check(blocks.contains(Blocks.DIAMOND_ORE) && blocks.contains(Blocks.DEEPSLATE_DIAMOND_ORE),
                "生成带方块族同时覆盖普通矿与深层矿变体");
        check(OreGenerationBand.forItem(STICK) == null, "表外物品没有生成带，探矿必须拒绝");
        check(OreGenerationBand.forItem(DIAMOND).minY() == -64
                        && OreGenerationBand.forItem(DIAMOND).maxY() == 16,
                "钻石生成带边界取公开数据 [-64, 16]");
        // 矿方块物品按目标方块族反查到产物带：iron_ore、coal_ore 与深层变体都与产物 ID 同带。
        check(OreGenerationBand.forItem(IRON_ORE).prospectY() == 16
                        && OreGenerationBand.forItem(IRON_ORE).equals(OreGenerationBand.forItem(RAW_IRON)),
                "iron_ore 反查到 raw_iron 同一条生成带");
        check(OreGenerationBand.forItem(COAL_ORE).prospectY() == 96,
                "coal_ore 反查到煤带");
        check(OreGenerationBand.forItem(DEEPSLATE_DIAMOND_ORE).prospectY() == -59,
                "深层钻石矿变体反查到钻石带");
        check(OreGenerationBand.forItem(DEBRIS).prospectY() == 15,
                "ancient_debris 产物与方块同名，仍按下界推荐层 15");
        check(OreGenerationBand.forItem(STONE) == null && OreGenerationBand.forItem(DIRT) == null,
                "stone/dirt 等普通方块不在任何方块族，反查仍拒绝");
    }

    // ---- 探矿决策：授权、表、维度与阶段共同决定下一步 ----

    private static void decision() {
        var overworld = OreGenerationBand.OVERWORLD;
        check(OreGenerationBand.plan(false, List.of(DIAMOND), overworld, false, false).step()
                        == OreGenerationBand.Step.UNAUTHORIZED,
                "授权关：维持公平空手行为，不派下降");
        check(OreGenerationBand.plan(true, List.of(STICK), overworld, false, false).step()
                        == OreGenerationBand.Step.UNKNOWN_BAND,
                "表外物品：拒绝探矿，不猜下降深度");
        check(OreGenerationBand.plan(true, List.of(DIAMOND), OreGenerationBand.NETHER, false, false).step()
                        == OreGenerationBand.Step.OTHER_DIMENSION,
                "生成带在另一维度：不在当前维度下降");
        var descend = OreGenerationBand.plan(true, List.of(DIAMOND), overworld, false, false);
        check(descend.step() == OreGenerationBand.Step.DESCEND && descend.band().prospectY() == -59,
                "授权开 + 表内物品 + 维度相符 → 下降，目标 Y = 推荐探矿值");
        check(OreGenerationBand.plan(true, List.of(DIAMOND), overworld, true, false).step()
                        == OreGenerationBand.Step.ALREADY_PROSPECTED,
                "下降已派出：不再重复决策");
        // 矿方块 ID 走同一条决策链：iron_ore 授权探矿派下降，与 raw_iron 落在同一条带。
        var ironOreDescend = OreGenerationBand.plan(true, List.of(IRON_ORE), overworld, false, false);
        var rawIronDescend = OreGenerationBand.plan(true, List.of(RAW_IRON), overworld, false, false);
        check(ironOreDescend.step() == OreGenerationBand.Step.DESCEND
                        && rawIronDescend.step() == OreGenerationBand.Step.DESCEND
                        && ironOreDescend.band().equals(rawIronDescend.band()),
                "矿方块 ID 与产物 ID 授权探矿同带下降，prospectY = "
                        + ironOreDescend.band().prospectY());
    }

    // ---- 掘进驱动：空候选继续掘进而非立即终局；预算耗尽诚实收手 ----

    private static void driver() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            initEffects(h);
            initNavSettingsRoot();
            h.position(Vec3.atBottomCenterOf(new BlockPos(8, 1, 8)));
            BlockPos feet = PlayerNav.playerFeet(h.player);
            int prospectY = feet.getY() - 2;   // 夹具地面之下，掘进会真实挖进石头
            // 目标必须是夹具里不存在的矿物：地层全是石头，目标含石头会让任务原地正常开采而非探矿。
            var record = new MineBlockTaskRecord("prospect-drive", 100000,
                    diamondOres(), 1, "diamond_ore").withProspecting(prospectY);
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 400 && state == TaskState.RUNNING; i++) {
                h.nextTick();
                state = task.tick(h.player);
            }
            check(state != TaskState.FAILED || resultMentionsProspect(task),
                    "探矿授权下空候选不得走「附近没有」的即时终局：" + state);
            check(field(task, "tunnelDriver") != null, "探矿驱动已建立按通行断面推进的掘进任务");
            int dug = intField(task, "excavatedBlocks");
            // 预算烧完：下一刻必须诚实收手，回执只陈述真实掘进量，不带透视暗示。
            setIntField(task, "prospectTicks", intConstant("PROSPECT_MAX_TICKS"));
            // 预算停止前先结束夹具里尚未提交的瞄准准备；真实已提交动作必须等原生回执结清。
            ((ProspectTunnelDriver) field(task, "tunnelDriver")).close();
            h.nextTick();
            state = task.tick(h.player);
            check(state == TaskState.FAILED && resultMentionsProspect(task),
                    "掘进预算耗尽后诚实失败并叙述探矿过程");
            check(intField(task, "excavatedBlocks") >= dug, "开路块数独立记账且单调增加");
        }
    }

    /**
     * 暴露在眼前的目标仍走既有闸门与挖掘循环：即时可见即入可挖名单并成为当前挖掘目标，
     * 探矿驱动不得无视它。方块破坏的完整结算（掉落、拾取、进包）依赖真实服务器往返，
     * 留给实机验收，不在夹具里硬凑。
     */
    private static void exposedTargetMined() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            initEffects(h);
            initNavSettingsRoot();
            BlockPos feet = new BlockPos(5, 1, 5);
            BlockPos ore = feet.east();
            h.position(Vec3.atBottomCenterOf(feet));
            h.set(ore, Blocks.STONE.defaultBlockState());
            var record = new MineBlockTaskRecord("prospect-exposed", 100000,
                    Set.of(Blocks.STONE), 1, "stone").withProspecting(0);
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 120 && state == TaskState.RUNNING; i++) {
                h.nextTick();
                state = task.tick(h.player);
            }
            check(((java.util.List<?>) field(task, "knownOres")).contains(ore),
                    "暴露的目标经公平闸门进入可挖名单");
            check(ore.equals(field(task, "activeTarget")),
                    "暴露的目标被既有挖矿循环接手为当前目标，实际: " + field(task, "activeTarget"));
        }
    }

    /** 未走实体构造器的夹具需要显式初始化空药效表（FairMineGateTest 同款处理）。 */
    private static void initEffects(InteractionWorldTestHarness h) throws Exception {
        var effects = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("activeEffects");
        effects.setAccessible(true);
        effects.set(h.player, new java.util.HashMap<>());
    }

    /** 掘进走真实导航后端（Baritone），夹具要给它一个可读设置的根目录（AssistedFallOwnershipTest 同款）。 */
    private static void initNavSettingsRoot() throws Exception {
        var mc = net.minecraft.client.Minecraft.getInstance();
        for (Class<?> owner = net.minecraft.client.Minecraft.class; owner != null; owner = owner.getSuperclass()) {
            try {
                var dir = owner.getDeclaredField("gameDirectory");
                dir.setAccessible(true);
                dir.set(mc, new java.io.File("prospecting-settings-fixture"));
                return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException("gameDirectory");
    }

    private static boolean resultMentionsProspect(MineCompanionTask task) {
        String message = task.result(TaskState.FAILED).message();
        return message != null && message.contains("prospect");
    }

    private static Object field(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance);
    }

    private static int intField(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(instance);
    }

    private static void setIntField(Object instance, String name, int value) throws Exception {
        var f = instance.getClass().getDeclaredField(name); f.setAccessible(true); f.setInt(instance, value);
    }

    private static int intConstant(String name) throws Exception {
        var f = MineCompanionTask.class.getDeclaredField(name); f.setAccessible(true); return f.getInt(null);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
