// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.BlockStateRules;

/**
 * 读现场：定一块范围，把范围里看得见的、自己放的方块读出来，画成一张层图。纯判断，只经只读接缝读世界。
 *
 * <p>范围有三种来法：指着一栋建筑档案就读档案的范围；给了 radius 就读以目标为中心的方盒；都没有就从目标那一格起
 * 自动框——沿"不是天然地形"的方块连通扩开，框住整栋，再往下带一层地面。三条边都不能超过 32 格。
 *
 * <p>读出来的格：空气、一放就顶掉的草不声明；看得见或自己放的方块照实记（摆放属性带上：朝向、上下半、铰链、
 * 床的哪一头、轴向、半砖类型这些，开关、充能、含水、连接这些运行态不带）；其余记"没看到"。
 */
final class SiteCapture {

    /** 一块范围的三条边最多几格。 */
    static final int MAX_SPAN = 32;
    /** 层图对象的名字：读现场读出来的那一张。 */
    static final String OBJECT_NAME = "现场";
    /** 自动框找第一块搭出来的方块时，从目标往外找几格。 */
    private static final int SEED_REACH = 2;
    /** 随方块放下时由放法定下、要照着验收的属性；连接、形状、开关这些不带。 */
    private static final Set<String> PLACEMENT_PROPERTIES = Set.of("facing", "half", "hinge", "part", "axis", "face",
            "rotation", "attachment", "hanging", "orientation");

    private SiteCapture() {}

    /**
     * 读到的现场。
     *
     * @param min      范围的最小角
     * @param max      范围的最大角
     * @param blocks   写进图纸的方块：位置 → 归一过的方块状态（只有看得见或自己放的）
     * @param raw      知道的非空气格的原样状态，算布局用（门开着没这类运行态也要看；一放就顶掉的草也在这里）
     * @param air      加载着的空气格（也算知道）
     * @param hidden   范围里有东西却没看到（看不见、不是自己放的）或没加载的格
     */
    record Read(BlockPos min, BlockPos max, Map<BlockPos, BlockState> blocks, Map<BlockPos, BlockState> raw,
                Set<BlockPos> air, Set<BlockPos> hidden) {

        /** 没看到的格数。 */
        int unseen() {
            return hidden.size();
        }

        /** 这一格知不知道：读到了方块，或是加载着的空气。 */
        boolean known(BlockPos pos) {
            return raw.containsKey(pos) || air.contains(pos);
        }

        /** 算布局用的状态：读到的照原样，空气格给空气。 */
        BlockState state(BlockPos pos) {
            BlockState state = raw.get(pos);
            return state != null ? state : Blocks.AIR.defaultBlockState();
        }
    }

