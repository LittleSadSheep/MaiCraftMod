// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

/** 用原生方向、门铰链和楼梯状态检查局部材质随组件变换；未写的运行态不能被输出补齐。 */
public final class BuildingModelBlockStatesTest {
    private static final int[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int[] FLIP_Y = {1, 0, 0, 0, -1, 0, 0, 0, 1};
    private static final int[] PITCH = {1, 0, 0, 0, 0, -1, 0, 1, 0};

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        sparsePropertiesAndDefaults(); horizontalStairsAndDoors(); directionalConnections(); threeDimensionalAxesAndFacing(); rejectsUnrepresentableStates();
        System.out.println("BuildingModelBlockStatesTest: native mirrors/rotations, sparse authored properties and bounded 3D representation passed");
    }

    private static void sparsePropertiesAndDefaults() {
        var authored = material("minecraft:oak_stairs", "{\"facing\":\"north\",\"half\":\"bottom\"}");
        String before = authored.toString();
        check(transform(authored, IDENTITY).equals(authored) && authored.toString().equals(before), "identity retains precisely the authored properties and leaves input JSON untouched");
        var rotated = transform(material("minecraft:dispenser", null), horizontal(Mirror.NONE, Rotation.CLOCKWISE_90));
        check(rotated.getAsJsonObject("properties").keySet().equals(Set.of("facing"))
                && property(rotated, "facing").equals("east"), "a rotated default facing must be emitted without freezing triggered=false");
        var mirroredDoor = transform(material("minecraft:oak_door", null), horizontal(Mirror.FRONT_BACK, Rotation.NONE));
        check(property(mirroredDoor, "hinge").equals("right") && !mirroredDoor.getAsJsonObject("properties").has("open")
                && !mirroredDoor.getAsJsonObject("properties").has("powered"), "native default hinge changes are retained while unmentioned operating states remain absent");
        var absentConnection = transform(material("minecraft:iron_bars", "{\"north\":\"false\"}"), horizontal(Mirror.NONE, Rotation.CLOCKWISE_90));
        check(absentConnection.getAsJsonObject("properties").keySet().equals(Set.of("east")) && property(absentConnection, "east").equals("false"),
                "an authored disconnected north side becomes an authored disconnected east side, without freezing the old north side");
        check(transform(material("minecraft:stone", null), FLIP_Y).equals(material("minecraft:stone", null)), "a directionless full cube needs no invented state properties");
    }

    private static void horizontalStairsAndDoors() {
        for (Direction facing : Direction.Plane.HORIZONTAL) for (Half half : Half.values()) for (StairsShape shape : StairsShape.values()) {
            BlockState before = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
            var material = material("minecraft:oak_stairs", "{\"facing\":\"" + facing.getName() + "\",\"half\":\"" + half.getSerializedName()
                    + "\",\"shape\":\"" + shape.getSerializedName() + "\"}");
            for (Mirror mirror : Mirror.values()) for (Rotation rotation : Rotation.values()) {
                JsonObject output = transform(material, horizontal(mirror, rotation));
                BlockState actual = state(output);
                check(actual.getValue(StairBlock.FACING) == rotation.rotate(mirror.mirror(facing)) && actual.getValue(StairBlock.HALF) == half,
                        "horizontal transforms keep the real stair half and transform its facing");
                // 反射必须翻转相对前进方向的左右缺角；直接原生Mirror的错误组合不能混过测试。
                StairsShape expected = mirror == Mirror.NONE ? shape : reflected(shape);
                check(actual.getValue(StairBlock.SHAPE) == expected, "a reflected inner/outer stair must retain its physical missing corner");
                if (mirror == Mirror.NONE) check(actual.equals(before.rotate(rotation)), "pure yaw rotation agrees with the native block owner");
                check(!output.getAsJsonObject("properties").has("waterlogged"), "mirroring geometry cannot turn an unspecified water state into an authored requirement");
            }
        }
        var door = material("minecraft:oak_door", "{\"facing\":\"west\",\"hinge\":\"left\",\"half\":\"upper\",\"open\":\"true\"}");
        for (Mirror mirror : Mirror.values()) for (Rotation rotation : Rotation.values()) {
            BlockState actual = state(transform(door, horizontal(mirror, rotation)));
            check(actual.equals(state(door).mirror(mirror).rotate(rotation)), "doors use the native facing, upper/lower part and open-leaf transformation together");
            check(actual.getValue(DoorBlock.HINGE) == (mirror == Mirror.NONE ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT), "door hinge handedness flips exactly once under reflection");
        }
    }

