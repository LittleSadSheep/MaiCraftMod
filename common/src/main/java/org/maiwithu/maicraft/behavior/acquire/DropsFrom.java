// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.game.world.SimilarIds;

/**
 * 掉落线索（参数 drops_from）：LLM 告诉 Mod "要的这样东西从这些方块挖得到、从这些生物身上打得到"。
 *
 * <p>客户端读不到掉落表，原版对照之外的模组矿、模组生物掉什么认不出来；给了线索，采掘、收庄稼与狩猎
 * 就把线索点名的方块与生物当作会掉要的东西的来源，找得见就挖、就打。线索不替谁判断对不对：
 * 挖了、打了掉的是什么，以实际进包的为准。线索只管这一次要的那样东西，备料时里层的请求不带它。
 *
 * @param blocks    方块注册 ID，例如 create:zinc_ore
 * @param blockTags 方块标签 ID（不带 #），例如 c:ores/zinc
 * @param entities  实体类型 ID，例如 minecraft:zombie
 */
public record DropsFrom(List<String> blocks, List<String> blockTags, List<String> entities) {

    /** 没有线索：采掘与狩猎照旧只认原版对照。 */
    public static final DropsFrom NONE = new DropsFrom(List.of(), List.of(), List.of());

    public DropsFrom {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        blockTags = blockTags == null ? List.of() : List.copyOf(blockTags);
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && blockTags.isEmpty() && entities.isEmpty();
    }

    /**
     * 把 LLM 写的线索分成方块、方块标签与实体：# 开头的按方块标签认；其余先查方块注册表再查实体注册表，
     * 两边都有的（TNT 这类既是方块也是实体）按方块。哪样都不是就记一句"不存在；你是不是要 …"，
     * 一次报全，不进世界。
     */
    public static Sorted sort(List<String> written, ReadsRegisteredIds registered) {
        List<String> registeredBlocks = registered.ids(IdKind.BLOCK);
        List<String> registeredEntities = registered.ids(IdKind.ENTITY_TYPE);
        Set<String> blockIds = new HashSet<>(registeredBlocks);
        Set<String> entityIds = new HashSet<>(registeredEntities);
        Set<String> filledBlockTags = new HashSet<>(registered.tags(IdKind.BLOCK));
        List<String> blocks = new ArrayList<>();
        List<String> blockTags = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String raw : written) {
            String id = raw.trim();
            if (id.startsWith("#")) {
                // 方块标签只和方块标签比：#c:ores/zin 该提示 #c:ores/zinc。
                if (filledBlockTags.contains(id.substring(1))) blockTags.add(id.substring(1));
                else problems.add(registered.missing(IdKind.BLOCK, id));
            } else if (blockIds.contains(id)) {
                blocks.add(id);
            } else if (entityIds.contains(id)) {
                entities.add(id);
            } else {
                // 方块与实体都没有：从两边一起挑最像的那个，写错的 minecraft:zombi 提示 minecraft:zombie；
                // 候选按注册表排好的先后比，同样像时每次给的建议都一样。
                List<String> both = new ArrayList<>(registeredBlocks);
                both.addAll(registeredEntities);
                problems.add(SimilarIds.missing("方块或实体", id, SimilarIds.closest(both, id)));
            }
        }
        return new Sorted(new DropsFrom(blocks, blockTags, entities), problems);
    }

    /** 分好的线索，连同写错的那几项的说法；problems 为空才算立得住。 */
    public record Sorted(DropsFrom dropsFrom, List<String> problems) {
        public Sorted {
            problems = List.copyOf(problems);
        }
    }
}
