// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.wait;

/**
 * 等待任务要等到的条件：五种里选一种，非法取值在参数校验阶段就已拒绝。
 *
 * <p>等待只观察，不替角色达成条件——等血回满不会自己去吃东西，等天黑不会去改时间。
 */
enum WaitFor {
    /** 只等时间：过了 after_seconds 就算等到。 */
    ELAPSED("elapsed"),
    /** 等到白天。 */
    DAY("day"),
    /** 等到夜晚。 */
    NIGHT("night"),
    /** 等生命回满。 */
    HEALTH_FULL("health_full"),
    /** 等不再饥饿：饱食度回到原版自然回血线 18 以上。 */
    NOT_HUNGRY("not_hungry");

    /** 原版自然回血需要的最低饱食度；不到这条线身体不会自己回血。 */
    static final int NATURAL_REGEN_FOOD = 18;

    private final String param;

    WaitFor(String param) {
        this.param = param;
    }

    String param() {
        return param;
    }

    /** 参数取值转条件；参数校验保证只会给合法取值。 */
    static WaitFor ofParam(String value) {
        for (WaitFor condition : values()) {
            if (condition.param.equals(value)) return condition;
        }
        throw new IllegalArgumentException("不认识的等待条件：" + value);
    }
}