    private static void directionalConnections() {
        for (String block : List.of("minecraft:iron_bars", "minecraft:cobblestone_wall", "minecraft:redstone_wire")) {
            String values = block.equals("minecraft:iron_bars") ? "{\"north\":\"true\",\"east\":\"false\"}"
                    : block.equals("minecraft:cobblestone_wall") ? "{\"north\":\"low\",\"east\":\"tall\",\"up\":\"false\"}"
                    : "{\"north\":\"side\",\"east\":\"up\"}";
            var input = material(block, values);
            for (Mirror mirror : Mirror.values()) for (Rotation rotation : Rotation.values()) {
                BlockState expected = state(input).mirror(mirror).rotate(rotation);
                check(state(transform(input, horizontal(mirror, rotation))).equals(expected), "native connection-direction properties follow the component transform for " + block);
            }
        }
        var pipe = transform(material("minecraft:chorus_plant", "{\"up\":\"true\",\"north\":\"true\",\"west\":\"false\"}"), PITCH);
        check(property(pipe, "south").equals("true") && property(pipe, "up").equals("true") && property(pipe, "west").equals("false")
                && !pipe.getAsJsonObject("properties").has("north"), "six native connection faces can exchange vertical and horizontal directions without retaining an old authored key");
    }

    private static void threeDimensionalAxesAndFacing() {
        check(property(transform(material("minecraft:oak_log", null), PITCH), "axis").equals("z"), "the default upright log becomes horizontal under pitch");
        check(property(transform(material("minecraft:stone_slab", null), FLIP_Y), "type").equals("top"), "vertical reflection maps a native bottom slab to a native top slab");
        check(property(transform(material("minecraft:stone_slab", "{\"type\":\"double\"}"), PITCH), "type").equals("double"), "the full native double slab stays representable when tilted");
        var stair = transform(material("minecraft:oak_stairs", "{\"facing\":\"east\",\"half\":\"bottom\",\"shape\":\"inner_left\"}"), FLIP_Y);
        check(property(stair, "half").equals("top") && property(stair, "shape").equals("inner_left"), "vertical reflection swaps stair top/bottom without turning it into a wall stair");
        for (int[] matrix : permutations()) {
            for (Direction direction : Direction.values()) {
                var input = material("minecraft:dispenser", "{\"facing\":\"" + direction.getName() + "\"}");
                var output = transform(input, matrix);
                check(property(output, "facing").equals(mapped(matrix, direction).getName()) && !output.getAsJsonObject("properties").has("triggered"),
                        "all 48 signed transforms preserve native six-way facing without fixing unrelated defaults");
                check(state(transform(output, transpose(matrix))).equals(state(input)), "a six-way state survives the inverse component transform");
            }
            for (Direction.Axis axis : Direction.Axis.values()) {
                var input = material("minecraft:oak_log", "{\"axis\":\"" + axis.getName() + "\"}");
                var output = transform(input, matrix);
                check(property(output, "axis").equals(mapped(matrix, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).getAxis().getName()),
                        "axis properties follow the unsigned transformed log axis");
                check(state(transform(output, transpose(matrix))).equals(state(input)), "mirrors do not invent a positive/negative distinction for a log axis");
            }
        }
    }

