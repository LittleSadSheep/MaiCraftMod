// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 机械动力机器的资料：每种认得的机器一篇——在机器里干什么、加工处在哪、从哪面接转动、要多快才干活、
 * 每转吃多少应力或给多少容量、用机器做东西时料放哪产物从哪收，传送带与链条怎么装。
 * 物品资料页经"相关资料"挂上它，LLM 不用读能力说明也查得到"压机下面第二格要放置物台"。
 *
 * <p>另有一篇总览：认得哪些机器，以及接动力的几条经验（先接现场已有的应力网，远处用链条输送机、
 * 近处用传动杆与齿轮箱，思索里的创造马达只是"这里接动力"的示意）。数值读 Create 自己的应力表与服务器配置，
 * 读不到（没进世界）就写"进世界后读得到"，不编。
 */
final class CreateMachineKnowledge implements KnowledgeSource {

    static final String PREFIX = "maicraft://knowledge/machine/create/";
    static final String INDEX = PREFIX + "index";

    private final CreateCompat compat;

    CreateMachineKnowledge(CreateCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "machine.create.index", "机械动力机器总览",
                "认得哪些 Create 机器、各自干什么，以及接动力的经验",
                "机械动力 create 机器 应力 动力 传动 压机 搅拌 置物台 传送带 链条"));
    }

    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        List<KnowledgeDocument.Entry> entries = new ArrayList<>(entries());
        for (CreateMachineCatalog.Kind kind : CreateMachineCatalog.kinds()) entries.add(entry(kind));
        return List.copyOf(entries);
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        return CreateMachineCatalog.kinds().stream().filter(kind -> kind.blocks().contains(registryId))
                .map(CreateMachineKnowledge::entry).toList();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (uri.equals(INDEX)) return index();
        if (!uri.startsWith(PREFIX)) return null;
        String id = CreateMachineCatalog.MOD_ID + ":" + uri.substring(PREFIX.length());
        return CreateMachineCatalog.kinds().stream().filter(kind -> kind.id().equals(id)).findFirst()
                .map(this::page).orElse(null);
    }

    @Override public String status() {
        return "机械动力机器资料：可用，" + CreateMachineCatalog.kinds().size() + " 种机器";
    }

    static String uri(CreateMachineCatalog.Kind kind) {
        return PREFIX + kind.id().substring(kind.id().indexOf(':') + 1);
    }

    private static KnowledgeDocument.Entry entry(CreateMachineCatalog.Kind kind) {
        return new KnowledgeDocument.Entry(uri(kind), "machine.create.kind", kind.name() + "（" + kind.id() + "）",
                "在机器里：" + roleName(kind) + "；加工处、接转动的面、转速门槛、应力", kind.name() + " " + kind.id(),
                "text/markdown", kind.id(), kind.name());
    }

    private KnowledgeDocument index() {
        StringBuilder text = new StringBuilder("# 机械动力机器总览\n\n");
        text.append("机器能力（machine_inspect / machine_run / machine_build / machine_connect）认得下面这些 Create 方块；")
                .append("附属模组的动力方块认作\"其他动力方块\"，只看得出转不转。每种的细节读它自己那一篇。\n\n");
        text.append("## 接动力的经验\n\n")
                .append("- 先接现场已有的应力网（主城的风车、蒸汽机）：machine_connect 不给 source 时会找最近在转的那张网。")
                .append("自建动力源只在机器离哪张网都远、又要长期运转时才考虑。\n")
                .append("- 思索（Ponder）演示里的创造马达、物品保险库是\"这里接动力、这里接料\"的示意，不是要照着放的方块。\n")
                .append("- 近处用传动杆、齿轮箱、齿轮；远处用链条输送机架过去（锁链便宜，适合长距离，")
                .append("一般用传动杆把它架高一点）；链式传动箱贵，不必用来跑长距离。\n")
                .append("- 每台机器里链条输送机的个数有上限（实例配置，默认 1）：近处能用传动杆接上的就别用链条。\n")
                .append("- 手摇曲柄只适合手动做几件东西（置物台上压几块板），长期生产线接真正的动力源。\n")
                .append("- 过载时整张网停转：看 machine_inspect 里那张网的 stress 与 stress_capacity。\n\n");
        text.append("## 设置框（过滤格、件数、数值、选项）\n\n")
                .append("- 方块上画着小框的都能用 machine_configure 改；machine_inspect 或 machine_configure 先看这台有哪些键。\n")
                .append("- 过滤格 `filter`（黄铜隧道每面一格写 `filter.north` 这样）：写物品 ID，角色手持那样东西点那一格；")
                .append("写 empty 空着手点，清空。漏斗、智能溜槽这类还有 `filter.amount`：any、<=N 最多 N 件、=N 正好 N 件。\n")
                .append("- 数值 `value`（转速控制器的转速带正负，脉冲计时器这类按刻写）、选项 `option`（写名字）：")
                .append("角色走近后照 Create 设置界面那样改，读回对上为止。同一种有好几个时第二个起是 `value.2` 这样。\n\n");
        text.append("## 看得见的读数\n\n")
                .append("- machine_inspect 的读数里：`fluids` 储罐、注液器、分液池、工作盆装着什么流体多少毫桶；")
                .append("`text` 辉光管、翻牌显示板显示的字，告示框里放的东西；`moving` 轴承、活塞、滑轮在不在动；")
                .append("无线红石信号终端的 `frequency`（两个频率格）、`link_mode`、`powered`。\n")
                .append("- 无线红石：`link.frequency.1`、`link.frequency.2` 写物品 ID（手持它点那一格），两格都一样的终端互相连着；")
                .append("`link.mode` 写 receive 或 transmit（空着手潜行右键它换）。\n\n");
        text.append("## 认得的机器\n\n");
        for (CreateMachineCatalog.Kind kind : CreateMachineCatalog.kinds()) {
            text.append("- ").append(kind.name()).append("（`").append(kind.id()).append("`）：")
                    .append(roleName(kind)).append("，资料 `").append(lookupId(kind)).append("`\n");
        }
        return new KnowledgeDocument(INDEX, "machine.create.index", "机械动力机器总览", "认得的 Create 机器与接动力经验",
                text.toString());
    }

    private KnowledgeDocument page(CreateMachineCatalog.Kind kind) {
        StringBuilder text = new StringBuilder("# ").append(kind.name()).append("（`").append(kind.id()).append("`）\n\n");
        text.append("- 在机器里：").append(roleName(kind)).append("\n");
        if (kind.blocks().size() > 1) {
            text.append("- 包括这些方块：").append(String.join("、", kind.blocks())).append("\n");
        }
        BlockState shown = defaultState(kind).orElse(Blocks.AIR.defaultBlockState());
        for (ExchangePoint point : ProcessingSpots.of(kind.work(), shown, BlockPos.ZERO, Set.of(), false)) {
            text.append("- ").append(point.note()).append("\n");
        }
        defaultState(kind).ifPresent(state -> appendKinetics(text, state));
        appendUse(text, kind);
        return new KnowledgeDocument(uri(kind), "machine.create.kind", kind.name(), "Create 机器资料", text.toString());
    }

    // 转动与应力：从伸着轴的面接转动；门槛、每转应力与容量读 Create 自己的表，读不到就说进世界后再看。
    private void appendKinetics(StringBuilder text, BlockState state) {
        if (!safely(() -> compat.kinetic(state)).orElse(false)) return;
        text.append("- 转动：从伸着轴的那几面接（machine_inspect 的 ports 里按面列出）\n");
        Optional<Optional<Double>> minimum = safely(() -> compat.minimumSpeed(state));
        if (minimum.isEmpty()) {
            text.append("- 干活要的转速：进世界后读得到（来自服务器配置）\n");
        } else {
            text.append("- 干活要的转速：").append(minimum.get().map(speed -> "至少 " + CreateReadings.round(speed)
                    + " 转/分").orElse("没有门槛，转起来就干活")).append("\n");
        }
        Block block = state.getBlock();
        safely(() -> compat.stressImpact(block)).filter(impact -> impact > 0).ifPresent(impact -> text
                .append("- 应力：每转吃 ").append(CreateReadings.round(impact)).append("（转速 n 时吃 ")
                .append(CreateReadings.round(impact)).append("×n）\n"));
        safely(() -> compat.stressCapacity(block)).filter(capacity -> capacity > 0).ifPresent(capacity -> {
            text.append("- 应力容量：每转给 ").append(CreateReadings.round(capacity));
            safely(() -> compat.generatedSpeed(block)).flatMap(speed -> speed)
                    .ifPresent(speed -> text.append("，自己转 ").append(speed).append(" 转/分（最多）"));
            text.append("\n");
        });
    }

    // 用机器做东西与施工时要知道的：料放哪、产物从哪收，安装段怎么装。
    private static void appendUse(StringBuilder text, CreateMachineCatalog.Kind kind) {
        switch (kind.work()) {
            case PRESS, DEPLOYER, FAN -> text.append("- machine_run：target 指这台机器；加工处放着置物台时，")
                    .append("料放到那台置物台上，产物从置物台空手收回")
                    .append(switch (kind.work()) {
                        case PRESS -> "；加工处是工作盆时站到盆边把料扔进去（压块）\n";
                        case DEPLOYER -> "；要它手里拿着东西（涂抹、序列组装）用 machine_configure 的 deployer.held 写物品 ID，"
                                + "deployer.mode 写 use 或 punch（拿扳手点它前端换）；machine_inspect 读得出它手里拿着什么\n";
                        // 风会把扔进去的东西一路吹走，落点不在一格里：machine_run 不往风里扔，只用风里的置物台。
                        case FAN -> "；鼓风机的置物台放在风里、水岩浆或火的后面（沿风向往前六格以内），"
                                + "风里没有置物台时 machine_run 不投料\n";
                        default -> "\n";
                    });
            case MIXER -> text.append("- machine_run：料扔进正下方第二格的工作盆里（站到盆边扔），产物留在盆里，")
                    .append("空手右键工作盆取出\n");
            case BASIN -> text.append("- machine_run：站到盆边把料扔进去，落在盆沿上也收；空手右键取出盆里的东西\n");
            case SAW -> text.append("- machine_run：锯齿朝上时站到旁边把料扔到锯面上；锯没转时料停在上面，转起来再锯\n");
            case GRINDER -> text.append("- machine_run：石磨站到旁边把料扔到顶上；粉碎轮扔进两只轮子中间那一格，")
                    .append("那一格要朝下送料（两只轮子对着往里转）\n");
            case DEPOT -> text.append("- machine_run：手持料右键顶面放上去，手上先只留要投的件数（一次最多一叠）；")
                    .append("空手右键顶面收回\n");
            case BELT -> text.append("- machine_build：installations 写 `{kind: create:belt, cells: [从一端到另一端]}`；")
                    .append("cells 里两端写与带子垂直的 `create:shaft`（不能用机壳传动杆），中间不写或写 air；")
                    .append("沿东西或南北走，可以上下斜四十五度或竖直，两端距离小于服务器配置（默认 20）。")
                    .append("每段两端都要有和带子走向一致的轴；拐弯分成两段\n");
            case CHAIN_CONVEYOR -> text.append("- machine_build：installations 写 `{kind: create:chain_conveyor, cells: [台 A, 台 B]}`，")
                    .append("两台本身写进 cells；相距小于服务器配置（默认 32）、不小于两格半，不能竖直，")
                    .append("高差不超过水平距离；锁链约每 2.5 格一根\n");
            default -> {
            }
        }
    }

    private static Optional<BlockState> defaultState(CreateMachineCatalog.Kind kind) {
        ResourceLocation id = ResourceLocation.tryParse(kind.blocks().getFirst());
        return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id).map(Block::defaultBlockState);
    }

    // 资料在 MCP 线程上读：Create 的配置没加载、模组接口对不上都按读不到收场，不让查资料报错。
    private static <T> Optional<T> safely(Supplier<T> read) {
        try {
            return Optional.ofNullable(read.get());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static String lookupId(CreateMachineCatalog.Kind kind) {
        return uri(kind).substring("maicraft://knowledge/".length());
    }

    private static String roleName(CreateMachineCatalog.Kind kind) {
        return switch (kind.role()) {
            case POWER_SOURCE -> "动力源";
            case TRANSMISSION -> "传动";
            case PROCESSING -> "加工";
            case LOGISTICS -> "搬运";
            case STORAGE -> "存放";
            case CONTROL -> "控制";
            case MOTION -> "装置";
            case CABLE -> "管线";
            case NETWORK_CORE -> "网络核心";
        } + "（" + kind.role().name().toLowerCase(Locale.ROOT) + "）";
    }
}
