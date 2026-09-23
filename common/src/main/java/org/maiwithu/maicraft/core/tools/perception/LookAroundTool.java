package org.maiwithu.maicraft.core.tools.perception;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.pathing.cache.LoadedOnlyView;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 自我中心的空间视图：将同伴周围方块呈现为角色中心字符网格（俯视、每格对应一个方块、北向朝上），而不是扁平坐标列表。
 * 每个格子都按移动类型和同伴当前高度带的垂直通行能力归类：平地、上台阶、下台阶、落差、墙、水或熔岩；
 * 这样模型可以直接读图理解地形、障碍、缺口和可跳越台阶，无需逐格探测。
 *
 * <p>此表示方式参考 Gao 等人的自我中心语义网格研究："Exploring Spatial Representation to Enhance LLM Reasoning in Aerial Vision-Language Navigation"（arXiv:2410.08500）。
 * 将多个高度切片合并为一个移动符号的垂直通行能力编码，以及危险区域“膨胀”缓冲区，参考自动驾驶导航中的占据栅格和分层代价地图做法（例如 Occ3D、ROS Nav2 costmap_2d）。
 * 稀疏的远距离对象由 {@code scan_blocks} 和 {@code scan_nearby_entities} 查询；本工具专注于密集的近场地图。
 */
public final class LookAroundTool implements MaiCraftTool {

    private static final int DEFAULT_RADIUS = 8;
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 16;
    /** 地面低于脚位多少格后，该格才显示为落差。 */
    private static final int DROP_DEPTH = 3;

    // 地图格子符号。
    private static final char YOU = '@';
    private static final char FLAT = '.';       // walkable, same level
    private static final char STEP_UP = '^';    // walkable by a 1-block jump up
    private static final char STEP_DOWN = ',';  // walkable, 1-2 blocks down
    private static final char DROP = 'v';        // drop of DROP_DEPTH+ blocks
    private static final char WALL = '#';        // blocked / step up >= 2
    private static final char WATER = '~';
    private static final char HAZARD = '!';      // lava / fire
    private static final char CAUTION = 'x';     // inflation buffer next to a hazard
    private static final char TREE = 'T';
    private static final char UNLOADED = '?';

    @Override
    public String name() {
        return "look_around";
    }