    private static void rejectsUnrepresentableStates() {
        rejects(material("minecraft:oak_stairs", null), PITCH, "stairs");
        rejects(material("minecraft:stone_slab", null), PITCH, "slab");
        rejects(material("minecraft:oak_door", null), FLIP_Y, "oak_door");
        rejects(material("minecraft:furnace", "{\"facing\":\"north\"}"), PITCH, "furnace");
        rejects(material("minecraft:rail", null), PITCH, "rail");
        rejects(material("minecraft:oak_stairs", "{\"facing\":\"up\"}"), IDENTITY, "facing");
        rejects(material("minecraft:stone", "{\"axis\":\"x\"}"), IDENTITY, "axis");
        rejects(material("missing_mod:unknown", null), IDENTITY, "unknown");
        rejects(material("minecraft:stone", null), new int[]{1, 0, 0, 0, 1, 0}, "matrix");
        rejects(material("minecraft:stone", null), new int[]{1, 1, 0, 0, 1, 0, 0, 0, 1}, "matrix");
        rejects(material("minecraft:stone", null), new int[]{2, 0, 0, 0, 1, 0, 0, 0, 1}, "matrix");
    }
    private static StairsShape reflected(StairsShape shape) {
        return switch (shape) { case INNER_LEFT -> StairsShape.INNER_RIGHT; case INNER_RIGHT -> StairsShape.INNER_LEFT;
            case OUTER_LEFT -> StairsShape.OUTER_RIGHT; case OUTER_RIGHT -> StairsShape.OUTER_LEFT; case STRAIGHT -> shape; };
    }
    private static int[] horizontal(Mirror mirror, Rotation rotation) {
        int[] result = new int[9]; Direction[] axes = {Direction.EAST, Direction.UP, Direction.SOUTH};
        for (int column = 0; column < 3; column++) {
            Direction direction = rotation.rotate(mirror.mirror(axes[column]));
            result[column] = direction.getStepX(); result[3 + column] = direction.getStepY(); result[6 + column] = direction.getStepZ();
        }
        return result;
    }
    private static List<int[]> permutations() {
        var result = new ArrayList<int[]>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
            if (x == y || y == z || x == z) continue;
            for (int signs = 0; signs < 8; signs++) { int[] matrix = new int[9]; matrix[x] = (signs & 1) == 0 ? 1 : -1;
                matrix[3 + y] = (signs & 2) == 0 ? 1 : -1; matrix[6 + z] = (signs & 4) == 0 ? 1 : -1; result.add(matrix); }
        }
        return result;
    }
    private static Direction mapped(int[] m, Direction d) { return Direction.fromDelta(m[0] * d.getStepX() + m[1] * d.getStepY() + m[2] * d.getStepZ(),
            m[3] * d.getStepX() + m[4] * d.getStepY() + m[5] * d.getStepZ(), m[6] * d.getStepX() + m[7] * d.getStepY() + m[8] * d.getStepZ()); }
    private static int[] transpose(int[] matrix) { int[] inverse = new int[9]; for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) inverse[row * 3 + column] = matrix[column * 3 + row]; return inverse; }
    private static JsonObject transform(JsonObject material, int[] matrix) { return BuildingModelBlockStates.transform(material, matrix); }
    private static JsonObject material(String block, String properties) { var material = new JsonObject(); material.addProperty("block_id", block);
        if (properties != null) material.add("properties", JsonParser.parseString(properties)); return material; }
    private static String property(JsonObject material, String property) { return material.getAsJsonObject("properties").get(property).getAsString(); }
    private static BlockState state(JsonObject material) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(material.get("block_id").getAsString())).defaultBlockState();
        if (material.has("properties")) for (var entry : material.getAsJsonObject("properties").entrySet())
            state = value(state, state.getBlock().getStateDefinition().getProperty(entry.getKey()), entry.getValue().getAsString());
        return state;
    }
    private static <T extends Comparable<T>> BlockState value(BlockState state, Property<T> property, String value) { return state.setValue(property, property.getValue(value).orElseThrow()); }
    private static void rejects(JsonObject material, int[] matrix, String detail) {
        try { transform(material, matrix); } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(detail), "rejection should identify its concrete unsupported state or transform: " + expected.getMessage()); return;
        }
        throw new AssertionError("unrepresentable local material was silently accepted: " + material);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
