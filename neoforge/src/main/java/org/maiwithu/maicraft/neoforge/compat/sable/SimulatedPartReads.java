// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.sable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterEntries;
import dev.simulated_team.simulated.network.packets.AssemblePacket;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterDisconnectUser;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterKeyInteractionPacket;
import net.minecraft.client.multiplayer.ClientLevel;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import org.maiwithu.maicraft.compat.sable.SimulatedParts;

/**
 * Simulated 载具部件的读写端：拉物理组装器的杆发 Simulated 自己的组装包（和玩家把杆拉到底松手时发的一样）；
 * 无线红石打字机读结构地块里的方块实体（绑了哪些键、谁在用），按键、松键、交还发它自己的包——
 * 和真人接管无线红石打字机后按键盘、按 Esc 时发的一样。只翻译，不判断。
 */
public final class SimulatedPartReads implements SimulatedParts {

    @Override public void pullAssembler(BlockPos at) {
        PacketDistributor.sendToServer(new AssemblePacket(at));
    }

    @Override public Set<Integer> typewriterKeys(ClientLevel level, String structureId, BlockPos local) {
        LinkedTypewriterBlockEntity typewriter = typewriter(level, structureId, local);
        return typewriter == null ? null : Set.copyOf(typewriter.getTypewriterEntries().getKeyMap().keySet());
    }

    @Override public List<KeyLink> typewriterLinks(ClientLevel level, String structureId, BlockPos local,
            List<BlockPos> receivers) {
        LinkedTypewriterBlockEntity typewriter = typewriter(level, structureId, local);
        if (typewriter == null) return null;
        // 收端候选各自听哪一对频率：机械动力的链路切到发送的不算收端。
        Map<BlockPos, Couple<RedstoneLinkNetworkHandler.Frequency>> listening = new LinkedHashMap<>();
        for (BlockPos at : receivers) {
            LevelChunk chunk = SableStructureReads.plotChunk(level, structureId, at);
            BlockEntity entity = chunk == null ? null : chunk.getBlockEntity(at);
            LinkBehaviour link = entity == null ? null : BlockEntityBehaviour.get(entity, LinkBehaviour.TYPE);
            if (link == null) continue;
            BlockState state = chunk.getBlockState(at);
            if (state.hasProperty(RedstoneLinkBlock.RECEIVER) && !state.getValue(RedstoneLinkBlock.RECEIVER)) continue;
            listening.put(at.immutable(), link.getNetworkKey());
        }
        List<KeyLink> links = new ArrayList<>();
        for (Map.Entry<Integer, LinkedTypewriterEntries.KeyboardEntry> entry
                : typewriter.getTypewriterEntries().getKeyMap().entrySet()) {
            Couple<RedstoneLinkNetworkHandler.Frequency> key = entry.getValue().getNetworkKey();
            List<BlockPos> hearing = listening.entrySet().stream()
                    .filter(heard -> heard.getValue().getFirst().equals(key.getFirst())
                            && heard.getValue().getSecond().equals(key.getSecond()))
                    .map(Map.Entry::getKey).toList();
            links.add(new KeyLink(entry.getKey(), id(key.getFirst()), id(key.getSecond()), hearing));
        }
        return links;
    }

    @Override public boolean typewriterUsedBy(ClientLevel level, String structureId, BlockPos local, UUID player) {
        LinkedTypewriterBlockEntity typewriter = typewriter(level, structureId, local);
        return typewriter != null && typewriter.checkUser(player);
    }

    @Override public void typewriterKey(BlockPos local, int key, boolean press) {
        // 真人按键时扫描码只给本地按键映射用，服务器按键码找频率；这里给 0。
        PacketDistributor.sendToServer(new TypewriterKeyInteractionPacket(local, key, 0,
                press ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE));
    }

    @Override public void typewriterDisconnect(ClientLevel level, String structureId, BlockPos local) {
        // 和按 Esc 一样：客户端先放下（不再拦本机的按键），再告诉服务器断开。
        LinkedTypewriterBlockEntity typewriter = typewriter(level, structureId, local);
        if (typewriter != null) typewriter.disconnectUser();
        PacketDistributor.sendToServer(new TypewriterDisconnectUser(local));
    }

    private static String id(RedstoneLinkNetworkHandler.Frequency frequency) {
        return BuiltInRegistries.ITEM.getKey(frequency.getStack().getItem()).toString();
    }

    private static LinkedTypewriterBlockEntity typewriter(ClientLevel level, String structureId, BlockPos local) {
        LevelChunk chunk = SableStructureReads.plotChunk(level, structureId, local);
        return chunk != null && chunk.getBlockEntity(local) instanceof LinkedTypewriterBlockEntity typewriter
                ? typewriter : null;
    }
}
