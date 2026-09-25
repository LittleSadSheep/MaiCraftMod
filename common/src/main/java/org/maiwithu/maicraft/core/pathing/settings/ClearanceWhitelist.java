package org.maiwithu.maicraft.core.pathing.settings;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.Constants;

/** 寻路挖路与施工清场共用的方块类型白名单；默认自然地形不依赖服务端安装本模组。 */
public final class ClearanceWhitelist {
    public static final String CONFIG = "config/maicraft-clearance.json";
    private static final List<String> DEFAULTS = Arrays.stream(("""
            stone granite diorite andesite deepslate tuff calcite dripstone_block pointed_dripstone
            dirt grass_block coarse_dirt podzol mycelium rooted_dirt mud muddy_mangrove_roots clay
            sand red_sand gravel sandstone red_sandstone terracotta white_terracotta orange_terracotta
            yellow_terracotta brown_terracotta red_terracotta light_gray_terracotta
            netherrack soul_sand soul_soil basalt smooth_basalt blackstone end_stone obsidian
            coal_ore iron_ore copper_ore gold_ore redstone_ore emerald_ore lapis_ore diamond_ore
            deepslate_coal_ore deepslate_iron_ore deepslate_copper_ore deepslate_gold_ore
            deepslate_redstone_ore deepslate_emerald_ore deepslate_lapis_ore deepslate_diamond_ore
            nether_gold_ore nether_quartz_ore ancient_debris raw_iron_block raw_copper_block
            snow snow_block ice packed_ice blue_ice powder_snow moss_block moss_carpet
            oak_log spruce_log birch_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log
            oak_leaves spruce_leaves birch_leaves jungle_leaves acacia_leaves dark_oak_leaves
            mangrove_leaves cherry_leaves azalea_leaves flowering_azalea_leaves mangrove_roots
            crimson_stem warped_stem nether_wart_block warped_wart_block shroomlight
            crimson_nylium warped_nylium brown_mushroom_block red_mushroom_block mushroom_stem
            short_grass tall_grass fern large_fern dead_bush vine glow_lichen hanging_roots
            azalea flowering_azalea small_dripleaf big_dripleaf big_dripleaf_stem spore_blossom
            cave_vines cave_vines_plant weeping_vines weeping_vines_plant twisting_vines twisting_vines_plant
            crimson_roots warped_roots nether_sprouts crimson_fungus warped_fungus
            dandelion poppy blue_orchid allium azure_bluet red_tulip orange_tulip white_tulip pink_tulip
            oxeye_daisy cornflower lily_of_the_valley sunflower lilac rose_bush peony pink_petals
            brown_mushroom red_mushroom sugar_cane cactus bamboo bamboo_sapling sweet_berry_bush
            pumpkin melon lily_pad seagrass tall_seagrass kelp kelp_plant chorus_plant chorus_flower
            """).trim().split("\\s+")).map(name -> "minecraft:" + name).toList();
    private static volatile Rules current = parse(DEFAULTS);

    private ClearanceWhitelist() {}

    /** 客户端启动时读取本实例的名单；空表禁止清障，坏配置也关闭清障，避免意外放宽玩家的限制。 */
    public static void initialize(Path gameDirectory) {
        Path file = gameDirectory.resolve(CONFIG);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(DEFAULTS) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!json.isJsonArray()) throw new IllegalArgumentException("expected an array of block IDs or #block_tags");
            var entries = new ArrayList<String>();
            for (var value : json.getAsJsonArray()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("whitelist entries must be strings");
                entries.add(value.getAsString());
            }
            current = parse(entries);
        } catch (IOException | RuntimeException invalid) {
            current = parse(List.of());
            Constants.LOG.warn("[clearance] 清障白名单读取失败，已禁止自动清障：{}: {}", file, invalid.getMessage());
        }
    }

    /** 已经是空气就无需清障；其他格严格按类型匹配，替换许可、流体和保护格仍由各执行器另行核验。 */
    public static boolean allows(BlockState state) { return state.isAir() || current.allows(state); }

    // 明确列举方块或引用实际同步的标签；不凭名称包含 stone、ore 等文字猜测模组机器是否属于自然地形。
    static Rules parse(List<String> entries) {
        Set<ResourceLocation> blocks = new LinkedHashSet<>();
        Set<TagKey<Block>> tags = new LinkedHashSet<>();
        for (String entry : entries) {
            String text = entry.trim();
            boolean tag = text.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
            if (id == null) throw new IllegalArgumentException("invalid clearance entry: " + entry);
            if (tag) tags.add(TagKey.create(Registries.BLOCK, id)); else blocks.add(id);
        }
        return new Rules(Set.copyOf(blocks), Set.copyOf(tags));
    }

    record Rules(Set<ResourceLocation> blocks, Set<TagKey<Block>> tags) {
        boolean allows(BlockState state) {
            return blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock())) || tags.stream().anyMatch(state::is);
        }
    }
}