    /** 常驻:空间视图是她的眼睛。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return "Your spatial view: a top-down character map of the blocks around you, centred on "
                + "yourself. `@` is you at the middle, North is up, East is right, each cell is one block. "
                + "Each cell encodes how you could move onto it, collapsing height into one symbol: "
                + "`.` flat walkable, `^` step up 1 (jumpable), `,` step down 1-2, `v` drop of 3+ (pit/cliff), "
                + "`#` wall/blocked, `~` water, `!` lava/hazard, `x` caution (next to a hazard), `T` tree, "
                + "`?` not loaded. Call this ONCE to grasp terrain, walls, ledges, water and gaps around you "
                + "instead of many inspect_block calls. Express the destination to goto; do not manually "
                + "issue one movement step per cell. "
                + "For far-away or specific blocks/entities use scan_blocks / scan_nearby_entities. "
                + "Optional `radius` (4-16, default 8).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("radius", "Half-width of the square view in blocks (4-16, default 8).",
                        MIN_RADIUS, MAX_RADIUS)
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        int radius = DEFAULT_RADIUS;
        if (args != null && args.has("radius") && args.get("radius").isJsonPrimitive()) {
            radius = Math.clamp(args.get("radius").getAsInt(), MIN_RADIUS, MAX_RADIUS);
        }
        reply.accept(render(self, radius));
    }

    private static String render(LocalPlayer self, int radius) {
        BlockGetter view = LoadedOnlyView.of(self.level());
        LoadedOnlyView loaded = view instanceof LoadedOnlyView v ? v : null;
        BlockPos center = PlayerNav.playerFeet(self);
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        int size = 2 * radius + 1;
        char[][] grid = new char[size][size];
        for (int r = 0; r < size; r++) {
            int dz = r - radius;                 // r=0 is north (-Z), top
            for (int c = 0; c < size; c++) {
                int dx = c - radius;             // c=0 is west (-X), left
                grid[r][c] = (dx == 0 && dz == 0)
                        ? YOU
                        : classify(view, loaded, cx + dx, cy, cz + dz);
            }
        }
        inflateHazards(grid, size);

        StringBuilder sb = new StringBuilder();
        sb.append("look_around center=(").append(cx).append(',').append(cy).append(',').append(cz)
                .append(") facing=").append(self.getDirection().getName())
                .append(" | 1 cell = 1 block, @ = you, North = up (-Z), East = right (+X)\n\n");
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                sb.append(grid[r][c]);
                if (c < size - 1) {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        sb.append("\nlegend: @ you | . flat | ^ step-up 1 | , step-down 1-2 | v drop>=").append(DROP_DEPTH)
                .append(" | # wall/blocked | ~ water | ! lava/hazard | x caution | T tree | ? unloaded\n")
                .append("route facts: . ^ , are locally walkable; # ~ ! v x block or endanger a route. "
                        + "Give goto the goal and let the runtime execute the path.\n");
        return sb.toString();
    }

    /** 将 (x,z) 柱列汇总为一个位于同伴当前 Y 高度带的语义移动能力符号。 */
    private static char classify(BlockGetter view, LoadedOnlyView loaded, int x, int feetY, int z) {
        if (loaded != null && !loaded.isLoaded(x, z)) {
            return UNLOADED;
        }
        BlockState feetState = view.getBlockState(new BlockPos(x, feetY, z));
        BlockState headState = view.getBlockState(new BlockPos(x, feetY + 1, z));

        if (MovementHelper.isLava(feetState) || MovementHelper.isLava(headState)) {
            return HAZARD;
        }
        if (feetState.getBlock() instanceof LiquidBlock || headState.getBlock() instanceof LiquidBlock) {
            return WATER;
        }

        // 在可跳上或短距离下落范围内，选择角色能够站立的最高表面。
        Integer standY = null;
        for (int y = feetY + 1; y >= feetY - DROP_DEPTH; y--) {
            if (canStandAt(view, x, y, z)) {
                standY = y;
                break;
            }
        }
        if (standY == null) {
            boolean bodyClear = MovementHelper.fullyPassable(view, new BlockPos(x, feetY, z))
                    && MovementHelper.fullyPassable(view, new BlockPos(x, feetY + 1, z));
            if (!bodyClear) {
                return (isTree(feetState) || isTree(headState)) ? TREE : WALL;
            }
            return DROP; // body clear but no floor within reach -> open pit/void
        }
        int delta = standY - feetY;
        if (delta >= 2) {
            return WALL;
        }
        if (delta == 1) {
            return STEP_UP;
        }
        if (delta == 0) {
            return FLAT;
        }
        if (delta >= -2) {
            return STEP_DOWN;
        }
        return DROP;
    }

    private static boolean canStandAt(BlockGetter view, int x, int y, int z) {
        return MovementHelper.canWalkOn(view, new BlockPos(x, y - 1, z))
                && MovementHelper.fullyPassable(view, new BlockPos(x, y, z))
                && MovementHelper.fullyPassable(view, new BlockPos(x, y + 1, z));
    }

    /** 按分层代价地图方式在熔岩和火焰周围加入谨慎缓冲区，让模型与危险边缘保持距离。 */
    private static void inflateHazards(char[][] grid, int size) {
        boolean[][] near = new boolean[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (grid[r][c] == HAZARD) {
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int nr = r + dr;
                            int nc = c + dc;
                            if (nr >= 0 && nr < size && nc >= 0 && nc < size) {
                                near[nr][nc] = true;
                            }
                        }
                    }
                }
            }
        }
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (near[r][c] && isWalkable(grid[r][c])) {
                    grid[r][c] = CAUTION;
                }
            }
        }
    }

    private static boolean isWalkable(char c) {
        return c == FLAT || c == STEP_UP || c == STEP_DOWN;
    }

    private static boolean isTree(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }
}
