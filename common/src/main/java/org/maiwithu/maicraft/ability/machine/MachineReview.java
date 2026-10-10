// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 机器蓝图审阅：把 LLM 自己看不出来的事算出来给它看，纯函数。
 *
 * <p>查这些：部件装不装得上它宿主的那一面、安装段的形状合不合规则、声明的工序这台机器
 * 做不做得了、动力消费者有没有通过传动连到动力源、整份蓝图要哪些材料身上缺多少。
 * 审阅不是开工的门：报了问题也照建，这里不下"通过 / 不通过"的结论。
 */
final class MachineReview {

    /** 一条审阅发现：在哪个位置、是什么问题、建议往哪看。 */
    record Issue(String where, String what, String suggestion) {
    }

    /** 一种材料：蓝图要几件、身上有几件、缺几件。 */
    record Material(String item, int needed, int carried, int missing) {
    }

    /** 一次审阅的报告：发现、材料账、动力与频道的估计。 */
    record Report(List<Issue> issues, List<Material> materials, String power, String channels) {

        Report {
            issues = List.copyOf(issues);
            materials = List.copyOf(materials);
        }
    }

    private static final String POWER_UNREADABLE = "估不出：消费与容量的应力数值要装了 Create 的联动才读得到";
    private static final String CHANNELS_UNREADABLE = "估不出：频道用量要 ME 网络的服务端读数，现在读不到";

    private MachineReview() {
    }

    /** 审一份机器蓝图：发现逐条列，材料一次给全。 */
    static Report review(MachineBlueprint blueprint, MachineServices services, BackpackView backpack) {
        List<Issue> issues = new ArrayList<>();
        reviewParts(blueprint, services, issues);
        reviewInstallations(blueprint, services, issues);
        reviewProcesses(blueprint, services, issues);
        reviewPower(blueprint, services, issues);
        return new Report(issues, materials(blueprint, backpack), POWER_UNREADABLE, CHANNELS_UNREADABLE);
    }

    // 部件：宿主要在蓝图里，且要有机器类型说装得上那一面。
    private static void reviewParts(MachineBlueprint blueprint, MachineServices services, List<Issue> issues) {
        for (MachineBlueprint.Part part : blueprint.parts()) {
            Optional<BlockState> host = blueprint.hostStateAt(part.offset());
            if (host.isEmpty()) {
                issues.add(new Issue(where(part.offset()), "部件的宿主不在蓝图里，装不上",
                        "先把宿主方块（一般是线缆）写进 cells"));
                continue;
            }
            List<String> refusals = new ArrayList<>();
            for (MachineType type : services.machineTypes()) {
                Optional<String> problem = type.partProblem(
                        new PartCell(part.offset(), part.side(), part.itemId()), host.get());
                if (problem.isEmpty()) {
                    refusals.clear();
                    break;
                }
                refusals.add(type.id() + "：" + problem.get());
            }
            if (!refusals.isEmpty()) {
                issues.add(new Issue(where(part.offset()), "没有机器类型肯把 " + part.itemId()
                        + " 装在这格宿主的" + part.side().getName() + "面：" + String.join("；", refusals),
                        "对照宿主方块与部件物品，或装上对应模组的联动再看"));
            }
        }
    }

    // 安装段：要有机器类型说形状合规则；一个都没有就是缺联动或形状不对。
    private static void reviewInstallations(MachineBlueprint blueprint, MachineServices services,
            List<Issue> issues) {
        for (MachineBlueprint.Segment segment : blueprint.installations()) {
            List<String> refusals = new ArrayList<>();
            for (MachineType type : services.machineTypes()) {
                Optional<String> problem = type.installationProblem(
                        new Installation(segment.kind(), segment.offsets()));
                if (problem.isEmpty()) {
                    refusals.clear();
                    break;
                }
                refusals.add(type.id() + "：" + problem.get());
            }
            if (!refusals.isEmpty()) {
                boolean noneRegistered = services.machineTypes().isEmpty();
                issues.add(new Issue(where(segment.offsets().get(0)),
                        noneRegistered ? "这个实例没有登记任何机器联动，认不出安装段 " + segment.kind()
                                : "没有机器类型认这段 " + segment.kind() + "：" + String.join("；", refusals),
                        "装了对应模组的联动后施工时才能装这段；形状规则以它为准"));
            }
        }
    }

    // 工序：那格要有机器认领；查得到的配方里要有这台机器做的；做得了还要有地方投料。
    private static void reviewProcesses(MachineBlueprint blueprint, MachineServices services,
            List<Issue> issues) {
        for (MachineBlueprint.Process process : blueprint.processes()) {
            Optional<PlannedCell> cell = blueprint.cellAt(process.offset());
            if (cell.isEmpty()) {
                issues.add(new Issue(where(process.offset()), "工序指的格不在蓝图里", "offset 对齐 cells"));
                continue;
            }
            MachineType type = services.claiming(cell.get().state());
            if (type == null) {
                issues.add(new Issue(where(process.offset()),
                        "这格是 " + blockId(cell.get().state()) + "，没有机器类型认领它，工序挂不上去",
                        "装上对应模组的联动，或把工序挪到认得出的机器上"));
                continue;
            }
            RecipeLookup.Answer answer = services.recipes().atWorkstation(type.id());
            List<ShownRecipe> making = answer.recipes().stream()
                    .filter(recipe -> recipe.makes(process.item())).toList();
            if (making.isEmpty()) {
                String withoutViewer = answer.answered() ? "" : "（这个实例没有配方查看器，查不出在哪台机器上做）";
                issues.add(new Issue(where(process.offset()) + " " + type.name(),
                        "查到的配方里没有这台机器做 " + process.item() + " 的" + withoutViewer,
                        "先 lookup 查这台机器能做什么"));
                continue;
            }
            if (!hasInputEndpoint(blueprint, services, process.offset())) {
                issues.add(new Issue(where(process.offset()) + " " + type.name(),
                        "这道配方要投料，但蓝图里这台机器没有输入口，旁边也没有搬运的东西",
                        "给它一个输入面（输入箱、置物台）或接一条搬运线"));
            }
        }
    }

