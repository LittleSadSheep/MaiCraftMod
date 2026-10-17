// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * 一栋建筑的布局：楼层、门、墙上的口子、窗、床、上下楼的地方、设施、屋里能放东西的空地。纯函数，只报事实，
 * 不判这栋房子好不好、能不能住、能不能走通。读现场读到的格、一张图纸编译出来的格都能拿来算，坐标照输入的口径给。
 *
 * <p>几个词的意思：<b>挡路</b>是方块挡身体（墙、地板、关着的门）；<b>能站</b>是脚下是整块或半砖、身体那两格不挡路
 * （站在床、箱子顶上不算这一层的地面）；
 * <b>有顶</b>是头顶往上 8 格内有挡路的方块。没读到的格当作不知道：不算能站，也不算有顶。
 *
 * @param floors     每层能站的地面
 * @param doors      每扇门（按下半给）
 * @param openings   墙上没装门的口子：门洞（一格宽两格高）与窗洞（一格高）
 * @param windows    玻璃、玻璃板、铁栏杆这类窗
 * @param beds       每张床
 * @param climbs     上下楼的地方：楼梯、梯子、藤蔓、脚手架、活板门，连着的一片算一处
 * @param facilities 箱子、工作台、熔炉这类设施
 * @param freeFloor  屋里能放东西的空地：有顶、能站、头上两格都空，连成片的一组
 */
