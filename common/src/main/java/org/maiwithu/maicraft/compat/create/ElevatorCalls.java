// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 把电梯叫到某一层的办法（呼梯点），近的在前：
 * <ol>
 *   <li>紧挨触点、按下去让触点通电的按钮；</li>
 *   <li>无线链路：触点旁有无线红石接收端（紧挨触点，或贴在紧挨触点的能导红石的方块上），角色 32 格内有同频的发射端，
 *       按发射端旁的按钮——发射端与接收端要在机械动力的无线距离内；</li>
 *   <li>身上遥控器有个键绑的频率和那个接收端一样：不用走，站着按（离接收端也要在无线距离内）。</li>
 * </ol>
 * 按钮离得近（5 格内）先按按钮，远了先用遥控器。频率按机械动力的认法比：两格各自是同一种物品、染色一样。
 * 只读世界与身上的东西，不按；读到的接收端、发射端与遥控器都写进说明，叫不来时照实说看了什么。
 */
final class ElevatorCalls {

    /** 找发射端的范围（格，从角色算）与最多看多少个方块实体。 */
    static final int RADIUS = 32;
    static final int BLOCK_ENTITY_BUDGET = 4096;
    /** 按钮在这么近（格）以内就先走过去按，远了先用身上的遥控器。 */
    static final double EASY_REACH = 5;
    static final String LINK = "create:redstone_link";
    /** 遥控器六个键的名字（和 vehicle_drive 试按遥控器的写法一样）。 */
    static final List<String> REMOTE_KEYS = List.of("上", "下", "左", "右", "跳", "潜行");

    private ElevatorCalls() {}

    /**
     * 一种呼梯办法。
     *
     * @param button          走过去按的按钮；用遥控器时为 null
     * @param remoteKey       遥控器的第几个键（0 上、1 下、2 左、3 右、4 跳、5 潜行）；按按钮时为 -1
     * @param remoteFrequency 那个键该绑的频率（两格）；按按钮时为空
     * @param how             怎么叫的一句话
     */
    record Call(BlockPos button, int remoteKey, List<String> remoteFrequency, String how) {
        Call {
            remoteFrequency = List.copyOf(remoteFrequency);
        }

        boolean remote() {
            return button == null;
        }

        /** 认这个办法的键：同一个按钮、同一个遥控器键只算一种。 */
        String key() {
            return remote() ? "遥控器:" + remoteKey : button.toShortString();
        }
    }

    /**
     * 一个无线红石端。
     *
     * @param at        在哪
     * @param frequency 两个频率格，各写成 {@link #frequencyKey} 的样子
     * @param receiver  是接收端
     * @param facing    朝哪（它贴在反方向那一格上）
     */
    record Link(BlockPos at, List<String> frequency, boolean receiver, Direction facing) {
        Link {
            frequency = List.copyOf(frequency);
        }
    }

    /** 某一格上的无线红石端；不是就为空。 */
    interface Links {
        Optional<Link> at(BlockPos pos);
    }

    /** 找到的呼梯办法，以及看了什么（叫不来时照它说）。 */
    record Found(List<Call> calls, String looked) {
        Found {
            calls = List.copyOf(calls);
        }
    }

