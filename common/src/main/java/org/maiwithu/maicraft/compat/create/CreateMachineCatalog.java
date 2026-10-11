// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * 机械动力里认得的机器：每一种的编号、中文名、在机器里干什么、由哪几种方块组成、怎么干活。
 * 编号就是主方块的注册 ID，也是配方查看器里这道工序的工作站物品 ID（压机做的配方工作站就是 create:mechanical_press），
 * 用机器做东西时按它挑配方。
 *
 * <p>只列 Create 本体里认得的这些；附属模组的动力方块（会转、会传动力）不在表里，由"其他动力方块"兜住，
 * 至少看得出它转不转、在哪张应力网上。
 */
final class CreateMachineCatalog {

    /** 一种机器怎么干活：决定它的进出口、读数、投料取货与安装段。 */
    enum Work {
        /** 只转动、传动或发出转动：轴、齿轮、齿轮箱、水车、风车轴承…… */
        TURNING,
        /** 置物台：东西放在台面上，被上方的压机、机械手或前方的鼓风机加工。 */
        DEPOT,
        /** 工作盆：东西扔进盆里，被上方的搅拌器搅拌、压机压块；空手右键取出。 */
        BASIN,
        /** 压机：压它正下方第二格（置物台、传送带或工作盆）上的东西。 */
        PRESS,
        /** 搅拌器：搅它正下方第二格工作盆里的东西。 */
        MIXER,
        /** 机械手：朝前方第二格用手里的东西（下方是置物台或传送带时给上面的东西用）。 */
        DEPLOYER,
        /** 鼓风机：朝前方吹，前方第一格放水、岩浆或火做加工，物品在风里或风前的置物台上被加工。 */
        FAN,
        /** 锯：锯齿朝上时锯放在它顶上、或经传送带送进来的东西。 */
        SAW,
        /** 石磨、粉碎轮这类从顶上扔东西进去磨碎的。 */
        GRINDER,
        /** 传送带：用传送带物品连两根轴装成的一整条。 */
        BELT,
        /** 链条输送机：两台之间手持锁链连起来。 */
        CHAIN_CONVEYOR,
        /** 库存请求器：读物流网络的现货。 */
        STOCK_TICKER,
        /** 其余不转的方块：漏斗、溜槽、储罐、保险库、管道…… */
        STILL
    }

