// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
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
        /** 注液器：往正下方第二格（置物台、传送带）上的东西里注流体。 */
        SPOUT,
        /** 分液池：东西扔上去或送进去，倒出里面的流体；空手右键取回。 */
        DRAIN,
        /** 机械臂：转起来在选好的取放点之间搬东西；放下之前选点，选点与放下是一个安装段。 */
        ARM,
        /** 机械合成器：几台连成一片，每台放一格料，凑成配方形状一起做。 */
        CRAFTER,
        /** 装置：转起来或通电时把一片方块推拉、转动、吊起，停下落回方块。 */
        MOTION,
        /** 铁路：轨道、车站、信号、转向架与操纵台。 */
        RAIL,
        /** 红石与显示：无线红石、脉冲、锁存、显示链接与显示面。 */
        SIGNAL,
        /** 蓝图：蓝图桌写蓝图、蓝图加农炮按蓝图建。 */
        SCHEMATIC,
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
        List<Kind> all = new ArrayList<>();
        all.addAll(powerSources());
        all.addAll(transmission());
        all.addAll(processing());
        all.addAll(storage());
        all.addAll(logistics());
        all.addAll(motion());
        all.addAll(railway());
        all.addAll(signals());
        all.addAll(schematics());
        all.addAll(vehicleParts());
        return List.copyOf(all);
    }

    // 载具部件（Simulated、Create Aeronautics、Offroad 一套，都是机械动力的附属模组）：它们的设置用的就是机械动力的设置框，
    // 读法、改法和机械动力自己的方块一样，所以归在这里；没装那一套时这些方块不出现，这几行认领不到东西。
    // 无线红石打字机不在这里：它的设置是绑键，归 Simulated 的联动。
    private static List<Kind> vehicleParts() {
        return List.of(
                foreign("simulated:steering_wheel", "舵轮", MachineRole.CONTROL, Work.TURNING),
                foreign("simulated:throttle_lever", "油门拉杆", MachineRole.CONTROL, Work.STILL),
                foreign("simulated:torsion_spring", "扭力弹簧", MachineRole.CONTROL, Work.TURNING),
                foreign("simulated:directional_gearshift", "定向齿轮换向器", MachineRole.CONTROL, Work.TURNING),
                foreign("simulated:analog_transmission", "模拟传动器", MachineRole.CONTROL, Work.TURNING),
                foreign("simulated:swivel_bearing", "旋转轴承", MachineRole.MOTION, Work.TURNING),
                foreign("simulated:physics_assembler", "物理组装器", MachineRole.CONTROL, Work.STILL),
                foreign("simulated:directional_linked_receiver", "定向无线红石接收器", MachineRole.CONTROL, Work.STILL),
                foreign("simulated:modulating_linked_receiver", "调制无线红石接收器", MachineRole.CONTROL, Work.STILL),
                foreign("simulated:altitude_sensor", "高度传感器", MachineRole.CONTROL, Work.STILL),
                foreign("simulated:velocity_sensor", "速度传感器", MachineRole.CONTROL, Work.STILL),
                foreign("simulated:gimbal_sensor", "姿态传感器", MachineRole.CONTROL, Work.STILL),
                foreign("aeronautics:propeller_bearing", "螺旋桨轴承", MachineRole.MOTION, Work.TURNING),
                foreign("aeronautics:gyroscopic_propeller_bearing", "陀螺螺旋桨轴承", MachineRole.MOTION, Work.TURNING),
                foreign("aeronautics:adjustable_burner", "可调燃烧器", MachineRole.CONTROL, Work.STILL),
                foreign("aeronautics:steam_vent", "蒸汽喷口", MachineRole.CONTROL, Work.STILL),
                foreign("offroad:wheel_mount", "车轮悬架", MachineRole.MOTION, Work.TURNING));
    }

    // 别的模组的方块：编号就是它自己的注册 ID，不加 create: 前缀。
    private static Kind foreign(String id, String name, MachineRole role, Work work) {
        return new Kind(id, name, role, work, List.of(id));
    }

    // 动力源：自己转，把转动交给接上的传动件。
    private static List<Kind> powerSources() {
        return List.of(
                kind("water_wheel", "水车", MachineRole.POWER_SOURCE, Work.TURNING),
                kind("large_water_wheel", "大水车", MachineRole.POWER_SOURCE, Work.TURNING,
                        "large_water_wheel", "water_wheel_structure"),
                kind("windmill_bearing", "风车轴承", MachineRole.POWER_SOURCE, Work.TURNING),
                kind("steam_engine", "蒸汽机", MachineRole.POWER_SOURCE, Work.TURNING, "steam_engine", "powered_shaft"),
                kind("hand_crank", "手摇曲柄", MachineRole.POWER_SOURCE, Work.TURNING, valveHandles()),
                kind("creative_motor", "创造马达", MachineRole.POWER_SOURCE, Work.TURNING));
    }

    // 传动：把转动传过去，有的换向、变速、断开。
    private static List<Kind> transmission() {
        return List.of(
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
                kind("flywheel", "飞轮", MachineRole.TRANSMISSION, Work.TURNING),
                kind("cuckoo_clock", "布谷鸟钟", MachineRole.TRANSMISSION, Work.TURNING,
                        "cuckoo_clock", "mysterious_cuckoo_clock"),
                kind("metal_girder_encased_shaft", "金属梁传动杆", MachineRole.TRANSMISSION, Work.TURNING),
                kind("gantry_shaft", "龙门架传动杆", MachineRole.TRANSMISSION, Work.TURNING));
    }

    // 加工：吃转动干活，加工处在固定的方位。
    private static List<Kind> processing() {
        return List.of(
                kind("mechanical_press", "动力压机", MachineRole.PROCESSING, Work.PRESS),
                kind("mechanical_mixer", "动力搅拌器", MachineRole.PROCESSING, Work.MIXER),
                kind("deployer", "机械手", MachineRole.PROCESSING, Work.DEPLOYER),
                kind("encased_fan", "鼓风机", MachineRole.PROCESSING, Work.FAN),
                kind("mechanical_saw", "动力锯", MachineRole.PROCESSING, Work.SAW),
                kind("millstone", "石磨", MachineRole.PROCESSING, Work.GRINDER),
                kind("crushing_wheel", "粉碎轮", MachineRole.PROCESSING, Work.GRINDER,
                        "crushing_wheel", "crushing_wheel_controller"),
                kind("mechanical_crafter", "动力合成器", MachineRole.PROCESSING, Work.CRAFTER),
                kind("mechanical_drill", "动力钻头", MachineRole.PROCESSING, Work.TURNING),
                kind("spout", "注液器", MachineRole.PROCESSING, Work.SPOUT),
                kind("item_drain", "分液池", MachineRole.PROCESSING, Work.DRAIN),
                kind("turntable", "转盘", MachineRole.PROCESSING, Work.TURNING),
                kind("nozzle", "鼓风机喷嘴", MachineRole.PROCESSING, Work.STILL),
                kind("blaze_burner", "烈焰人燃烧室", MachineRole.CONTROL, Work.STILL, "blaze_burner", "lit_blaze_burner"));
    }

    // 存放：东西放在上面或里面，看得见。
    private static List<Kind> storage() {
        return List.of(
                kind("depot", "置物台", MachineRole.STORAGE, Work.DEPOT),
                kind("weighted_ejector", "弹射置物台", MachineRole.LOGISTICS, Work.DEPOT),
                kind("basin", "工作盆", MachineRole.STORAGE, Work.BASIN),
                kind("item_vault", "物品保险库", MachineRole.STORAGE, Work.STILL),
                kind("item_hatch", "物品舱口", MachineRole.LOGISTICS, Work.STILL),
                kind("fluid_tank", "流体储罐", MachineRole.STORAGE, Work.STILL),
                kind("creative_fluid_tank", "创造流体储罐", MachineRole.STORAGE, Work.STILL),
                kind("creative_crate", "创造板条箱", MachineRole.STORAGE, Work.STILL),
                kind("toolbox", "工具箱", MachineRole.STORAGE, Work.STILL, dyed("toolbox")));
    }

    // 搬运：把东西或流体送到别处。
    private static List<Kind> logistics() {
        return List.of(
                kind("belt", "传送带", MachineRole.LOGISTICS, Work.BELT),
                kind("chain_conveyor", "链条输送机", MachineRole.LOGISTICS, Work.CHAIN_CONVEYOR),
                kind("andesite_funnel", "安山漏斗", MachineRole.LOGISTICS, Work.STILL,
                        "andesite_funnel", "andesite_belt_funnel"),
                kind("brass_funnel", "黄铜漏斗", MachineRole.LOGISTICS, Work.STILL, "brass_funnel", "brass_belt_funnel"),
                kind("andesite_tunnel", "安山隧道", MachineRole.LOGISTICS, Work.STILL),
                kind("brass_tunnel", "黄铜隧道", MachineRole.LOGISTICS, Work.STILL),
                kind("chute", "溜槽", MachineRole.LOGISTICS, Work.STILL),
                kind("smart_chute", "智能溜槽", MachineRole.LOGISTICS, Work.STILL),
                kind("mechanical_arm", "动力臂", MachineRole.LOGISTICS, Work.ARM),
                kind("mechanical_pump", "动力泵", MachineRole.LOGISTICS, Work.TURNING),
                kind("hose_pulley", "软管滑轮", MachineRole.LOGISTICS, Work.TURNING),
                kind("portable_fluid_interface", "移动式流体接口", MachineRole.LOGISTICS, Work.STILL),
                kind("portable_storage_interface", "移动式存储接口", MachineRole.LOGISTICS, Work.STILL),
                kind("content_observer", "智能侦测器", MachineRole.CONTROL, Work.STILL),
                kind("stockpile_switch", "阈值开关", MachineRole.CONTROL, Work.STILL),
                kind("steam_whistle", "汽笛", MachineRole.CONTROL, Work.STILL, "steam_whistle", "steam_whistle_extension"),
                kind("fluid_pipe", "流体管道", MachineRole.CABLE, Work.STILL,
                        "fluid_pipe", "encased_fluid_pipe", "glass_fluid_pipe", "smart_fluid_pipe", "fluid_valve"),
                kind("stock_ticker", "库存请求器", MachineRole.LOGISTICS, Work.STOCK_TICKER),
                kind("stock_link", "库存链接器", MachineRole.LOGISTICS, Work.STILL),
                kind("packager", "打包机", MachineRole.LOGISTICS, Work.STILL, "packager", "repackager"),
                kind("package_frogport", "包裹青蛙港", MachineRole.LOGISTICS, Work.STILL),
                kind("postbox", "邮筒", MachineRole.LOGISTICS, Work.STILL, dyed("postbox")),
                kind("redstone_requester", "红石请求器", MachineRole.LOGISTICS, Work.STILL),
                kind("factory_gauge", "工厂仪表", MachineRole.CONTROL, Work.STILL),
                kind("table_cloth", "桌布", MachineRole.STORAGE, Work.STILL, tableCloths()));
    }

    // 装置：轴转起来或通电时推拉、转动、吊起一片方块；部件随装置一起动。
    private static List<Kind> motion() {
        return List.of(
                kind("mechanical_piston", "机械活塞", MachineRole.MOTION, Work.MOTION,
                        "mechanical_piston", "sticky_mechanical_piston", "mechanical_piston_head", "piston_extension_pole"),
                kind("gantry_carriage", "龙门架", MachineRole.MOTION, Work.MOTION),
                kind("mechanical_bearing", "机械轴承", MachineRole.MOTION, Work.MOTION),
                kind("clockwork_bearing", "时钟轴承", MachineRole.MOTION, Work.MOTION),
                kind("rope_pulley", "绳索滑轮", MachineRole.MOTION, Work.MOTION, "rope_pulley", "rope", "pulley_magnet"),
                kind("elevator_pulley", "电梯滑轮", MachineRole.MOTION, Work.MOTION),
                kind("elevator_contact", "电梯触点", MachineRole.CONTROL, Work.SIGNAL),
                kind("cart_assembler", "矿车组装器", MachineRole.MOTION, Work.MOTION),
                kind("controller_rail", "控制铁轨", MachineRole.CONTROL, Work.STILL),
                kind("minecart_anchor", "矿车锚", MachineRole.MOTION, Work.STILL),
                kind("linear_chassis", "线性机壳", MachineRole.MOTION, Work.STILL, "linear_chassis",
                        "secondary_linear_chassis"),
                kind("radial_chassis", "径向机壳", MachineRole.MOTION, Work.STILL),
                kind("sticker", "黏着器", MachineRole.MOTION, Work.STILL),
                kind("contraption_controls", "装置控制器", MachineRole.CONTROL, Work.STILL),
                kind("redstone_contact", "红石触点", MachineRole.CONTROL, Work.SIGNAL),
                kind("mechanical_harvester", "动力收割机", MachineRole.MOTION, Work.STILL),
                kind("mechanical_plough", "动力犁", MachineRole.MOTION, Work.STILL),
                kind("mechanical_roller", "动力辊", MachineRole.MOTION, Work.STILL),
                kind("sail", "帆", MachineRole.MOTION, Work.STILL, sails()));
    }

    // 铁路：轨道与车站、信号；转向架、操纵台装在列车上。
    private static List<Kind> railway() {
        return List.of(
                kind("track", "轨道", MachineRole.LOGISTICS, Work.RAIL),
                kind("track_station", "列车站", MachineRole.LOGISTICS, Work.RAIL),
                kind("track_signal", "列车信号机", MachineRole.CONTROL, Work.RAIL),
                kind("track_observer", "轨道侦测器", MachineRole.CONTROL, Work.RAIL),
                kind("small_bogey", "小型转向架", MachineRole.MOTION, Work.RAIL, "small_bogey", "large_bogey"),
                kind("controls", "列车操纵台", MachineRole.CONTROL, Work.RAIL),
                kind("train_door", "列车门", MachineRole.CONTROL, Work.STILL, "train_door", "train_trapdoor"),
                kind("seat", "座椅", MachineRole.CONTROL, Work.STILL, dyed("seat")));
    }

    // 红石与显示：无线红石、脉冲、锁存、显示链接与显示面。
    private static List<Kind> signals() {
        return List.of(
                kind("redstone_link", "无线红石信号终端", MachineRole.CONTROL, Work.SIGNAL),
                kind("analog_lever", "模拟拉杆", MachineRole.CONTROL, Work.SIGNAL),
                kind("pulse_repeater", "脉冲中继器", MachineRole.CONTROL, Work.SIGNAL),
                kind("pulse_extender", "脉冲延长器", MachineRole.CONTROL, Work.SIGNAL),
                kind("pulse_timer", "脉冲计时器", MachineRole.CONTROL, Work.SIGNAL),
                kind("powered_latch", "动力锁存器", MachineRole.CONTROL, Work.SIGNAL),
                kind("powered_toggle_latch", "切换锁存器", MachineRole.CONTROL, Work.SIGNAL),
                kind("display_link", "显示链接器", MachineRole.CONTROL, Work.SIGNAL),
                kind("display_board", "翻牌显示板", MachineRole.CONTROL, Work.SIGNAL),
                kind("nixie_tube", "辉光管", MachineRole.CONTROL, Work.SIGNAL, nixieTubes()),
                kind("placard", "告示框", MachineRole.CONTROL, Work.SIGNAL),
                kind("rose_quartz_lamp", "玫瑰石英灯", MachineRole.CONTROL, Work.SIGNAL),
                kind("desk_bell", "铃铛", MachineRole.CONTROL, Work.SIGNAL, "desk_bell", "peculiar_bell", "haunted_bell"));
    }

    // 蓝图：蓝图桌把蓝图文件写进空白蓝图，蓝图加农炮按蓝图放方块。
    private static List<Kind> schematics() {
        return List.of(
                kind("schematicannon", "蓝图加农炮", MachineRole.PROCESSING, Work.SCHEMATIC),
                kind("schematic_table", "蓝图桌", MachineRole.PROCESSING, Work.SCHEMATIC));
    }

    // 主方块就是唯一的方块时只写短名；带上 create: 前缀拼成注册 ID。
    private static Kind kind(String id, String name, MachineRole role, Work work, String... blocks) {
        List<String> ids = (blocks.length == 0 ? List.of(id) : List.of(blocks)).stream()
                .map(block -> MOD_ID + ":" + block).toList();
        return new Kind(MOD_ID + ":" + id, name, role, work, ids);
    }

    /** 染料的十六种颜色，按 Create 染色方块族的顺序。 */
    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    // 十六色一族的方块（邮筒、座椅、工具箱）：颜色_名字。
    private static String[] dyed(String name) {
        return COLORS.stream().map(color -> color + "_" + name).toArray(String[]::new);
    }

    // 辉光管：橙色那一种叫 nixie_tube，别的颜色是 颜色_nixie_tube。
    private static String[] nixieTubes() {
        List<String> ids = new ArrayList<>(List.of("nixie_tube"));
        COLORS.stream().filter(color -> !color.equals("orange")).forEach(color -> ids.add(color + "_nixie_tube"));
        return ids.toArray(String[]::new);
    }

    // 桌布：三种材质的加上十六色的。
    private static String[] tableCloths() {
        List<String> ids = new ArrayList<>(List.of("andesite_table_cloth", "brass_table_cloth", "copper_table_cloth"));
        COLORS.forEach(color -> ids.add(color + "_table_cloth"));
        return ids.toArray(String[]::new);
    }

    // 帆：帆架、白帆与十六色的帆（白帆就是白色那一种）。
    private static String[] sails() {
        List<String> ids = new ArrayList<>(List.of("sail_frame", "white_sail"));
        COLORS.stream().filter(color -> !color.equals("white")).forEach(color -> ids.add(color + "_sail"));
        return ids.toArray(String[]::new);
    }

    // 手摇曲柄与铜阀门手轮（含染色的十六种）一样是手摇着转的动力源。
    private static String[] valveHandles() {
        List<String> ids = new ArrayList<>(List.of("hand_crank", "copper_valve_handle"));
        COLORS.forEach(color -> ids.add(color + "_valve_handle"));
        return ids.toArray(String[]::new);
    }
}