    // 输入端：这台机器的进出口里有输入，或蓝图里相邻有认领为搬运的机器方块。
    private static boolean hasInputEndpoint(MachineBlueprint blueprint, MachineServices services,
            BlockPos at) {
        Optional<BlockState> host = blueprint.hostStateAt(at);
        if (host.isPresent()) {
            MachineType type = services.claiming(host.get());
            if (type != null) {
                for (ExchangePoint point : type.exchangePoints(host.get(), at)) {
                    if (point.flow() != ExchangePoint.Flow.OUTPUT) {
                        return true;
                    }
                }
            }
        }
        for (BlockPos neighbor : List.of(at.above(), at.below(), at.north(), at.south(), at.east(), at.west())) {
            Optional<BlockState> beside = blueprint.hostStateAt(neighbor);
            if (beside.isEmpty()) {
                continue;
            }
            MachineType type = services.claiming(beside.get());
            if (type != null && type.role() == MachineRole.LOGISTICS) {
                return true;
            }
        }
        return false;
    }

    // 动力：每个动力消费者都要沿着传动连到蓝图里的某个动力源；蓝图里没有动力源就一起说清。
    private static void reviewPower(MachineBlueprint blueprint, MachineServices services, List<Issue> issues) {
        Map<BlockPos, MachineRole> roles = new LinkedHashMap<>();
        for (PlannedCell cell : blueprint.cells()) {
            MachineType type = services.claiming(cell.state());
            if (type != null && switch (type.role()) {
                case POWER_SOURCE, TRANSMISSION, PROCESSING -> true;
                default -> false;
            }) {
                roles.put(cell.pos(), type.role());
            }
        }
        if (!roles.containsValue(MachineRole.PROCESSING)) {
            return;
        }
        if (!roles.containsValue(MachineRole.POWER_SOURCE)) {
            issues.add(new Issue("整份蓝图", "蓝图里有要动力的机器，却没有动力源",
                    "补上动力源，或建好后用 machine_connect 接现场已有的动力"));
            return;
        }
        for (Map.Entry<BlockPos, MachineRole> entry : roles.entrySet()) {
            if (entry.getValue() != MachineRole.PROCESSING) {
                continue;
            }
            if (!reachesPowerSource(entry.getKey(), roles, new LinkedHashSet<>())) {
                issues.add(new Issue(where(entry.getKey()),
                        "这台机器沿着传动连不到蓝图里的任何动力源",
                        "在蓝图里补上传动（轴、齿轮），或建好后用 machine_connect 接动力"));
            }
        }
    }

    // 沿传动与相邻动力源深搜：消费者经过传动方块（或直接贴着动力源）能不能够到动力。
    private static boolean reachesPowerSource(BlockPos at, Map<BlockPos, MachineRole> roles, Set<BlockPos> visited) {
        if (!visited.add(at)) {
            return false;
        }
        for (BlockPos neighbor : List.of(at.above(), at.below(), at.north(), at.south(), at.east(), at.west())) {
            MachineRole role = roles.get(neighbor);
            if (role == MachineRole.POWER_SOURCE) {
                return true;
            }
            if (role == MachineRole.TRANSMISSION && reachesPowerSource(neighbor, roles, visited)) {
                return true;
            }
        }
        return false;
    }

    // 材料账：逐格清单按计划格算，部件一件一件算，安装段按格数估；身上有多少从背包读。
    private static List<MachineReview.Material> materials(MachineBlueprint blueprint, BackpackView backpack) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        needed.putAll(Blueprint.materialsOf(blueprint.cells()));
        for (MachineBlueprint.Part part : blueprint.parts()) {
            needed.merge(part.itemId(), 1, Integer::sum);
        }
        for (MachineBlueprint.Segment segment : blueprint.installations()) {
            ResourceLocation item = ResourceLocation.tryParse(segment.kind());
            if (item != null && BuiltInRegistries.ITEM.getOptional(item).isPresent()) {
                needed.merge(item.toString(), segment.offsets().size(), Integer::sum);
            }
        }
        Map<String, Integer> carried = new LinkedHashMap<>();
        if (backpack != null) {
            for (var stack : backpack.stacks()) {
                carried.merge(stack.itemId(), stack.count(), Integer::sum);
            }
        }
        List<MachineReview.Material> out = new ArrayList<>();
        needed.forEach((item, count) -> {
            int have = carried.getOrDefault(item, 0);
            out.add(new MachineReview.Material(item, count, have, Math.max(0, count - have)));
        });
        return out;
    }

    private static String where(BlockPos at) {
        return "(" + at.getX() + "," + at.getY() + "," + at.getZ() + ")";
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