    /**
     * 一种机器。
     *
     * @param id     编号，主方块的注册 ID
     * @param name   给 LLM 看的中文名
     * @param role   在机器里干什么
     * @param work   怎么干活
     * @param blocks 由哪几种方块组成（注册 ID），第一个是主方块
     */
    record Kind(String id, String name, MachineRole role, Work work, List<String> blocks) {
        Kind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(work, "work");
            blocks = List.copyOf(blocks);
        }
    }

    /** 机械动力的模组 ID。 */
    static final String MOD_ID = "create";

    private CreateMachineCatalog() {
    }

    /** 认得的全部机器，表的顺序就是认领方块的顺序（同一种方块只出现在一行里）。 */
    static List<Kind> kinds() {
        return List.of(
                // 动力源：自己转，把转动交给接上的传动件。
                kind("water_wheel", "水车", MachineRole.POWER_SOURCE, Work.TURNING),
                kind("large_water_wheel", "大水车", MachineRole.POWER_SOURCE, Work.TURNING,
                        "large_water_wheel", "water_wheel_structure"),
                kind("windmill_bearing", "风车轴承", MachineRole.POWER_SOURCE, Work.TURNING),
                kind("steam_engine", "蒸汽机", MachineRole.POWER_SOURCE, Work.TURNING, "steam_engine", "powered_shaft"),
                kind("hand_crank", "手摇曲柄", MachineRole.POWER_SOURCE, Work.TURNING, valveHandles()),
                kind("creative_motor", "创造马达", MachineRole.POWER_SOURCE, Work.TURNING),
                // 传动：把转动传过去，有的换向、变速、断开。
                kind("shaft", "传动杆", MachineRole.TRANSMISSION, Work.TURNING),
                kind("cogwheel", "齿轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("large_cogwheel", "大齿轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("andesite_encased_shaft", "安山机壳传动杆", MachineRole.TRANSMISSION, Work.TURNING),
                kind("brass_encased_shaft", "黄铜机壳传动杆", MachineRole.TRANSMISSION, Work.TURNING),
                kind("andesite_encased_cogwheel", "安山机壳齿轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("brass_encased_cogwheel", "黄铜机壳齿轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("andesite_encased_large_cogwheel", "安山机壳大齿轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("brass_encased_large_cogwheel", "黄铜机壳大齿轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("gearbox", "齿轮箱", MachineRole.TRANSMISSION, Work.TURNING),
                kind("encased_chain_drive", "链式传动箱", MachineRole.TRANSMISSION, Work.TURNING),
                kind("adjustable_chain_gearshift", "可调节链式传动箱", MachineRole.TRANSMISSION, Work.TURNING),
                kind("gearshift", "反转齿轮箱", MachineRole.CONTROL, Work.TURNING),
                kind("clutch", "离合器", MachineRole.CONTROL, Work.TURNING),
                kind("sequenced_gearshift", "定序齿轮箱", MachineRole.CONTROL, Work.TURNING),
                kind("rotation_speed_controller", "转速控制器", MachineRole.CONTROL, Work.TURNING),
                kind("speedometer", "转速表", MachineRole.TRANSMISSION, Work.TURNING),
                kind("stressometer", "应力表", MachineRole.TRANSMISSION, Work.TURNING),
                kind("metal_girder_encased_shaft", "金属梁传动杆", MachineRole.TRANSMISSION, Work.TURNING),
                kind("gantry_shaft", "龙门架传动杆", MachineRole.TRANSMISSION, Work.TURNING),
                // 加工：吃转动干活，加工处在固定的方位。
                kind("mechanical_press", "动力压机", MachineRole.PROCESSING, Work.PRESS),
                kind("mechanical_mixer", "动力搅拌器", MachineRole.PROCESSING, Work.MIXER),
                kind("deployer", "机械手", MachineRole.PROCESSING, Work.DEPLOYER),
                kind("encased_fan", "鼓风机", MachineRole.PROCESSING, Work.FAN),
                kind("mechanical_saw", "动力锯", MachineRole.PROCESSING, Work.SAW),
                kind("millstone", "石磨", MachineRole.PROCESSING, Work.GRINDER),
                kind("crushing_wheel", "粉碎轮", MachineRole.PROCESSING, Work.GRINDER),
                kind("mechanical_crafter", "动力合成器", MachineRole.PROCESSING, Work.TURNING),
                kind("mechanical_drill", "动力钻头", MachineRole.PROCESSING, Work.TURNING),
                kind("spout", "注液器", MachineRole.PROCESSING, Work.STILL),
                kind("item_drain", "分液池", MachineRole.PROCESSING, Work.STILL),
                kind("blaze_burner", "烈焰人燃烧室", MachineRole.CONTROL, Work.STILL, "blaze_burner", "lit_blaze_burner"),
                // 存放：东西放在上面或里面，看得见。
                kind("depot", "置物台", MachineRole.STORAGE, Work.DEPOT),
                kind("weighted_ejector", "弹射置物台", MachineRole.LOGISTICS, Work.DEPOT),
                kind("basin", "工作盆", MachineRole.STORAGE, Work.BASIN),
                kind("item_vault", "物品保险库", MachineRole.STORAGE, Work.STILL),
                kind("fluid_tank", "流体储罐", MachineRole.STORAGE, Work.STILL),
                // 搬运：把东西或流体送到别处。
                kind("belt", "传送带", MachineRole.LOGISTICS, Work.BELT),
                kind("chain_conveyor", "链条输送机", MachineRole.LOGISTICS, Work.CHAIN_CONVEYOR),
                kind("andesite_funnel", "安山漏斗", MachineRole.LOGISTICS, Work.STILL,
                        "andesite_funnel", "andesite_belt_funnel"),
                kind("brass_funnel", "黄铜漏斗", MachineRole.LOGISTICS, Work.STILL, "brass_funnel", "brass_belt_funnel"),
                kind("andesite_tunnel", "安山隧道", MachineRole.LOGISTICS, Work.STILL),
                kind("brass_tunnel", "黄铜隧道", MachineRole.LOGISTICS, Work.STILL),
                kind("chute", "溜槽", MachineRole.LOGISTICS, Work.STILL),
                kind("smart_chute", "智能溜槽", MachineRole.LOGISTICS, Work.STILL),
                kind("mechanical_arm", "动力臂", MachineRole.LOGISTICS, Work.TURNING),
                kind("mechanical_pump", "动力泵", MachineRole.LOGISTICS, Work.TURNING),
                kind("fluid_pipe", "流体管道", MachineRole.CABLE, Work.STILL,
                        "fluid_pipe", "encased_fluid_pipe", "glass_fluid_pipe", "smart_fluid_pipe", "fluid_valve"),
                kind("stock_ticker", "库存请求器", MachineRole.LOGISTICS, Work.STOCK_TICKER),
                kind("stock_link", "库存链接器", MachineRole.LOGISTICS, Work.STILL),
                kind("packager", "打包机", MachineRole.LOGISTICS, Work.STILL, "packager", "repackager"));
    }

    // 主方块就是唯一的方块时只写短名；带上 create: 前缀拼成注册 ID。
    private static Kind kind(String id, String name, MachineRole role, Work work, String... blocks) {
        List<String> ids = (blocks.length == 0 ? List.of(id) : List.of(blocks)).stream()
                .map(block -> MOD_ID + ":" + block).toList();
        return new Kind(MOD_ID + ":" + id, name, role, work, ids);
    }

    // 手摇曲柄与铜阀门手轮（含染色的十六种）一样是手摇着转的动力源。
    private static String[] valveHandles() {
        List<String> colors = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
        String[] ids = new String[colors.size() + 2];
        ids[0] = "hand_crank";
        ids[1] = "copper_valve_handle";
        for (int i = 0; i < colors.size(); i++) {
            ids[i + 2] = colors.get(i) + "_valve_handle";
        }
        return ids;
    }
}