    /** 以目标为中心、边长 2×radius+1 的方盒。 */
    static Blueprint.Bounds box(BlockPos center, int radius) {
        if (radius < 0 || 2 * radius + 1 > MAX_SPAN) throw DesignFormat.bad("radius 要在 0.." + (MAX_SPAN - 1) / 2 + " 之间：方盒的边不能超过 " + MAX_SPAN + " 格");
        return new Blueprint.Bounds(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
    }

    /**
     * 从目标那一格起自动框：先在两格内找一块搭出来的方块，再沿相邻（含斜着挨着）的搭出来的方块连通扩开，
     * 框住整栋，再往下带一层地面；有一条边超过 32 格就停下来报，写明框到了多大。
     */
    static Blueprint.Bounds frame(BlockPos start, ReadsSiteCells site) {
        BlockPos seed = seed(start, site);
        if (seed == null) {
            throw DesignFormat.bad("目标 " + start.toShortString() + " 周围 " + SEED_REACH + " 格内没看到搭出来的方块；指着一面墙、一扇门，或给 radius 读一个方盒");
        }
        int minX = seed.getX(), minY = seed.getY(), minZ = seed.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
        Set<BlockPos> visited = new HashSet<>(List.of(seed));
        Deque<BlockPos> queue = new ArrayDeque<>(List.of(seed));
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            minX = Math.min(minX, at.getX());
            minY = Math.min(minY, at.getY());
            minZ = Math.min(minZ, at.getZ());
            maxX = Math.max(maxX, at.getX());
            maxY = Math.max(maxY, at.getY());
            maxZ = Math.max(maxZ, at.getZ());
            if (maxX - minX + 1 > MAX_SPAN || maxY - minY + 1 > MAX_SPAN || maxZ - minZ + 1 > MAX_SPAN) {
                throw DesignFormat.bad("从 " + seed.toShortString() + " 沿搭出来的方块框到 " + (maxX - minX + 1) + "×" + (maxY - minY + 1) + "×"
                        + (maxZ - minZ + 1) + " 还没到头，超过 " + MAX_SPAN + " 格；给 radius 改读一个方盒，或指着更小的一栋");
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = at.offset(dx, dy, dz);
                        if (visited.contains(next) || !built(next, site)) continue;
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        // 再往下带一层地面：房子坐在哪一层地上也画出来，改地面、清地面时对得上。
        return new Blueprint.Bounds(new BlockPos(minX, minY - 1, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    // 从目标起两格内最近的一块搭出来的方块；目标自己就是的话就是它。
    private static BlockPos seed(BlockPos start, ReadsSiteCells site) {
        if (built(start, site)) return start;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(start.offset(-SEED_REACH, -SEED_REACH, -SEED_REACH), start.offset(SEED_REACH, SEED_REACH, SEED_REACH))) {
            if (!built(pos, site)) continue;
            double distance = pos.distSqr(start);
            if (distance < bestDistance) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    // 搭出来的方块：加载着，而且不是天然地形。
    private static boolean built(BlockPos pos, ReadsSiteCells site) {
        return site.loaded(pos) && !site.natural(pos);
    }

    /** 读范围里的每一格：空气与一放就顶掉的不声明，看得见或自己放的照实记，其余算没看到。 */
    static Read read(Blueprint.Bounds range, ReadsSiteCells site) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<BlockPos, BlockState> raw = new LinkedHashMap<>();
        Set<BlockPos> air = new HashSet<>();
        Set<BlockPos> hidden = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(range.min(), range.max())) {
            BlockPos at = pos.immutable();
            if (!site.loaded(at)) {
                hidden.add(at);
                continue;
            }
            BlockState state = site.state(at);
            if (state.isAir()) {
                air.add(at);
                continue;
            }
            // 流动的水、一放就顶掉的草这类不是"放上去的东西"，不声明，只给布局知道那里能走；水源照实记（施工会倒桶）。
            if (state.canBeReplaced() && (state.getFluidState().isEmpty() || !state.getFluidState().isSource())) {
                raw.put(at, state);
                continue;
            }
            if (!site.selfPlaced(at) && !site.visible(at)) {
                hidden.add(at);
                continue;
            }
            raw.put(at, state);
            blocks.put(at, placementState(state));
        }
        return new Read(range.min(), range.max(), blocks, raw, air, hidden);
    }

    // 读出来写进图纸的状态：先按施工的归一规则把运行态去掉，摆放属性留着当验收标准。
    private static BlockState placementState(BlockState state) {
        return BlockStateRules.normalize(state);
    }

    // 哪些属性写进材料：放法定下的那几样；半砖的上下片、双层也是放法定下的。
    private static boolean writes(BlockState state, Property<?> property) {
        return PLACEMENT_PROPERTIES.contains(property.getName())
                || property.getName().equals("type") && state.getBlock() instanceof SlabBlock;
    }

    /**
     * 把读到的现场画成一张图纸：材料表每种状态一项（名字是方块 ID 加摆放属性），一个层图对象一层一张字符图。
     *
     * @param read   读到的现场
     * @param name   图纸名
     * @param origin 设计原点落在的那一格：层图对象的 location 是范围最小角减去它
     */
    static JsonObject drawing(Read read, String name, BlockPos origin) {
        // 用得多的状态先分字符：墙、地板拿到最好认的大写字母。
        Map<String, JsonObject> states = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<BlockPos, String> materialAt = new LinkedHashMap<>();
        for (var entry : read.blocks().entrySet()) {
            JsonObject state = stateJson(entry.getValue());
            String key = state.toString();
            states.putIfAbsent(key, state);
            counts.merge(key, 1, Integer::sum);
            materialAt.put(entry.getKey(), key);
        }
        if (states.isEmpty()) throw DesignFormat.bad("这片范围里一格方块都没看到；走近、走进屋里再读，或指着一面墙");
        if (states.size() > Layers.SYMBOLS.length()) {
            throw DesignFormat.bad("这片范围里有 " + states.size() + " 种方块状态，超过一张层图的 " + Layers.SYMBOLS.length() + " 种；给 radius 分几块读");
        }
        List<String> byUse = states.keySet().stream().sorted(Comparator.comparing((String key) -> -counts.get(key))).toList();
        JsonObject materials = new JsonObject();
        JsonObject legend = new JsonObject();
        Map<String, Character> symbols = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < byUse.size(); i++) {
            JsonObject state = states.get(byUse.get(i));
            String material = materialName(state, names);
            materials.add(material, state);
            char symbol = Layers.SYMBOLS.charAt(i);
            legend.addProperty(String.valueOf(symbol), material);
            symbols.put(byUse.get(i), symbol);
        }
        JsonArray layers = new JsonArray();
        BlockPos min = read.min();
        BlockPos max = read.max();
        for (int y = min.getY(); y <= max.getY(); y++) {
            JsonArray rows = new JsonArray();
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                StringBuilder row = new StringBuilder();
                for (int x = min.getX(); x <= max.getX(); x++) {
                    BlockPos at = new BlockPos(x, y, z);
                    String key = materialAt.get(at);
                    if (key != null) row.append(symbols.get(key));
                    else row.append(read.hidden().contains(at) ? Layers.NOT_SEEN : Layers.NOT_DECLARED);
                }
                rows.add(row.toString());
            }
            JsonObject layer = new JsonObject();
            layer.addProperty("y", y - min.getY());
            layer.add("rows", rows);
            layers.add(layer);
        }
        JsonObject object = new JsonObject();
        object.addProperty("name", OBJECT_NAME);
        object.addProperty("type", "LAYERS");
        JsonArray location = new JsonArray();
        location.add(min.getX() - origin.getX());
        location.add(min.getY() - origin.getY());
        location.add(min.getZ() - origin.getZ());
        object.add("location", location);
        object.add("legend", legend);
        object.add("layers", layers);
        JsonArray objects = new JsonArray();
        objects.add(object);
        JsonObject drawing = new JsonObject();
        drawing.addProperty("name", name);
        drawing.addProperty("coordinate_system", "minecraft_y_up");
        drawing.add("materials", materials);
        drawing.add("objects", objects);
        return drawing;
    }

    // 一种方块状态写成材料：block_id 加写进去的摆放属性。
    private static JsonObject stateJson(BlockState state) {
        JsonObject json = new JsonObject();
        json.addProperty("block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            if (writes(state, property)) sorted.put(property.getName(), valueName(state, property));
        }
        sorted.forEach(properties::addProperty);
        if (!properties.isEmpty()) json.add("properties", properties);
        return json;
    }

    // 材料名：方块 ID 去掉命名空间，加上方括号里的摆放属性；超过 64 个字符或重名时改成"方块#序号"。
    private static String materialName(JsonObject state, Set<String> taken) {
        String id = state.get("block_id").getAsString();
        String base = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        String name = base;
        if (state.has("properties")) {
            StringBuilder text = new StringBuilder(base).append('[');
            boolean first = true;
            for (var entry : state.getAsJsonObject("properties").entrySet()) {
                if (!first) text.append(',');
                text.append(entry.getKey()).append('=').append(entry.getValue().getAsString());
                first = false;
            }
            name = text.append(']').toString();
        }
        if (name.length() > 64 || name.contains("/") || taken.contains(name)) {
            String shortBase = base.length() > 56 ? base.substring(0, 56) : base;
            int n = 1;
            while (taken.contains(shortBase + "#" + n)) n++;
            name = shortBase + "#" + n;
        }
        taken.add(name);
        return name;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
