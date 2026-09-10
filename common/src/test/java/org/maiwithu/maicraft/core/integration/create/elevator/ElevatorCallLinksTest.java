package org.maiwithu.maicraft.core.integration.create.elevator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.create.elevator.CreateElevatorBridge.WorldLink;

/**
 * 用给定频率和按钮方块检查有序频率、染色差别、收发模式、距离许可和实际供电方向；不发送真实无线控制。
 */
public final class ElevatorCallLinksTest {
    private static final BlockPos BUTTON = new BlockPos(-74, 116, -12), SUPPORT = new BlockPos(-73, 116, -12);
    private static final BlockPos TRANSMITTER = new BlockPos(-73, 116, -13), RECEIVER = new BlockPos(-67, 115, -12);
    private static final BlockPos CONTACT = new BlockPos(-68, 115, -12);
    private static final List<String> FREQUENCY = List.of("minecraft:iron_ingot#-1", "minecraft:copper_ingot#-1");
    private static final List<Map<String, Object>> ITEMS = List.of(Map.of("item", "minecraft:iron_ingot", "dyed_color", -1),
            Map.of("item", "minecraft:copper_ingot", "dyed_color", -1));

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var transmitter = link(TRANSMITTER, false, FREQUENCY);
        var receiver = link(RECEIVER, true, FREQUENCY);
        check(ElevatorCallLinks.matches(transmitter, receiver, true), "matching native link keys were not associated");
        check(!ElevatorCallLinks.matches(transmitter, link(RECEIVER, true, List.of("other", "channel")), true), "different frequencies matched");
        check(!ElevatorCallLinks.matches(transmitter, link(RECEIVER, false, FREQUENCY), true), "transmitter was mistaken for floor receiver");
        check(!ElevatorCallLinks.matches(link(TRANSMITTER, true, FREQUENCY), receiver, true), "receiver was mistaken for button-fed transmitter");
        check(!ElevatorCallLinks.matches(transmitter, receiver, false), "native out-of-range result was ignored");
        check(!ElevatorCallLinks.matches(transmitter, link(RECEIVER, true, FREQUENCY.reversed()), true), "ordered frequency slots were swapped");
        check(!ElevatorCallLinks.matches(transmitter, link(RECEIVER, true,
                List.of("minecraft:iron_ingot#16711680", "minecraft:copper_ingot#-1")), true), "native dyed-color distinction was discarded");

        Scene world = new Scene();
        var buttonState = Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        world.blocks.put(BUTTON, buttonState);
        world.blocks.put(SUPPORT, Blocks.STONE.defaultBlockState());
        check(ElevatorCallLinks.buttonCandidates(TRANSMITTER).size() == 24, "button neighborhood exceeded its fixed electrical adjacency bound");
        check(ElevatorCallLinks.buttonCandidates(TRANSMITTER).contains(BUTTON), "actual button behind a shared support was omitted");
        check(ElevatorCallLinks.buttonFeeds(world, TRANSMITTER, BUTTON), "wall button did not strongly power the transmitter's neighbor");
        check(ElevatorSurvey.feeds(world, CONTACT, RECEIVER, Blocks.OBSERVER.defaultBlockState(), false), "adjacent receiver did not feed contact");
        check(!ElevatorSurvey.feeds(world, CONTACT, BUTTON, buttonState, true), "distant wireless button was wrongly treated as directly wired");
        world.blocks.put(SUPPORT, Blocks.GLASS.defaultBlockState());
        check(!ElevatorCallLinks.buttonFeeds(world, TRANSMITTER, BUTTON), "non-conducting support was accepted");
        world.blocks.put(SUPPORT, Blocks.STONE.defaultBlockState());
        world.blocks.put(BUTTON, buttonState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        check(!ElevatorCallLinks.buttonFeeds(world, TRANSMITTER, BUTTON), "wrong wall attachment powered a remote support");
        world.blocks.put(BUTTON, Blocks.STONE.defaultBlockState());
        check(!ElevatorCallLinks.buttonFeeds(world, TRANSMITTER, BUTTON), "removed button remained callable");
        BlockPos direct = CONTACT.above(); world.blocks.put(direct, buttonState);
        check(ElevatorCallLinks.buttonFeeds(world, CONTACT, direct), "ordinary direct button support regressed");

        var wireless = new ElevatorSurvey.CallInput(BUTTON, Vec3.ZERO, -1, -1, TRANSMITTER, RECEIVER, ITEMS);
        check(!wireless.remote() && wireless.evidence().get("source").equals("world_redstone_link_button"), "world input was turned into handheld protocol");
        check(wireless.evidence().get("frequency_items").equals(ITEMS) && wireless.evidence().containsKey("transmitter")
                && wireless.evidence().containsKey("receiver"), "MCP call evidence omitted frequency or endpoints");
        check(new ElevatorSurvey.CallInput(BUTTON, Vec3.ZERO, -1, -1).evidence().get("source").equals("direct_button"), "legacy button constructor changed semantics");
        check(new ElevatorSurvey.CallInput(RECEIVER, Vec3.ZERO, 7, 2).remote(), "handheld linked controller channel changed semantics");
        System.out.println("ElevatorCallLinksTest: passed");
    }

    private static WorldLink link(BlockPos pos, boolean receiver, Object frequency) {
        return new WorldLink(pos, new Object(), receiver, frequency, ITEMS);
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static final class Scene implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        public int getHeight() { return 384; }
        public int getMinBuildHeight() { return -64; }
    }
}
