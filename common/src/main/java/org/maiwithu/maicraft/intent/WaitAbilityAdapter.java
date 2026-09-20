package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;

/** 把“先等多久、再等什么”转换成只读条件；计划和执行共用同一套参数检查。 */
final class WaitAbilityAdapter {
    static final String ABILITY = "maicraft:wait_for_condition";

    enum Condition {
        ELAPSED("elapsed"), DAY("day"), NIGHT("night"), HEALTH_FULL("health_full"), NOT_HUNGRY("not_hungry");

        private final String id;
        Condition(String id) { this.id = id; }
        String id() { return id; }

        // 等待只观察眼前状态，不替玩家吃东西、恢复生命或修改世界时间。
        boolean satisfiedBy(LocalPlayer player) {
            return switch (this) {
                case ELAPSED -> true;
                case DAY -> WorldTimeSemantics.isDaytime(player.level());
                case NIGHT -> WorldTimeSemantics.isNighttime(player.level());
                case HEALTH_FULL -> player.getHealth() >= player.getMaxHealth();
                case NOT_HUNGRY -> player.getFoodData().getFoodLevel() >= 18;
            };
        }
    }

    private WaitAbilityAdapter() { }

    static void validate(Goal goal) {
        JsonObject parameters = goal.parameters();
        condition(parameters);
        delaySeconds(parameters);
    }

    static IntentAction.Wait adapt(Goal goal, LocalPlayer player) {
        JsonObject parameters = goal.parameters();
        // after_s 是最短等待时间，不是超时；到点后仍需观察所选条件是否成立。
        return new IntentAction.Wait(condition(parameters),
                player.level().getGameTime() + delaySeconds(parameters) * 20L);
    }

    private static Condition condition(JsonObject parameters) {
        var value = parameters.get("condition");
        if (value == null) return Condition.ELAPSED;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            for (Condition condition : Condition.values()) {
                if (condition.id().equals(value.getAsString())) return condition;
            }
        }
        throw new IllegalArgumentException("condition must be elapsed, day, night, health_full or not_hungry");
    }

    private static int delaySeconds(JsonObject parameters) {
        var value = parameters.get("after_s");
        if (value == null) return 1;
        // 明确拒绝小数、字符串和越界时长，不能把玩家的等待要求偷偷截断或压到另一数值。
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int seconds = value.getAsBigDecimal().intValueExact();
                if (seconds >= 0 && seconds <= 3600) return seconds;
            } catch (ArithmeticException invalid) { }
        }
        throw new IllegalArgumentException("after_s must be an integer from 0 to 3600");
    }
}
