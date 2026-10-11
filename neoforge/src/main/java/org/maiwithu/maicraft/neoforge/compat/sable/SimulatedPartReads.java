// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.sable;

import java.util.Set;
import java.util.UUID;

import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;
import dev.simulated_team.simulated.network.packets.AssemblePacket;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterDisconnectUser;
import dev.simulated_team.simulated.network.packets.linked_typewriter.TypewriterKeyInteractionPacket;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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

    private static LinkedTypewriterBlockEntity typewriter(ClientLevel level, String structureId, BlockPos local) {
        LevelChunk chunk = SableStructureReads.plotChunk(level, structureId, local);
        return chunk != null && chunk.getBlockEntity(local) instanceof LinkedTypewriterBlockEntity typewriter
                ? typewriter : null;
    }
}
