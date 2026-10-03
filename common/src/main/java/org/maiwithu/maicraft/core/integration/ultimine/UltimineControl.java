// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** 原生持键事务边界：准备完整选区、保持破坏、确认松键，供采矿与通道共用。 */
public interface UltimineControl extends AutoCloseable {
    UltimineSession.Decision prepareSelection(LocalPlayerContext context, BlockHitResult hit,
            Predicate<BlockPos> allowed, Predicate<BlockPos> preserve);
    UltimineSession.Decision tickInFlight(LocalPlayerContext context);
    UltimineSession.Decision finish(LocalPlayerContext context);
    Map<String, Object> holdEvidence();
    void close();
}
