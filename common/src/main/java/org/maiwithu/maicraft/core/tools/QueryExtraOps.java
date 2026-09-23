package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Arrays;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.mcp.knowledge.RecipeKnowledgeSource;

/**
 * 查询工具的具体实现：承载 {@code LookupRecipeTool}、{@code ScanNearbyEntitiesTool} 和 {@code InspectBlockStorageTool} 的业务逻辑。
 */
public final class QueryExtraOps {

    // ---- scan_nearby_entities：扫描附近实体 ----

    private static final int MAX_RESULTS = 20;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 64.0;

    public String scanNearbyEntities(
double radius,
String type_filter,
            LocalPlayer self) {
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        String filter = readEnum("type_filter", type_filter,
                List.of("hostile", "passive", "player", "all"));

        AABB box = self.getBoundingBox().inflate(radius);
        List<Entity> raw = self.level().getEntities(self, box);

        List<ScoredEntity> matched = new ArrayList<>(raw.size());
        for (Entity e : raw) {
            String cat = categorise(e);
            if (!matches(filter, cat)) continue;
            matched.add(new ScoredEntity(e, cat, self.distanceTo(e)));
        }
        matched.sort(Comparator.comparingDouble(s -> s.distance));

        JsonArray entities = new JsonArray();
        int limit = Math.min(matched.size(), MAX_RESULTS);
        for (int i = 0; i < limit; i++) {
            ScoredEntity s = matched.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", s.entity.getId());
            o.addProperty("type", s.entity.getType().getDescriptionId());
            o.addProperty("category", s.category);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", s.entity.getX());
            pos.addProperty("y", s.entity.getY());
            pos.addProperty("z", s.entity.getZ());
            o.add("position", pos);
            o.addProperty("distance", s.distance);
            if (s.entity instanceof LivingEntity le) {
                o.addProperty("hp", le.getHealth());
                o.addProperty("max_hp", le.getMaxHealth());
            }
            entities.add(o);
        }

        JsonObject root = new JsonObject();
        root.add("entities", entities);
        root.addProperty("total_found", matched.size());
        root.addProperty("truncated", matched.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        root.addProperty("filter", filter);
        return root.toString();
    }

    private static String categorise(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof Monster) return "hostile";
        return "passive";
    }

    private static boolean matches(String filter, String category) {
        if ("all".equals(filter)) return true;
        return filter.equals(category);
    }

    private record ScoredEntity(Entity entity, String category, double distance) {}

