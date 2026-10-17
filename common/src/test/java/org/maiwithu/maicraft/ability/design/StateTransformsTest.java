// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 材料状态随对象变换：原生朝向、门铰链和楼梯状态跟着转；没写的运行态不会被补上；表达不出的拒绝。 */
class StateTransformsTest {

    private static final int[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int[] FLIP_Y = {1, 0, 0, 0, -1, 0, 0, 0, 1};
    private static final int[] PITCH = {1, 0, 0, 0, 0, -1, 0, 1, 0};

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 稀疏属性与默认值() {
        var authored = material("minecraft:oak_stairs", "{\"facing\":\"north\",\"half\":\"bottom\"}");
        String before = authored.toString();
        assertTrue(transform(authored, IDENTITY).equals(authored) && authored.toString().equals(before), "恒等变换原样保留作者的属性，不改输入");
        var rotated = transform(material("minecraft:dispenser", null), horizontal(Mirror.NONE, Rotation.CLOCKWISE_90));
        assertTrue(rotated.getAsJsonObject("properties").keySet().equals(Set.of("facing")) && property(rotated, "facing").equals("east"),
                "转过的默认朝向要写出来，但不冻结 triggered=false");
        var mirroredDoor = transform(material("minecraft:oak_door", null), horizontal(Mirror.FRONT_BACK, Rotation.NONE));
        assertTrue(property(mirroredDoor, "hinge").equals("right") && !mirroredDoor.getAsJsonObject("properties").has("open")
                && !mirroredDoor.getAsJsonObject("properties").has("powered"), "原生默认铰链的变化保留，没提到的运行态仍然缺席");
        var absentConnection = transform(material("minecraft:iron_bars", "{\"north\":\"false\"}"), horizontal(Mirror.NONE, Rotation.CLOCKWISE_90));
        assertTrue(absentConnection.getAsJsonObject("properties").keySet().equals(Set.of("east")) && property(absentConnection, "east").equals("false"),
                "作者写的断开的北面变成断开的东面，不冻结旧的北面");
        assertEquals(material("minecraft:stone", null), transform(material("minecraft:stone", null), FLIP_Y), "无朝向的整块不用发明状态属性");
    }

    @Test
    void 水平的楼梯与门() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Half half : Half.values()) {
                for (StairsShape shape : StairsShape.values()) {
                    BlockState before = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
                    var material = material("minecraft:oak_stairs", "{\"facing\":\"" + facing.getName() + "\",\"half\":\"" + half.getSerializedName()
                            + "\",\"shape\":\"" + shape.getSerializedName() + "\"}");
                    for (Mirror mirror : Mirror.values()) {
                        for (Rotation rotation : Rotation.values()) {
                            JsonObject output = transform(material, horizontal(mirror, rotation));
                            BlockState actual = state(output);
                            assertTrue(actual.getValue(StairBlock.FACING) == rotation.rotate(mirror.mirror(facing)) && actual.getValue(StairBlock.HALF) == half,
                                    "水平变换保留楼梯的上下半并转朝向");
                            // 镜像要翻转相对前进方向的左右缺角；原生 Mirror 的错误组合不能混过。
                            StairsShape expected = mirror == Mirror.NONE ? shape : reflected(shape);
                            assertEquals(expected, actual.getValue(StairBlock.SHAPE), "镜像后的内外角楼梯保留实际缺角");
                            if (mirror == Mirror.NONE) assertEquals(before.rotate(rotation), actual, "纯偏航旋转与原生一致");
                            assertFalse(output.getAsJsonObject("properties").has("waterlogged"), "镜像几何不能把没写的含水状态变成作者要求");
                        }
                    }
                }
            }
        }
        var door = material("minecraft:oak_door", "{\"facing\":\"west\",\"hinge\":\"left\",\"half\":\"upper\",\"open\":\"true\"}");
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                BlockState actual = state(transform(door, horizontal(mirror, rotation)));
                assertEquals(state(door).mirror(mirror).rotate(rotation), actual, "门的朝向、上下半与开合一起变换");
                assertEquals(mirror == Mirror.NONE ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT, actual.getValue(DoorBlock.HINGE), "镜像时门铰链恰好翻一次");
            }
        }
    }

    @Test
    void 连接面随变换() {
        for (String block : List.of("minecraft:iron_bars", "minecraft:cobblestone_wall", "minecraft:redstone_wire")) {
            String values = block.equals("minecraft:iron_bars") ? "{\"north\":\"true\",\"east\":\"false\"}"
                    : block.equals("minecraft:cobblestone_wall") ? "{\"north\":\"low\",\"east\":\"tall\",\"up\":\"false\"}"
                    : "{\"north\":\"side\",\"east\":\"up\"}";
            var input = material(block, values);
            for (Mirror mirror : Mirror.values()) {
                for (Rotation rotation : Rotation.values()) {
                    BlockState expected = state(input).mirror(mirror).rotate(rotation);
                    assertEquals(expected, state(transform(input, horizontal(mirror, rotation))), "连接面属性跟着变换：" + block);
                }
            }
        }
        var pipe = transform(material("minecraft:chorus_plant", "{\"up\":\"true\",\"north\":\"true\",\"west\":\"false\"}"), PITCH);
        assertTrue(property(pipe, "south").equals("true") && property(pipe, "up").equals("true") && property(pipe, "west").equals("false")
                && !pipe.getAsJsonObject("properties").has("north"), "六个连接面可以交换竖直与水平方向，不保留旧的作者键");
    }

    @Test
    void 三维的轴与朝向() {
        assertEquals("z", property(transform(material("minecraft:oak_log", null), PITCH), "axis"), "默认竖直的原木在俯仰后变成水平");
        assertEquals("top", property(transform(material("minecraft:stone_slab", null), FLIP_Y), "type"), "竖直翻转把下半砖变成上半砖");
        assertEquals("double", property(transform(material("minecraft:stone_slab", "{\"type\":\"double\"}"), PITCH), "type"), "双层半砖倾斜后仍可表达");
        var stair = transform(material("minecraft:oak_stairs", "{\"facing\":\"east\",\"half\":\"bottom\",\"shape\":\"inner_left\"}"), FLIP_Y);
        assertTrue(property(stair, "half").equals("top") && property(stair, "shape").equals("inner_left"), "竖直翻转交换楼梯上下半，不变成墙上的楼梯");
        for (int[] matrix : permutations()) {
            for (Direction direction : Direction.values()) {
                var input = material("minecraft:dispenser", "{\"facing\":\"" + direction.getName() + "\"}");
                var output = transform(input, matrix);
                assertTrue(property(output, "facing").equals(mapped(matrix, direction).getName()) && !output.getAsJsonObject("properties").has("triggered"),
                        "四十八种带符号变换都保住六向朝向，不冻结无关默认值");
                assertEquals(state(input), state(transform(output, transpose(matrix))), "六向状态经逆变换回到原样");
            }
            for (Direction.Axis axis : Direction.Axis.values()) {
                var input = material("minecraft:oak_log", "{\"axis\":\"" + axis.getName() + "\"}");
                var output = transform(input, matrix);
                assertEquals(mapped(matrix, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).getAxis().getName(),
                        property(output, "axis"), "轴属性跟着无符号的变换后轴走");
                assertEquals(state(input), state(transform(output, transpose(matrix))), "镜像不会给原木轴发明正负之分");
            }
        }
    }

    @Test
    void 表达不出的状态拒绝并点名() {
        rejects(material("minecraft:oak_stairs", null), PITCH, "minecraft:oak_stairs");
        rejects(material("minecraft:stone_slab", null), PITCH, "minecraft:stone_slab");
        rejects(material("minecraft:oak_door", null), FLIP_Y, "minecraft:oak_door");
        rejects(material("minecraft:furnace", "{\"facing\":\"north\"}"), PITCH, "minecraft:furnace");
        rejects(material("minecraft:rail", null), PITCH, "minecraft:rail");
        rejects(material("minecraft:oak_stairs", "{\"facing\":\"up\"}"), IDENTITY, "facing");
        rejects(material("minecraft:stone", "{\"axis\":\"x\"}"), IDENTITY, "axis");
        rejects(material("missing_mod:unknown", null), IDENTITY, "missing_mod:unknown");
        rejects(material("minecraft:stone", null), new int[]{1, 0, 0, 0, 1, 0}, "矩阵");
        rejects(material("minecraft:stone", null), new int[]{1, 1, 0, 0, 1, 0, 0, 0, 1}, "矩阵");
        rejects(material("minecraft:stone", null), new int[]{2, 0, 0, 0, 1, 0, 0, 0, 1}, "矩阵");
    }

    private static StairsShape reflected(StairsShape shape) {
        return switch (shape) {
            case INNER_LEFT -> StairsShape.INNER_RIGHT;
            case INNER_RIGHT -> StairsShape.INNER_LEFT;
            case OUTER_LEFT -> StairsShape.OUTER_RIGHT;
            case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
            case STRAIGHT -> shape;
        };
    }

    private static int[] horizontal(Mirror mirror, Rotation rotation) {
        int[] result = new int[9];
        Direction[] axes = {Direction.EAST, Direction.UP, Direction.SOUTH};
        for (int column = 0; column < 3; column++) {
            Direction direction = rotation.rotate(mirror.mirror(axes[column]));
            result[column] = direction.getStepX();
            result[3 + column] = direction.getStepY();
            result[6 + column] = direction.getStepZ();
        }
        return result;
    }

    private static List<int[]> permutations() {
        var result = new ArrayList<int[]>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    if (x == y || y == z || x == z) continue;
                    for (int signs = 0; signs < 8; signs++) {
                        int[] matrix = new int[9];
                        matrix[x] = (signs & 1) == 0 ? 1 : -1;
                        matrix[3 + y] = (signs & 2) == 0 ? 1 : -1;
                        matrix[6 + z] = (signs & 4) == 0 ? 1 : -1;
                        result.add(matrix);
                    }
                }
            }
        }
        return result;
    }

    private static Direction mapped(int[] m, Direction d) {
        return Direction.fromDelta(m[0] * d.getStepX() + m[1] * d.getStepY() + m[2] * d.getStepZ(),
                m[3] * d.getStepX() + m[4] * d.getStepY() + m[5] * d.getStepZ(), m[6] * d.getStepX() + m[7] * d.getStepY() + m[8] * d.getStepZ());
    }

    private static int[] transpose(int[] matrix) {
        int[] inverse = new int[9];
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) inverse[row * 3 + column] = matrix[column * 3 + row];
        return inverse;
    }

    private static JsonObject transform(JsonObject material, int[] matrix) {
        return StateTransforms.transform(material, matrix);
    }

    private static JsonObject material(String block, String properties) {
        var material = new JsonObject();
        material.addProperty("block_id", block);
        if (properties != null) material.add("properties", JsonParser.parseString(properties));
        return material;
    }

    private static String property(JsonObject material, String property) {
        return material.getAsJsonObject("properties").get(property).getAsString();
    }

    private static BlockState state(JsonObject material) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(material.get("block_id").getAsString())).defaultBlockState();
        if (material.has("properties")) {
            for (var entry : material.getAsJsonObject("properties").entrySet()) {
                state = value(state, state.getBlock().getStateDefinition().getProperty(entry.getKey()), entry.getValue().getAsString());
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState value(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow());
    }

    private static void rejects(JsonObject material, int[] matrix, String detail) {
        String message = assertThrows(IllegalArgumentException.class, () -> transform(material, matrix), "表达不出的材料被静默接受：" + material).getMessage();
        assertTrue(message.contains(detail), "拒绝理由要点名具体的状态或变换：" + message);
    }
}