    /** 这一层能怎么把电梯叫过来；{@code floorName} 只用来写说明。 */
    static Found find(CreateCompat compat, ClientLevel level, LocalPlayer player, BlockPos contact, String floorName) {
        BlockPos feet = player.blockPosition();
        Links links = links(compat, level);
        List<Call> buttons = new ArrayList<>();
        for (BlockPos button : ElevatorFloors.callButtons(level, contact)) {
            buttons.add(new Call(button, -1, List.of(), "按触点旁的呼梯按钮 " + button.toShortString()));
        }
        List<Link> receivers = receiversPowering(level, contact, links);
        List<String> looked = new ArrayList<>();
        looked.add(buttons.isEmpty() ? "触点旁没有呼梯按钮" : "触点旁有 " + buttons.size() + " 个呼梯按钮");
        Call remote = null;
        if (receivers.isEmpty()) {
            looked.add("触点旁也没有无线红石接收端");
        } else {
            int range = compat.linkRange();
            looked.add("触点旁有无线红石接收端（频率 " + receivers.stream().map(link -> describe(link.frequency()))
                    .distinct().collect(Collectors.joining("、")) + "）");
            Scan scan = linksNear(level, feet, links);
            List<Link> transmitters = transmittersFor(receivers, scan.links(), range);
            int linked = 0;
            for (Link transmitter : transmitters) {
                for (BlockPos button : ElevatorFloors.callButtons(level, transmitter.at())) {
                    if (buttons.stream().anyMatch(call -> button.equals(call.button()))) continue;
                    buttons.add(new Call(button, -1, List.of(), "按无线发射端 " + transmitter.at().toShortString()
                            + " 旁的按钮 " + button.toShortString() + "（和触点旁的接收端同频）"));
                    linked++;
                }
            }
            looked.add(transmitters.isEmpty() ? RADIUS + " 格内没有同频、在无线距离（" + range + " 格）内的发射端"
                    : RADIUS + " 格内有 " + transmitters.size() + " 个同频的发射端，旁边能按的按钮 " + linked + " 个");
            if (scan.truncated()) looked.add("附近方块实体太多，只看了最近的 " + BLOCK_ENTITY_BUDGET + " 个");
            List<ItemStack> remotes = remotes(player);
            for (ItemStack stack : remotes) {
                List<List<String>> keys = keysOf(compat, stack);
                int key = remoteKey(keys, receivers, feet, range);
                if (key < 0) continue;
                remote = new Call(null, key, keys.get(key), "用身上遥控器的「" + REMOTE_KEYS.get(key)
                        + "」键（和触点旁的接收端同频）");
                break;
            }
            if (remote == null) {
                looked.add(remotes.isEmpty() ? "身上没有遥控器" : "身上的遥控器没有绑这个频率的键（或离接收端超过 " + range + " 格）");
            }
        }
        buttons.sort(Comparator.comparingDouble(call -> call.button().distSqr(feet)));
        List<Call> calls = new ArrayList<>(buttons);
        if (remote != null) {
            // 按钮就在手边先按按钮；远了先用遥控器，不用走。
            boolean near = !buttons.isEmpty() && Math.sqrt(buttons.getFirst().button().distSqr(feet)) <= EASY_REACH;
            calls.add(near ? 1 : 0, remote);
        }
        return new Found(calls, "这一层（" + floorName + "）" + String.join("，", looked));
    }

    /**
     * 让触点通电的无线接收端：紧挨触点的（接收端对四周都弱充能），或贴在紧挨触点、能导红石的方块上的
     * （接收端让贴着的那格强充能，那格再给触点通电）。
     */
    static List<Link> receiversPowering(BlockGetter level, BlockPos contact, Links links) {
        List<Link> found = new ArrayList<>();
        for (Direction side : Direction.values()) {
            BlockPos next = contact.relative(side);
            Optional<Link> beside = links.at(next);
            if (beside.isPresent()) {
                if (beside.get().receiver() && !found.contains(beside.get())) found.add(beside.get());
                continue;
            }
            if (!level.getBlockState(next).isRedstoneConductor(level, next)) continue;
            for (Direction around : Direction.values()) {
                BlockPos candidate = next.relative(around);
                if (candidate.equals(contact)) continue;
                links.at(candidate).filter(Link::receiver)
                        .filter(link -> candidate.relative(link.facing().getOpposite()).equals(next))
                        .filter(link -> !found.contains(link))
                        .ifPresent(found::add);
            }
        }
        return found;
    }

    /** 和这些接收端里任何一个同频、又在机械动力无线距离内的发射端，保持原来的先后（离角色由近到远）。 */
    static List<Link> transmittersFor(List<Link> receivers, List<Link> candidates, int range) {
        return candidates.stream().filter(link -> !link.receiver())
                .filter(link -> receivers.stream().anyMatch(receiver -> receiver.frequency().equals(link.frequency())
                        && link.at().closerThan(receiver.at(), range)))
                .toList();
    }