public record BuildingLayout(List<Floor> floors, List<Door> doors, List<Opening> openings, List<BlockPos> windows,
                             List<Bed> beds, List<Climb> climbs, List<Facility> facilities, List<FreeFloor> freeFloor) {

    /** 往上看多高还算有顶。 */
    static final int ROOF_REACH = 8;

    public BuildingLayout {
        floors = List.copyOf(floors);
        doors = List.copyOf(doors);
        openings = List.copyOf(openings);
        windows = List.copyOf(windows);
        beds = List.copyOf(beds);
        climbs = List.copyOf(climbs);
        facilities = List.copyOf(facilities);
        freeFloor = List.copyOf(freeFloor);
    }

    /**
     * 一层地面。
     *
     * @param y          地面方块那一层的高度（人站在 y+1）
     * @param height     这一层有顶处最常见的净高：从脚下那格往上数到顶有几格空
     * @param roofedCells 这一层有顶的能站格数
     */
    public record Floor(int y, int height, int roofedCells) {}

    /**
     * 一扇门。
     *
     * @param at     门的下半
     * @param block  哪种门
     * @param facing 朝向（东南西北）
     * @param hinge  铰链在哪边：左 / 右
     * @param open   开着没
     * @param inside 两侧哪一边有顶（屋里那一侧的方向）；两边都有顶写"两边都有顶"，都没有写"两边都没顶"
     */
    public record Door(BlockPos at, String block, String facing, String hinge, boolean open, String inside) {}

    /**
     * 墙上一处没装门的口子。
     *
     * @param at   口子下面那一格
     * @param kind 门洞（一格宽两格高、脚下能站）或窗洞（一格高、上下都是墙）
     * @param wall 墙的走向：东西向 / 南北向
     */
    public record Opening(BlockPos at, String kind, String wall) {}

    /** 一张床：床脚、床头、朝向（东南西北，从床脚指向床头）。 */
    public record Bed(BlockPos foot, BlockPos head, String facing) {}

    /**
     * 一处上下楼的地方。
     *
     * @param kind   楼梯 / 梯子 / 藤蔓 / 脚手架 / 活板门
     * @param from   这一片的最小角
     * @param to     这一片的最大角
     * @param cells  几格
     * @param floors 挨着的地面层（Floor 的 y），从低到高
     */
    public record Climb(String kind, BlockPos from, BlockPos to, int cells, List<Integer> floors) {
        public Climb {
            floors = List.copyOf(floors);
        }
    }

    /** 一处设施：在哪、哪种方块。 */
    public record Facility(BlockPos at, String block) {}

    /**
     * 屋里一片能放东西的空地。
     *
     * @param from  这一片的最小角（人站的那一层，也就是东西放下去的那一层）
     * @param to    这一片的最大角
     * @param cells 几格
     */
    public record FreeFloor(BlockPos from, BlockPos to, int cells) {}

    /**
     * 算一块范围的布局。
     *
     * @param min      范围的最小角
     * @param max      范围的最大角
     * @param states   一格是什么；只会问 known 说读到了的格
     * @param known    这一格读到了没有
     * @param facility 方块 ID 是不是设施（箱子、工作台、熔炉这类）
     */
    public static BuildingLayout of(BlockPos min, BlockPos max, Function<BlockPos, BlockState> states,
            Predicate<BlockPos> known, Predicate<String> facility) {
        return new Scan(min, max, states, known).layout(facility);
    }

    // 一次计算的现场：范围、读到的格；判断挡路、能站、有顶都在这里。
    private static final class Scan {
        private final BlockPos min;
        private final BlockPos max;
        private final Function<BlockPos, BlockState> states;
        private final Predicate<BlockPos> known;
        private final Map<BlockPos, BlockState> cache = new HashMap<>();

        Scan(BlockPos min, BlockPos max, Function<BlockPos, BlockState> states, Predicate<BlockPos> known) {
            this.min = min.immutable();
            this.max = max.immutable();
            this.states = states;
            this.known = known;
        }

        BuildingLayout layout(Predicate<String> facility) {
            List<Door> doors = new ArrayList<>();
            List<BlockPos> windows = new ArrayList<>();
            List<Bed> beds = new ArrayList<>();
            List<Facility> facilities = new ArrayList<>();
            Map<String, Set<BlockPos>> climbCells = new LinkedHashMap<>();
            Map<Integer, List<BlockPos>> roofedStanding = new TreeMap<>();
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockPos at = pos.immutable();
                BlockState state = state(at);
                if (state == null) continue;
                Block block = state.getBlock();
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                if (block instanceof DoorBlock && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                    doors.add(door(at, state, id));
                }
                if (block instanceof BedBlock && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
                    Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    beds.add(new Bed(at, at.relative(facing), compass(facing)));
                }
                if (block instanceof TransparentBlock || block instanceof IronBarsBlock) windows.add(at);
                String climb = climbKind(block);
                if (climb != null) climbCells.computeIfAbsent(climb, ignored -> new HashSet<>()).add(at);
                if (facility.test(id)) facilities.add(new Facility(at, id));
                // 站在这一格上（它是地面）：脚下挡路、上面两格不挡路，再看头顶有没有顶。
                if (standable(at) && roofed(at.above())) roofedStanding.computeIfAbsent(at.getY(), ignored -> new ArrayList<>()).add(at.above());
            }
            List<Floor> floors = floors(roofedStanding);
            return new BuildingLayout(floors, doors, openings(), windows, beds, climbs(climbCells, floors), facilities,
                    freeFloor(roofedStanding));
        }

        // 一扇门：朝向、铰链、开关照方块状态；门前门后那两格哪边有顶就是屋里那一侧。
        private Door door(BlockPos at, BlockState state, String id) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            boolean front = roofed(at.relative(facing));
            boolean back = roofed(at.relative(facing.getOpposite()));
            String inside = front && back ? "两边都有顶" : front ? compass(facing) : back ? compass(facing.getOpposite()) : "两边都没顶";
            String hinge = state.getValue(BlockStateProperties.DOOR_HINGE) == DoorHingeSide.LEFT ? "左" : "右";
            return new Door(at, id, compass(facing), hinge, state.getValue(BlockStateProperties.OPEN), inside);
        }

        // 每层地面：有顶的能站格按地面高度分组；净高取这一层最常见的那个。
        private List<Floor> floors(Map<Integer, List<BlockPos>> roofedStanding) {
            List<Floor> out = new ArrayList<>();
            roofedStanding.forEach((y, feet) -> {
                Map<Integer, Integer> heights = new TreeMap<>();
                for (BlockPos foot : feet) heights.merge(clearance(foot), 1, Integer::sum);
                int common = heights.entrySet().stream().max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).orElse(0);
                out.add(new Floor(y, common, feet.size()));
            });
            return out;
        }

        // 墙上的口子：这一格不挡路，沿一个方向两边都是墙，沿另一个方向两边都不挡路，至少一侧有顶；装了门的不算。
        private List<Opening> openings() {
            List<Opening> out = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockPos at = pos.immutable();
                BlockState state = state(at);
                if (state == null || blocks(state) || state.getBlock() instanceof DoorBlock) continue;
                for (Direction along : List.of(Direction.EAST, Direction.SOUTH)) {
                    Direction across = along.getClockWise();
                    if (!wall(at.relative(along)) || !wall(at.relative(along.getOpposite()))) continue;
                    if (!open(at.relative(across)) || !open(at.relative(across.getOpposite()))) continue;
                    if (!roofed(at.relative(across)) && !roofed(at.relative(across.getOpposite()))) continue;
                    String wall = along.getAxis() == Direction.Axis.X ? "东西向" : "南北向";
                    BlockState above = state(at.above());
                    BlockState below = state(at.below());
                    boolean doorway = above != null && !blocks(above) && !(above.getBlock() instanceof DoorBlock)
                            && wall(at.above().relative(along)) && wall(at.above().relative(along.getOpposite()))
                            && below != null && blocks(below) && wall(at.above(2));
                    boolean window = wall(at.above()) && wall(at.below());
                    if (doorway) out.add(new Opening(at, "门洞", wall));
                    else if (window) out.add(new Opening(at, "窗洞", wall));
                }
            }
            return out;
        }

        // 上下楼的地方：同一种连着的格算一处，再看它挨着哪几层地面。
        private List<Climb> climbs(Map<String, Set<BlockPos>> cells, List<Floor> floors) {
            List<Climb> out = new ArrayList<>();
            cells.forEach((kind, positions) -> {
                for (List<BlockPos> group : groups(positions, true)) {
                    BlockPos from = corner(group, true);
                    BlockPos to = corner(group, false);
                    List<Integer> touched = floors.stream().map(Floor::y)
                            .filter(y -> y >= from.getY() - 1 && y <= to.getY()).toList();
                    out.add(new Climb(kind, from, to, group.size(), touched));
                }
            });
            return out;
        }

        // 屋里的空地：有顶、能站、头上两格都是空气的格，同一层连成片的一组，大的在前。
        private List<FreeFloor> freeFloor(Map<Integer, List<BlockPos>> roofedStanding) {
            Set<BlockPos> free = new HashSet<>();
            roofedStanding.values().forEach(feet -> feet.stream()
                    .filter(foot -> air(foot) && air(foot.above())).forEach(free::add));
            List<FreeFloor> out = new ArrayList<>();
            for (List<BlockPos> group : groups(free, false)) {
                out.add(new FreeFloor(corner(group, true), corner(group, false), group.size()));
            }
            out.sort(Comparator.comparingInt(FreeFloor::cells).reversed()
                    .thenComparingInt(area -> area.from().getY()).thenComparingLong(area -> area.from().asLong()));
            return out;
        }

        // 连成片：楼梯、梯子这类上下相连也算（六向），空地只算同一层前后左右相连。
        private static List<List<BlockPos>> groups(Set<BlockPos> cells, boolean vertical) {
            List<List<BlockPos>> out = new ArrayList<>();
            Set<BlockPos> left = new HashSet<>(cells);
            List<BlockPos> ordered = new ArrayList<>(cells);
            ordered.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).thenComparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getX()));
            for (BlockPos start : ordered) {
                if (!left.remove(start)) continue;
                List<BlockPos> group = new ArrayList<>();
                Deque<BlockPos> queue = new ArrayDeque<>(List.of(start));
                while (!queue.isEmpty()) {
                    BlockPos at = queue.poll();
                    group.add(at);
                    for (Direction direction : Direction.values()) {
                        if (!vertical && direction.getAxis() == Direction.Axis.Y) continue;
                        // 楼梯一级比一级高：斜着上去也连着，所以竖直相连时把斜上、斜下也算进来。
                        BlockPos next = at.relative(direction);
                        if (left.remove(next)) queue.add(next);
                        if (vertical && direction.getAxis() != Direction.Axis.Y) {
                            for (BlockPos diagonal : List.of(next.above(), next.below())) {
                                if (left.remove(diagonal)) queue.add(diagonal);
                            }
                        }
                    }
                }
                out.add(group);
            }
            return out;
        }

        private static BlockPos corner(List<BlockPos> cells, boolean low) {
            int x = low ? Integer.MAX_VALUE : Integer.MIN_VALUE, y = x, z = x;
            for (BlockPos cell : cells) {
                x = low ? Math.min(x, cell.getX()) : Math.max(x, cell.getX());
                y = low ? Math.min(y, cell.getY()) : Math.max(y, cell.getY());
                z = low ? Math.min(z, cell.getZ()) : Math.max(z, cell.getZ());
            }
            return new BlockPos(x, y, z);
        }

        // 能站在这一格上：它是地面（整块或半砖），上面两格读到了而且不挡路、没有液体。
        // 床、箱子、楼梯也挡路，但站上去不是一层地面，不算。
        private boolean standable(BlockPos floor) {
            BlockState ground = state(floor);
            BlockState feet = state(floor.above());
            BlockState head = state(floor.above(2));
            return ground != null && floorLike(ground) && feet != null && passable(feet) && head != null && passable(head);
        }

        private static boolean floorLike(BlockState state) {
            return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || state.getBlock() instanceof SlabBlock;
        }

        // 有顶：站在 feet 这一格时，头顶（feet+2 起往上 8 格）有挡路的方块。
        private boolean roofed(BlockPos feet) {
            for (int dy = 2; dy < 2 + ROOF_REACH; dy++) {
                BlockState state = state(feet.above(dy));
                if (state != null && blocks(state)) return true;
            }
            return false;
        }

        // 从脚下那格往上数到顶有几格空（最多数到有顶的范围）。
        private int clearance(BlockPos feet) {
            int clear = 0;
            for (int dy = 0; dy < 2 + ROOF_REACH; dy++) {
                BlockState state = state(feet.above(dy));
                if (state == null || blocks(state)) break;
                clear++;
            }
            return clear;
        }

        private boolean wall(BlockPos pos) {
            BlockState state = state(pos);
            return state != null && blocks(state);
        }

        private boolean open(BlockPos pos) {
            BlockState state = state(pos);
            return state != null && passable(state);
        }

        private boolean air(BlockPos pos) {
            BlockState state = state(pos);
            return state != null && state.isAir();
        }

        private static boolean blocks(BlockState state) {
            return state.blocksMotion();
        }

        private static boolean passable(BlockState state) {
            return !state.blocksMotion() && state.getFluidState().isEmpty();
        }

        // 范围里读到了的格才给状态；范围外、没读到的给空，当作不知道。
        private BlockState state(BlockPos pos) {
            if (pos.getX() < min.getX() || pos.getY() < min.getY() || pos.getZ() < min.getZ()
                    || pos.getX() > max.getX() || pos.getY() > max.getY() || pos.getZ() > max.getZ()) {
                return null;
            }
            if (!known.test(pos)) return null;
            return cache.computeIfAbsent(pos.immutable(), states);
        }

        private static String climbKind(Block block) {
            if (block instanceof StairBlock) return "楼梯";
            if (block instanceof LadderBlock) return "梯子";
            if (block instanceof VineBlock) return "藤蔓";
            if (block instanceof ScaffoldingBlock) return "脚手架";
            if (block instanceof TrapDoorBlock) return "活板门";
            return null;
        }

        private static String compass(Direction direction) {
            return switch (direction) {
                case NORTH -> "北";
                case SOUTH -> "南";
                case EAST -> "东";
                case WEST -> "西";
                default -> direction.getName();
            };
        }
    }
}
