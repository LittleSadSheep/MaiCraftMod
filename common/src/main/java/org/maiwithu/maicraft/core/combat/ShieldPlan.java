package org.maiwithu.maicraft.core.combat;

/**
 * 描述一种“攻击冷却时举盾、准备攻击时松盾”的选择规则，返回建议，不真正按键。
 * 目前只保留这份规则，实际战斗没有接入这个 decide 方法；改这里不会自动改变玩家的举盾行为。
 */
public final class ShieldPlan {

    /** 这一刻拿盾做什么。 */
    public enum Decision {
        /** 不关盾的事,该干嘛干嘛。 */
        PROCEED,
        /** 手上正用着别的东西(拉弓、吃东西),这一刻别碰。 */
        WAIT,
        /** 举起来。 */
        RAISE,
        /** 举着别放。 */
        HOLD,
        /** 放下 —— 冷却好了,该砍了。 */
        RELEASE
    }

    private ShieldPlan() {}

    /**
     * @param shieldUsable  副手有盾且不在冷却里(被斧子破盾会进冷却)
     * @param usingOtherItem 正在用别的东西:拉弓、吃东西
     * @param shieldRaised   这一刻盾已经举着
     * @param attackReady    攻击充能到位,见 {@link Swing#ATTACK_READY}
     */
    // 正在吃东西等其他使用动作就等；已经举盾且能攻击了就松盾；攻击冷却没好且盾可用就举盾。
    // 这是独立的判断函数。当前生产源码没有调用它，真实举盾流程在 AttackCompanionTask.tickShield。
    public static Decision decide(boolean shieldUsable, boolean usingOtherItem,
                                  boolean shieldRaised, boolean attackReady) {
        if (usingOtherItem) {
            return Decision.WAIT;
        }
        if (shieldRaised) {
            return attackReady ? Decision.RELEASE : Decision.HOLD;
        }
        if (shieldUsable && !attackReady) {
            return Decision.RAISE;
        }
        return Decision.PROCEED;
    }
}
