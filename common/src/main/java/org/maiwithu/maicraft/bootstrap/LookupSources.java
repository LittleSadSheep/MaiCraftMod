// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.GameRecipes;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ClientItemDescriptions;
import org.maiwithu.maicraft.game.world.ClientRegisteredIds;
import org.maiwithu.maicraft.game.world.ReadsItemDescriptions;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.maiwithu.maicraft.mcp.knowledge.IdCatalogPages;
import org.maiwithu.maicraft.mcp.knowledge.ItemPages;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.tool.ClientThread;
import org.maiwithu.maicraft.mcp.tool.LookupTool;

/**
 * lookup 查得到的东西从哪来：配方先问联动登记的配方查看器，都答不了退到游戏配方表；
 * 知识库 = 物品资料页（总入口）+ 本实例的群系与结构目录 + 联动模组登记的资料来源，物品资料页列各来源跟这件物品有关的条目。
 * 启动在联动清单检查完、登记表定下来之后建一次。
 */
final class LookupSources {

    private LookupSources() {}

    /**
     * @param abilities  能力注册表：查能力说明
     * @param compat     联动登记表：配方查看器与资料来源
     * @param now        当刻的角色上下文：物品说明、配方表都从角色所在的世界读
     * @param clientWork 读游戏现场的部分交给客户端线程
     */
    static LookupTool lookupTool(AbilityRegistry abilities, CompatRegistry compat, Supplier<PlayerContext> now,
            ClientThread clientWork) {
        RecipeLookup recipes = new RecipeLookup(compat.recipeViewers(), new GameRecipeTable(GameRecipes.fromPlayer(now)));
        ReadsItemDescriptions items = new ClientItemDescriptions(now);
        List<KnowledgeSource> modSources = compat.knowledgeSources();
        List<KnowledgeSource> sources = new ArrayList<>();
        sources.add(new ItemPages(items, recipes, modSources));
        // 群系与结构目录：群系从角色当刻所在世界的注册表读，探索认不出群系时指到这一页。
        sources.add(new IdCatalogPages(new ClientRegisteredIds(now)));
        sources.addAll(modSources);
        return new LookupTool(abilities, new KnowledgeLibrary(sources), recipes, items, clientWork);
    }
}
