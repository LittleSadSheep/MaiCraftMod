// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 呼梯的无线链路：触点旁哪些接收端让触点通电（紧挨的、贴在紧挨触点的实心方块上的），哪些发射端同频又在无线距离内，
 * 遥控器哪个键能叫；频率按机械动力的认法写（物品 + 染色，空格为空）。
 */
class ElevatorCallsTest {

    private static final BlockPos CONTACT = new BlockPos(0, 64, 0);
    private static final List<String> IRON_GOLD = List.of("minecraft:iron_ingot#-1", "minecraft:gold_ingot#-1");
    private static final List<String> IRON_COAL = List.of("minecraft:iron_ingot#-1", "minecraft:coal#-1");

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 假世界：放了的格是那种方块，其余是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static ElevatorCalls.Links links(ElevatorCalls.Link... placed) {
        Map<BlockPos, ElevatorCalls.Link> byPos = new HashMap<>();
        for (ElevatorCalls.Link link : placed) byPos.put(link.at(), link);
        return pos -> Optional.ofNullable(byPos.get(pos));
    }

    private static ElevatorCalls.Link receiver(BlockPos at, List<String> frequency, Direction facing) {
        return new ElevatorCalls.Link(at, frequency, true, facing);
    }

    private static ElevatorCalls.Link transmitter(BlockPos at, List<String> frequency) {
        return new ElevatorCalls.Link(at, frequency, false, Direction.UP);
    }

    @Test
    void 紧挨触点的接收端让触点通电_发射端不算() {
        ElevatorCalls.Link beside = receiver(CONTACT.east(), IRON_GOLD, Direction.EAST);
        ElevatorCalls.Link sender = transmitter(CONTACT.west(), IRON_GOLD);

        List<ElevatorCalls.Link> found = ElevatorCalls.receiversPowering(new World(), CONTACT, links(beside, sender));

        assertEquals(List.of(beside), found);
    }

    @Test
    void 贴在紧挨触点的实心方块上的接收端才算_贴在别处或隔着玻璃的不算() {
        World world = new World();
        BlockPos stone = CONTACT.north();
        world.blocks.put(stone, Blocks.STONE.defaultBlockState());
        BlockPos glass = CONTACT.south();
        world.blocks.put(glass, Blocks.GLASS.defaultBlockState());
        // 贴在石头上（朝外，背后就是石头）：强充能石头，石头给触点通电。
        ElevatorCalls.Link onStone = receiver(stone.north(), IRON_GOLD, Direction.NORTH);
        // 挨着石头却贴在别的格上（朝石头）：只弱充能石头，传不到触点。
        ElevatorCalls.Link facingStone = receiver(stone.east(), IRON_COAL, Direction.WEST);
        // 贴在玻璃上：玻璃不导红石。
        ElevatorCalls.Link onGlass = receiver(glass.south(), IRON_COAL, Direction.SOUTH);

        List<ElevatorCalls.Link> found = ElevatorCalls.receiversPowering(world, CONTACT, links(onStone, facingStone, onGlass));

        assertEquals(List.of(onStone), found);
    }

    @Test
    void 同频又在无线距离内的发射端才算() {
        List<ElevatorCalls.Link> receivers = List.of(receiver(CONTACT.east(), IRON_GOLD, Direction.EAST));
        ElevatorCalls.Link near = transmitter(new BlockPos(5, 64, 5), IRON_GOLD);
        ElevatorCalls.Link otherFrequency = transmitter(new BlockPos(3, 64, 3), IRON_COAL);
        ElevatorCalls.Link tooFar = transmitter(new BlockPos(300, 64, 0), IRON_GOLD);
        ElevatorCalls.Link anotherReceiver = receiver(new BlockPos(2, 64, 2), IRON_GOLD, Direction.UP);

        List<ElevatorCalls.Link> found = ElevatorCalls.transmittersFor(receivers,
                List.of(near, otherFrequency, tooFar, anotherReceiver), 256);

        assertEquals(List.of(near), found);
    }

    @Test
    void 遥控器同频的键能叫_空键不算_离接收端太远不算() {
        List<ElevatorCalls.Link> receivers = List.of(receiver(CONTACT.east(), IRON_GOLD, Direction.EAST));
        List<List<String>> keys = List.of(List.of("", ""), IRON_COAL, IRON_GOLD, List.of("", ""), List.of("", ""), List.of("", ""));

        assertEquals(2, ElevatorCalls.remoteKey(keys, receivers, new BlockPos(10, 64, 0), 256));
        assertEquals(-1, ElevatorCalls.remoteKey(keys, receivers, new BlockPos(400, 64, 0), 256), "遥控器从角色脚下发信号");
        List<ElevatorCalls.Link> blank = List.of(receiver(CONTACT.east(), List.of("", ""), Direction.EAST));
        assertEquals(-1, ElevatorCalls.remoteKey(keys, blank, new BlockPos(10, 64, 0), 256), "没绑东西的键不算能叫");
    }

    @Test
    void 频率按物品与染色写_空格为空串() {
        assertEquals("", ElevatorCalls.frequencyKey(ItemStack.EMPTY));
        assertEquals("minecraft:iron_ingot#-1", ElevatorCalls.frequencyKey(new ItemStack(Items.IRON_INGOT, 3)),
                "件数不算频率");
        assertEquals("minecraft:iron_ingot + 空", ElevatorCalls.describe(List.of("minecraft:iron_ingot#-1", "")));
    }

    @Test
    void 发射端旁的按钮和触点旁的按钮同一套认法() {
        // 按钮贴在发射端背后的石头上：按下去石头强充能，发射端收到信号。
        World world = new World();
        BlockPos sender = new BlockPos(5, 64, 5);
        BlockPos stone = sender.west();
        world.blocks.put(stone, Blocks.STONE.defaultBlockState());
        BlockPos button = stone.north();
        world.blocks.put(button, Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(ButtonBlock.FACING, Direction.NORTH));

        assertTrue(ElevatorFloors.callButtons(world, sender).contains(button));
    }
}
