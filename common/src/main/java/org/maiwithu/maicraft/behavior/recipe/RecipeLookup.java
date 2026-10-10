// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配方查询：一次只挑一个配方查看器回答，不把几个查看器的结果拼在一起。
 *
 * <p>先问会把别的查看器的配方一起导进来的（EMI 会导入 JEI 插件的类别），再问其余联动登记的查看器，
 * 都不能回答时退到游戏配方表。被跳过的查看器写一句为什么，让 LLM 知道这次看到的是谁的配方：
 * EMI 刚进世界要加载几秒，这段时间由 JEI 回答；两个都没装时游戏配方表看得到原料和产出，看不出在哪台机器上做。
 *
 * <p>只在客户端线程上调用。LLM 经 lookup 查配方、机器能力核对"这台机器能不能做这道工序"都走这里。
 */
public final class RecipeLookup {
    private static final Logger LOG = LoggerFactory.getLogger(RecipeLookup.class);

    private final List<RecipeViewer> order;
    private final RecipeViewer gameTable;
    private final boolean modViewersInstalled;

    /**
     * @param modViewers 联动登记的配方查看器，按联动清单的顺序
     * @param gameTable  游戏配方表：总在最后
     */
    public RecipeLookup(List<RecipeViewer> modViewers, RecipeViewer gameTable) {
        this.gameTable = Objects.requireNonNull(gameTable, "gameTable");
        List<RecipeViewer> ordered = new ArrayList<>();
        // 会导入别家配方的排前面：同装时问它就是两者之和，问另一个反而少看一部分。
        modViewers.stream().filter(RecipeViewer::importsOtherViewers).forEach(ordered::add);
        modViewers.stream().filter(viewer -> !viewer.importsOtherViewers()).forEach(ordered::add);
        this.modViewersInstalled = !ordered.isEmpty();
        ordered.add(gameTable);
        this.order = List.copyOf(ordered);
    }

    /** 能做出这件物品的配方。 */
    public Answer making(String itemId) {
        return ask(itemId, viewer -> viewer.making(itemId));
    }

    /** 拿这件物品当原料或催化剂的配方。 */
    public Answer using(String itemId) {
        return ask(itemId, viewer -> viewer.using(itemId));
    }

    /** 这件物品当工作站的配方：机器能做哪些加工。 */
    public Answer atWorkstation(String itemId) {
        return ask(itemId, viewer -> viewer.atWorkstation(itemId));
    }

    // 按顺序找第一个能回答的查看器；问的时候出错（读写端坏了、模组接口对不上）也算这一个不能回答，
    // 记一句原因换下一个，查配方不因为一个模组出问题而报内部错误。
    private Answer ask(String itemId, Function<RecipeViewer, List<ShownRecipe>> question) {
        Objects.requireNonNull(itemId, "itemId");
        List<String> notes = new ArrayList<>();
        for (RecipeViewer viewer : order) {
            RecipeViewer.Readiness readiness = viewer.readiness();
            if (!readiness.ready()) {
                notes.add(viewer.name() + " 这次没回答：" + readiness.reason());
                continue;
            }
            List<ShownRecipe> recipes;
            try {
                recipes = question.apply(viewer);
            } catch (RuntimeException failure) {
                LOG.warn("配方查看器 {} 查 {} 时出错，换下一个查看器回答", viewer.name(), itemId, failure);
                notes.add(viewer.name() + " 这次没回答：读配方时出错（" + failure.getMessage() + "）");
                continue;
            }
            // 退到游戏配方表时说清它看不出什么：工作站（哪台机器做）只有配方查看器标得出来。
            if (viewer == gameTable) {
                notes.add((modViewersInstalled ? "配方查看器这次都没回答，由游戏配方表回答"
                        : "这个实例里没有配方查看器（例如 EMI、JEI），由游戏配方表回答")
                        + "：模组加工配方看得到原料和产出，看不出用哪台机器做");
            }
            return new Answer(viewer.name(), distinct(recipes), notes);
        }
        // 游戏配方表也答不了（不在世界里）：如实说谁都没回答，原因都在 notes 里。
        return new Answer("", List.of(), notes);
    }

    // 同一类别下背后是同一条游戏配方的只留一次；查看器给不出配方 ID 的展示配方各算各的。
    private static List<ShownRecipe> distinct(List<ShownRecipe> recipes) {
        Set<String> seen = new HashSet<>();
        List<ShownRecipe> kept = new ArrayList<>();
        for (ShownRecipe recipe : recipes) {
            if (recipe.recipeId() == null || seen.add(recipe.category() + "\n" + recipe.recipeId())) {
                kept.add(recipe);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * 一次查询的回答。
     *
     * @param readFrom 实际回答的查看器名字（emi、jei、game）；谁都没回答时为空串
     * @param recipes  查到的配方，一条不省
     * @param notes    给 LLM 的说明：跳过了哪个查看器、为什么，没装查看器时看不出什么
     */
    public record Answer(String readFrom, List<ShownRecipe> recipes, List<String> notes) {
        public Answer {
            Objects.requireNonNull(readFrom, "readFrom");
            recipes = List.copyOf(recipes);
            notes = List.copyOf(notes);
        }

        /** 有没有查看器回答了这次查询。 */
        public boolean answered() {
            return !readFrom.isEmpty();
        }
    }
}