    /**
     * 遥控器哪个键能叫：那个键绑的频率和某个接收端一样，角色（遥控器按下去时从角色脚下发信号）离那个接收端在无线距离内。
     * 两格都没放东西的键不算。没有为 -1。
     */
    static int remoteKey(List<List<String>> keys, List<Link> receivers, BlockPos feet, int range) {
        for (int key = 0; key < keys.size(); key++) {
            List<String> frequency = keys.get(key);
            if (frequency.stream().allMatch(String::isEmpty)) continue;
            boolean reaches = receivers.stream().anyMatch(receiver -> receiver.frequency().equals(frequency)
                    && feet.closerThan(receiver.at(), range));
            if (reaches) return key;
        }
        return -1;
    }

    /** 一个频率格写成"物品#染色"（没染色为 -1）；空格为空串。机械动力认频率只看这两样。 */
    static String frequencyKey(ItemStack stack) {
        if (stack.isEmpty()) return "";
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "#" + (dyed == null ? -1 : dyed.rgb());
    }

    /** 频率的一句话：两格的物品，染了色的带上颜色，空格写"空"。 */
    static String describe(List<String> frequency) {
        return frequency.stream().map(key -> {
            if (key.isEmpty()) return "空";
            int mark = key.lastIndexOf('#');
            String color = key.substring(mark + 1);
            return key.substring(0, mark) + (color.equals("-1") ? "" : "（染色 " + color + "）");
        }).collect(Collectors.joining(" + "));
    }

    /** 这个遥控器每个键绑的频率。 */
    static List<List<String>> keysOf(CreateCompat compat, ItemStack remote) {
        return compat.remoteFrequencies(remote).stream()
                .map(slots -> slots.stream().map(ElevatorCalls::frequencyKey).toList())
                .toList();
    }

    // 身上的遥控器：主手、副手、背包里的都算，主手的在前。
    private static List<ItemStack> remotes(LocalPlayer player) {
        List<ItemStack> found = new ArrayList<>();
        List<ItemStack> all = new ArrayList<>();
        all.add(player.getMainHandItem());
        all.add(player.getOffhandItem());
        all.addAll(player.getInventory().items);
        for (ItemStack stack : all) {
            if (isRemote(stack) && found.stream().noneMatch(seen -> seen == stack)) found.add(stack);
        }
        return found;
    }

    static boolean isRemote(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(RemoteButtons.REMOTE);
    }

    // 世界里的无线红石端：方块是无线红石终端才去读频率与收发。
    private static Links links(CreateCompat compat, ClientLevel level) {
        return pos -> {
            BlockState state = level.getBlockState(pos);
            if (!LINK.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) return Optional.empty();
            Direction facing = state.hasProperty(BlockStateProperties.FACING)
                    ? state.getValue(BlockStateProperties.FACING) : Direction.UP;
            return compat.link(level, pos).map(reading -> new Link(pos,
                    reading.frequencies().stream().map(ElevatorCalls::frequencyKey).toList(), reading.receiver(), facing));
        };
    }

    /** 角色附近的无线红石端，以及是不是因为方块实体太多没看完。 */
    private record Scan(List<Link> links, boolean truncated) {}

    // 从角色所在区块一圈圈往外看已加载的区块，32 格内的无线红石端都读出来；方块实体最多看 4096 个。
    private static Scan linksNear(ClientLevel level, BlockPos center, Links links) {
        List<Link> found = new ArrayList<>();
        int visited = 0;
        boolean truncated = false;
        int rings = (RADIUS >> 4) + 1;
        scan:
        for (int ring = 0; ring <= rings; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow((center.getX() >> 4) + dx, (center.getZ() >> 4) + dz);
                    if (chunk == null) continue;
                    for (BlockPos pos : List.copyOf(chunk.getBlockEntities().keySet())) {
                        if (++visited > BLOCK_ENTITY_BUDGET) {
                            truncated = true;
                            break scan;
                        }
                        if (pos.distSqr(center) > (double) RADIUS * RADIUS) continue;
                        links.at(pos).ifPresent(found::add);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(link -> link.at().distSqr(center)));
        return new Scan(found, truncated);
    }
}
