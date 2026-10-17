// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.maiwithu.maicraft.behavior.navigation.baritone.NavigationHazards;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.DamageObserver;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 吃过亏的方块（小本本）：没人打、碰着某种方块就掉了血，把这种方块记下来，以后寻路一律绕开。
 * 凭身体感受认，不给每个模组写名单；记在所有世界共用的那份里——荆棘在哪个世界都扎人。
 * 实机 10-14 下界被 biomesoplenty:bramble 连扎三下还往里走。
 *
 * <p>名字和伤害类型对得上的一次就记；只是唯一碰着的那种，再疼一次才记，免得冤枉旁边的方块。
 */
public final class PainfulBlocks implements DamageObserver {

    private static final Logger LOG = LoggerFactory.getLogger(PainfulBlocks.class);
    static final String SCOPE = "painful-blocks";
    /** 不分世界：身体的经验跟着角色走。 */
    private static final String IDENTITY = "body";
    private static final String KEY = "blocks";
    private static final int LIMIT = 64 * 1024;
    /** 嫌疑要疼这么多次才记。 */
    private static final int SUSPECT_HITS = 2;
    /** 碰着的范围比身体大一圈：客户端看到的位置和服务端结算时差一点。 */
    private static final double TOUCH_MARGIN = 0.05;

    private final DocumentStore documents;
    /** 记下的：方块 ID → 当时疼的伤害类型。 */
    private final Map<String, String> learned = new LinkedHashMap<>();
    private final Map<String, Integer> suspects = new HashMap<>();
    /** 这种伤（原版 msgId）最近一次是碰着哪种方块挨的：挨打事件的一句话用。 */
    private final Map<String, String> lastCulprit = new HashMap<>();

    public PainfulBlocks(DocumentStore documents) {
        this.documents = documents;
        load();
        install();
    }

    // 疼了一下：没有攻击者时看身上碰着什么，认出来的记一笔；名单变了就交给寻路。
    @Override
    public void hurt(LocalPlayer player, DamageSource source, long gameTick) {
        if (source.getEntity() != null || source.getDirectEntity() != null || player.level() == null) return;
        String damageType = source.typeHolder().unwrapKey().map(key -> key.location().toString())
                .orElse(source.type().msgId());
        Optional<PainCulprit.Verdict> verdict = PainCulprit.judge(damageType, touched(player));
        if (verdict.isEmpty()) return;
        String blockId = verdict.get().blockId();
        lastCulprit.put(source.type().msgId(), blockId);
        if (learned.containsKey(blockId)) return;
        if (!verdict.get().certain() && suspects.merge(blockId, 1, Integer::sum) < SUSPECT_HITS) return;
        learned.put(blockId, damageType);
        suspects.remove(blockId);
        LOG.info("[吃过亏] 碰到 {} 受了 {} 伤，记下来以后寻路绕开", blockId, damageType);
        save();
        install();
    }

    /** 这种伤（原版 msgId）是碰着哪种方块挨的、记没记下；认不出为空。 */
    public Optional<String> culpritOf(String damageMsgId) {
        String blockId = lastCulprit.get(damageMsgId);
        if (blockId == null) return Optional.empty();
        return Optional.of(learned.containsKey(blockId)
                ? "碰到 " + blockId + " 扎的；这种方块已记进吃过亏的方块，以后寻路绕开"
                : "像是碰到 " + blockId + " 扎的；再疼一次就记下来绕开");
    }

    /** 记下的方块 ID。 */
    public List<String> blocks() {
        return List.copyOf(learned.keySet());
    }

    // 身体碰着的每一格：方块、是不是脚下的地面、是不是本来就不算、整格实心、可替换。
    private List<PainCulprit.Touched> touched(LocalPlayer player) {
        Level level = player.level();
        AABB body = player.getBoundingBox().inflate(TOUCH_MARGIN);
        List<PainCulprit.Touched> cells = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, pos);
            boolean underFoot = pos.getY() < player.getY()
                    && (shape.isEmpty() ? pos.getY() + 1 : pos.getY() + shape.max(Direction.Axis.Y))
                    <= player.getY() + 0.01;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            // 原版本来就绕开的（仙人掌、火、岩浆块）不再记；已经记下的照样认，挨打的那句才说得出是它。
            boolean ruledOut = !state.getFluidState().isEmpty() || state.getBlock() instanceof DoorBlock
                    || state.getBlock() instanceof TrapDoorBlock || state.getBlock() instanceof FenceGateBlock
                    || BlockPassability.avoidWalkingInto(level, pos) && !learned.containsKey(id);
            cells.add(new PainCulprit.Touched(id,
                    underFoot, ruledOut, Block.isShapeFullBlock(shape), state.is(BlockTags.REPLACEABLE)));
        }
        return cells;
    }

    // 名单交给寻路：认不出的 ID（模组卸了）跳过。
    private void install() {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String id : learned.keySet()) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null && BuiltInRegistries.BLOCK.containsKey(location)) {
                blocks.add(BuiltInRegistries.BLOCK.get(location));
            }
        }
        NavigationHazards.install(blocks);
    }

    private void load() {
        try {
            String json = documents.read(SCOPE, IDENTITY, KEY, LIMIT);
            if (json == null) return;
            for (var element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("blocks")) {
                JsonObject entry = element.getAsJsonObject();
                learned.put(entry.get("block").getAsString(), entry.get("damage").getAsString());
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warn("吃过亏的方块读不出来，按空的开始", failure);
        }
    }

    private void save() {
        JsonArray blocks = new JsonArray();
        learned.forEach((block, damage) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("block", block);
            entry.addProperty("damage", damage);
            blocks.add(entry);
        });
        JsonObject root = new JsonObject();
        root.add("blocks", blocks);
        try {
            documents.write(SCOPE, IDENTITY, KEY, root.toString(), false);
        } catch (IOException failure) {
            LOG.warn("吃过亏的方块存盘失败，这次进世界内仍然绕开", failure);
        }
    }
}
