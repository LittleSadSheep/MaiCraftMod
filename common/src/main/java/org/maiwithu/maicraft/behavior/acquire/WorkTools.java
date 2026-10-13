// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

/**
 * 成批干活前先备一把效率工具的规则：砍一片树先做石斧，挖一片石头先做石镐，像玩家一样不徒手、不拿木镐硬挖。
 * 工具只是建议——备不来照样徒手干，不拦活；开局什么都没有时先徒手弄够做第一把工具的料，不为做斧头先要斧头。
 * 纯规则，不读游戏；身上和方块的事实由游戏接口层读出来交进来。
 */
public final class WorkTools {

    /** 一批活不到这么多格、身上也没有木制工具在干这活：不值得先备工具，直接干。 */
    public static final int BATCH_SIZE = 4;

    private WorkTools() {}

    /**
     * 这类方块对口的那把基准工具：按方块要求的最低等级，至少是石制（木制工具挖三块石头就该换了）。
     *
     * @param family       对口的工具种类：axe / pickaxe / shovel；别的不备
     * @param requiredTier 方块要求的等级：0 不要求，1 石，2 铁，3 钻石
     */
    public static Optional<String> reference(String family, int requiredTier) {
        if (!"axe".equals(family) && !"pickaxe".equals(family) && !"shovel".equals(family)) return Optional.empty();
        String material = switch (Math.max(1, requiredTier)) {
            case 3 -> "diamond";
            case 2 -> "iron";
            default -> "stone";
        };
        return Optional.of("minecraft:" + material + "_" + family);
    }

    /** 身上那把工具的耐久至少要留多少才算够这批活：这批几格再加余量，最少十六、最多六十四。 */
    public static int durabilityNeeded(int work) {
        return Math.min(64, Math.max(16, work + 8));
    }

    /**
     * 这批活要不要先备基准工具。
     *
     * @param work            这批还要几件
     * @param woodenToolWorks 身上有木制工具正好能干这活（木镐挖石头、木斧砍树）：活再少也该换石制的
     * @param goodEnough      身上已有一把和基准一样快、耐久够这批的
     */
    public static Optional<String> toPrepare(Optional<String> reference, int work, boolean woodenToolWorks,
            boolean goodEnough) {
        if (reference.isEmpty() || goodEnough) return Optional.empty();
        if (work < BATCH_SIZE && !woodenToolWorks) return Optional.empty();
        return reference;
    }

    /**
     * 开局为了做第一把工具，还得先徒手（或用手上的木镐）弄几件：
     * 砍树时按工作台、木镐、木棍还缺几块木板算要先砍几根原木；有镐挖石头时先挖够做一把石制工具的三块石料。
     * 弄够了返回 0，接着就去备工具。
     *
     * @param logs        身上的原木
     * @param planks      身上的木板
     * @param sticks      身上的木棍
     * @param table       身上有工作台
     * @param pickaxe     身上有一把还能挖三下以上的镐
     * @param stoneTools  身上能做石制工具的石料（圆石、黑石、深板岩圆石）
     */
    public static int bootstrap(boolean log, boolean stone, int logs, int planks, int sticks, boolean table,
            boolean pickaxe, int stoneTools) {
        if (log) {
            int neededPlanks = (table ? 0 : 4) + (pickaxe ? 0 : 3) + (sticks >= (pickaxe ? 2 : 4) ? 0 : 2);
            return Math.max(0, (neededPlanks - planks - logs * 4 + 3) / 4);
        }
        if (stone && pickaxe) return Math.max(0, 3 - stoneTools);
        return 0;
    }
}