    private static String readEnum(String key, String value, List<String> allowed) {
        if (value == null) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        String v = value;
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be one of " + allowed + ", got: " + v);
        }
        return v;
    }

    // ---- lookup_recipe：查询配方 ----

    /** 限制每次查询返回的配方数，保留足够候选供选择，又避免结果占用过多上下文。 */
    private static final int MAX_RECIPES = 4;

    public String lookupRecipe(
String item_id,
            LocalPlayer self) {
        Item target = ToolArgs.parseItem(item_id);
        var context = ClientRuntime.requireContext(self);
        var level = context.level();
        var recipeManager = context.connection().getRecipeManager();
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        List<String> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (recipes.size() >= MAX_RECIPES) {
                break;
            }
            // 每条配方自成一格:整合包里一条坏配方(产出为 null、输入表为 null)
            // 只丢它自己,绝不让它杀掉整个查询。见 RecipeProbe。
            try {
                Recipe<?> r = holder.value();
                if (r instanceof CraftingRecipe cr) {
                    // 产出依赖输入的配方(烟花、镶零件的装备)静态描述答不了;
                    // 1.21.1 没有 PlacementInfo,空输入表的老启发式一并保留。
                    if (cr.isSpecial() || cr.getIngredients().isEmpty()
                            || cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                        continue;
                    }
                    ItemStack result = RecipeProbe.resultOf(cr, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add("[crafting] " + format(cr, result));
                } else if (r instanceof AbstractCookingRecipe cook) {
                    ItemStack result = RecipeProbe.resultOf(cook, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add(formatCooking(cook, result));
                } else if (r instanceof StonecutterRecipe sc) {
                    ItemStack result = RecipeProbe.resultOf(sc, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add("[stonecutter] " + describeIngredient(sc.getIngredients().get(0))
                            + " -> makes " + result.getCount());
                } else if (r instanceof SmithingRecipe sm) {
                    // 锻造不走展示产出,保留空输入 assemble 的既有语义:变换配方
                    // (下界合金升级)照样给出产物,纹饰配方产出(空的)基底、自然排除。
                    ItemStack result = RecipeProbe.probe(() -> sm.assemble(new SmithingRecipeInput(
                            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), level.registryAccess()));
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add(formatSmithing(sm, result));
                }
            } catch (RuntimeException broken) {
                Constants.LOG.debug(
                        "[maicraft-recipe] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }

        if (recipes.isEmpty()) {
            // 普通工作台和炉子没查到时，模组机器仍可能加工该材料；交给按需工艺知识，不能误导角色去采矿或交易。
            return TaskResult.ok("No ordinary crafting/cooking recipe was found for " + name
                    + ". This does not prove that no recipe exists; inspect material/process knowledge at "
                    + RecipeKnowledgeSource.uri(BuiltInRegistries.ITEM.getKey(target))
                    + " before selecting another acquisition route.").toJson();
        }
        return TaskResult.ok("recipe(s) for " + name + ":\n\n" + String.join("\n\n", recipes) + "\n\n"
                + "To make it —\n"
                + "• [crafting]: call craft {item_id, count} — it lays out the grid and takes the "
                + "result for you (2x2 uses inventory; for 3x3 MaiCraft approaches a loaded table "
                + "or places a carried one, while semantic craft/acquire obtains a missing table first).\n"
                + "• [smelting|blasting|smoking]: interact_at the furnace, then transfer the input and "
                + "the fuel with NO `to` — the menu routes each to its slot. Wait, then transfer the "
                + "output back out.\n"
                + "• [stonecutter]: interact_at it, transfer the input (no `to` routes it in), take the "
                + "output. [smithing]: interact_at it, inspect_gui, then transfer template + base + "
                + "addition each into its own slot (give `to`).").toJson();
    }

    private static String format(CraftingRecipe recipe, ItemStack result) {
        int count = result.getCount();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            var cells = shaped.getIngredients();   // 1.21.1：返回 NonNullList<Ingredient>；配方空格用 Ingredient.EMPTY 表示。
            StringBuilder sb = new StringBuilder("shaped " + w + "x" + h + ", makes " + count + ":");
            for (int r = 0; r < h; r++) {
                sb.append("\n  ");
                for (int c = 0; c < w; c++) {
                    Ingredient ing = cells.get(r * w + c);
                    sb.append(ing.isEmpty() ? "." : describeIngredient(ing));
                    if (c < w - 1) {
                        sb.append(" | ");
                    }
                }
            }
            return sb.toString();
        }
        // 无序配方不要求材料顺序，可放入任意槽位。
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            counts.merge(describeIngredient(ing), 1, Integer::sum);
        }
        String list = counts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
        return "shapeless, makes " + count + ": " + list + " (place anywhere in the grid)";
    }

    /** 单输入、单输出的烹饪配方，并标明使用的工作站。 */
    private static String formatCooking(AbstractCookingRecipe recipe, ItemStack result) {
        RecipeType<?> type = recipe.getType();
        String station = type == RecipeType.BLASTING ? "blasting (blast furnace)"
                : type == RecipeType.SMOKING ? "smoking (smoker)"
                : type == RecipeType.CAMPFIRE_COOKING ? "campfire"
                : "smelting (furnace)";
        return "[" + station + "] " + describeIngredient(recipe.getIngredients().get(0)) + " -> makes "
                + result.getCount() + " (" + recipe.getCookingTime() + " ticks)";
    }

    /** 锻造台配方。1.21.1 的 SmithingRecipe 只提供 is*Ingredient(stack) 测试，没有材料读取器，
     *  因此无法枚举输入材料，只能描述工作站和结果。 */
    private static String formatSmithing(SmithingRecipe recipe, ItemStack result) {
        return "[smithing] (smithing table: template + base + addition) -> makes " + result.getCount();
    }

    /** 描述材料时，单个物品直接给名称；共享后缀标签写作“任意木板”；否则列出少量成员，避免模型误以为类别材料只接受某一种物品。
     *  对包内可见，合成工具也使用同一套词汇说明材料缺口。 */
    static String describeIngredient(Ingredient ing) {
        List<String> paths = Arrays.stream(ing.getItems())   // 1.21.1：getItems() 返回 ItemStack[]，随后提取候选物品的注册路径。
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            return "?";
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        String suffix = commonSuffixToken(paths);
        if (suffix != null) {
            return suffix + "(any)";
        }
        return "any[" + paths.stream().limit(3).collect(Collectors.joining("/"))
                + (paths.size() > 3 ? "/…" : "") + "]";
    }

    private static String commonSuffixToken(List<String> paths) {
        String token = null;
        for (String p : paths) {
            int u = p.lastIndexOf('_');
            String t = u < 0 ? p : p.substring(u + 1);
            if (token == null) {
                token = t;
            } else if (!token.equals(t)) {
                return null;
            }
        }
        return token;
    }

    // ---- inspect_block_storage：检查方块存储 ----

    public String inspectBlockStorage(int x,
int y,
int z,
                                      LocalPlayer self) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!self.level().isLoaded(pos)) {
            return TaskResult.fail("block at " + x + "," + y + "," + z
                    + " is outside the local client's loaded world; move closer and inspect again.").toJson();
        }
        BlockState state = self.level().getBlockState(pos);
        String coord = x + "," + y + "," + z;
        if (state.isAir()) {
            return TaskResult.fail("block at " + coord + " is air — nothing to read.").toJson();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return TaskResult.ok(id + " at " + coord + ". Generic mod storage contents are not "
                + "authoritative on the client until its menu is synchronized; interact with it "
                + "then use inspect_gui.").toJson();
    }
}
