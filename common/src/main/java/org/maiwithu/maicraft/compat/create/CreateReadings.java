// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 把机械动力的读数翻成机器能力认的运行状态，纯函数：转不转、转多快、过载没有、转速够不够干活，
 * 加上台面、盆里、带上画出来的东西。看得出几成就说几成，看不出的写进说明，不猜。
 */
final class CreateReadings {

    private CreateReadings() {
    }

    /**
     * 一格动力方块的运行状态。
     *
     * @param reading 动力读数；读不到为空
     * @param minimum 这台机器干活要的最低转速；没有门槛为空
     * @param shown   这一格画出来的东西（台面、盆里、带上的）
     */
    static MachineState kinetic(Optional<CreateKinetics.Reading> reading, Optional<Double> minimum,
            List<MachineState.Shown> shown) {
        if (reading.isEmpty()) {
            return new MachineState(MachineState.Activity.UNKNOWN, null, null, shown, Map.of(),
                    "读不到这一格的动力读数（没加载，或已经不是动力方块）");
        }
        CreateKinetics.Reading now = reading.get();
        Map<String, Object> readings = new LinkedHashMap<>();
        now.networkId().ifPresent(id -> {
            readings.put("stress", round(now.stress()));
            readings.put("stress_capacity", round(now.capacity()));
            readings.put("network_members", (long) now.networkSize());
        });
        minimum.ifPresent(speed -> readings.put("required_speed", speed));
        if (now.overStressed()) {
            readings.put("speed_without_overload", round(now.theoreticalSpeed()));
            return new MachineState(MachineState.Activity.OVERLOADED, 0.0, null, shown, readings,
                    "这张应力网过载了：应力 " + round(now.stress()) + " 超过容量 " + round(now.capacity())
                            + "，整张网停转");
        }
        if (!now.turning()) {
            String why = now.networkId().isEmpty() ? "没接到任何在转的动力上" : "接在网上，但这张网现在没转";
            return new MachineState(MachineState.Activity.IDLE, round(now.speed()), null, shown, readings, why);
        }
        if (!now.speedRequirementMet()) {
            // 在转但转速不够：Create 的机器这时不干活，看着在转也做不出东西。
            return new MachineState(MachineState.Activity.IDLE, round(now.speed()), null, shown, readings,
                    "在转，但转速 " + round(Math.abs(now.speed())) + " 低于这台机器干活要的 "
                            + minimum.map(CreateReadings::round).map(String::valueOf).orElse("门槛"));
        }
        return new MachineState(MachineState.Activity.RUNNING, round(now.speed()), null, shown, readings, "");
    }

    /** 不转的方块（置物台、工作盆、漏斗……）：只说画出来的东西，没有转速可言。 */
    static MachineState still(List<MachineState.Shown> shown, String note) {
        return new MachineState(MachineState.Activity.IDLE, null, null, shown, Map.of(), note);
    }

    /** 物品叠合成"什么、几件"：同一种东西合并，空的跳过。 */
    static List<MachineState.Shown> shown(List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        List<MachineState.Shown> out = new ArrayList<>();
        counts.forEach((item, count) -> out.add(new MachineState.Shown(item, count)));
        return out;
    }

    // 读数保留一位小数：转速、应力在 Create 里是浮点，结果里给到一位足够看清。
    static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
