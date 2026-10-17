// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction.spi;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 一次连锁挖按着的连锁键：挖第一下之前问准备好没有（键按下去、形状切好、模组把会一起挖的格发回来、观众看清了轮廓），
 * 挖的时候每刻续按，触发格挖掉、副格的更新到齐后松开。一次连锁挖用一个，用完就扔。
 */
public interface ChainKeyHold {

    /**
     * 工具拿好、准星已经对准触发格的那一面之后，每刻问一次，直到给出 {@link Readiness.Chain} 或 {@link Readiness.Single}。
     * 第一次问时记下触发格与命中面；之后准星换了格或换了面就不连锁。
     */
    Readiness ready(PlayerContext context, BlockHitResult hit);

    /** 挖的过程中每刻续按；键被别的事松掉了（形状被换、手里的工具换了）时为 false，调用方停手、改成只挖这一格。 */
    boolean keep(PlayerContext context);

    /** 挖完或停手：松开连锁键；模组那边已经按松开处理了为 true，在那之前不开始下一次挖，免得下一下又带着连锁。 */
    boolean release(PlayerContext context);

    /** 第一下之前的答复。 */
    sealed interface Readiness {

        /** 再等等：键刚按下、形状在切、模组的预览还没到、让观众看清轮廓。 */
        record Wait(String why) implements Readiness {}

        /** 可以挖了：按着键挖触发格会一起挖掉这一批（含触发格）。 */
        record Chain(List<BlockPos> batch) implements Readiness {
            public Chain {
                batch = List.copyOf(batch);
            }
        }

        /** 这次不连锁，连锁键已经松开、模组也按松开处理了：只挖触发格这一格。 */
        record Single(String why) implements Readiness {}
    }
}
