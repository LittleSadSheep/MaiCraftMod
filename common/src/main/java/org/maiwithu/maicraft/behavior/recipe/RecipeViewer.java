// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.List;
import java.util.Objects;

/**
 * 配方查看器：EMI、JEI 这类给玩家看配方的模组，以及最后退路的游戏配方表；这里是"从它读配方"的读取接缝。
 *
 * <p>给出的是玩家在查看器里看得到的：被查看器隐藏的配方与类别不给。只读不做：不打开界面、不点格子、不试做配方。
 * 只在客户端线程上调用，模组的配方索引与游戏配方表都不是给别的线程读的。
 */
public interface RecipeViewer {

    /** 结果里写"从哪读的"用的名字，例如 emi、jei、game。 */
    String name();

    /**
     * 会不会把别的配方查看器的配方一起导进来。EMI 会原样导入 JEI 插件注册的配方类别（类别 ID 不变），
     * 两个都装时问它就看到两者之和；配方查询先问报 true 的，不用自己再合并去重。
     */
    boolean importsOtherViewers();

    /** 此刻能不能回答：还在加载配方、角色不在世界里、联动停用时，说明原因，配方查询就去问下一个。 */
    Readiness readiness();

    /** 能做出这件物品的配方（物品 ID，例如 create:andesite_alloy）。 */
    List<ShownRecipe> making(String itemId);

    /** 拿这件物品当原料或催化剂的配方。 */
    List<ShownRecipe> using(String itemId);

    /** 这件物品当工作站的那些配方类别里的全部配方：机器能做哪些加工就在这里。 */
    List<ShownRecipe> atWorkstation(String itemId);

    /**
     * 能不能回答。
     *
     * @param ready  true 时可以问
     * @param reason 不能回答的原因，写给 LLM 看的一句话；能回答时为空串
     */
    record Readiness(boolean ready, String reason) {
        public Readiness {
            Objects.requireNonNull(reason, "reason");
            if (!ready && reason.isBlank()) throw new IllegalArgumentException("不能回答时要写明原因");
        }

        /** 能回答。 */
        public static Readiness yes() {
            return new Readiness(true, "");
        }

        /** 不能回答，以及为什么。 */
        public static Readiness no(String reason) {
            return new Readiness(false, reason);
        }
    }
}
