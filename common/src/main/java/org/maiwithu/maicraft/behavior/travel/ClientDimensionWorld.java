// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.menu.EndPoemScreen;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.EndExitPortal;

/**
 * 跨维度现场的生产读端：每刻从角色上下文读维度与脚下，找门交给找活门，
 * 扫黑曜石与末地门框架交给方块扫描服务，出口门按出口门那一份游戏事实读；只读不写，
 * 只有终末之诗交给游戏接口层关掉。
 * 启动时建一份交给跨维度任务；替身测试不经过它。
 */
public final class ClientDimensionWorld implements DimensionWorldView {

    private final Supplier<PlayerContext> context;
    private final PortalFinder portals;
    private final BlockScanService scans;
    /** 登记过扫描目标的世界与方块种类：换目标或换世界时重新登记。 */
    private ClientLevel registeredLevel;
    private Block registeredBlock;

    public ClientDimensionWorld(Supplier<PlayerContext> context, PortalFinder portals, BlockScanService scans) {
        this.context = context;
        this.portals = portals;
        this.scans = scans;
    }

    @Override
    public String currentDimension() {
        PlayerContext current = context.get();
        return current == null || current.level() == null
                ? null : current.level().dimension().location().toString();
    }

    @Override
    public BlockPos feet() {
        PlayerContext current = context.get();
        return current == null || current.level() == null
                ? null : current.localPlayer().blockPosition();
    }

    @Override
    public PortalFinder.Round scanPortals(Block portalBlock, int searchDistance, Set<Long> attemptedCells) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return new PortalFinder.Round(List.of(), true);
        }
        return portals.scan(portalBlock, searchDistance, attemptedCells);
    }

    @Override
    public boolean portalAlive(Block portalBlock, BlockPos cell) {
        PlayerContext current = context.get();
        return current != null && current.level() != null
                && PortalFinder.alive(current.level(), cell, portalBlock);
    }

    @Override
    public boolean touchesPortal(Block portalBlock, Set<Long> portalCells) {
        PlayerContext current = context.get();
        return current != null && current.level() != null
                && WalksIntoPortal.touchesPortal(current.level(),
                        current.localPlayer().blockPosition(), portalCells, portalBlock);
    }

    @Override
    public Optional<BlockPos> stepAsideCell(Set<Long> portalCells) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return Optional.empty();
        }
        return WalksIntoPortal.stepAsideCell(current.level(), portalCells,
                current.localPlayer().blockPosition());
    }

    @Override
    public Optional<BlockState> stateAt(BlockPos cell) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null || !current.level().isLoaded(cell)) {
            return Optional.empty();
        }
        return Optional.of(current.level().getBlockState(cell));
    }

    @Override
    public boolean carries(String itemId) {
        PlayerContext current = context.get();
        if (current == null || current.backpack() == null) {
            return false;
        }
        return current.backpack().stacks().stream().anyMatch(stack -> itemId.equals(stack.itemId()));
    }

    @Override
    public List<BackpackStack> carriedStacks() {
        PlayerContext current = context.get();
        if (current == null || current.backpack() == null) {
            return List.of();
        }
        return List.copyOf(current.backpack().stacks());
    }

    @Override
    public EndExitPortal.Sighting exitPortal() {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            // 不在世界里：看不见门，按没加载答，不冒充"没亮"。
            return new EndExitPortal.Sighting(new BlockPos(EndExitPortal.CENTER_X, 0, EndExitPortal.CENTER_Z),
                    false, false);
        }
        return EndExitPortal.look(current.level(), EndExitPortal.CENTER_X, EndExitPortal.CENTER_Z);
    }

    @Override
    public boolean endPoemShowing() {
        return EndPoemScreen.showing();
    }

    @Override
    public void closeEndPoem() {
        EndPoemScreen.close();
    }

    @Override
    public Scan scanBlocks(Block target, int searchDistance, int want) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) {
            return new Scan(List.of(), true);
        }
        ClientLevel level = current.level();
        ensureRegistered(level, target);
        BlockPos center = current.localPlayer().blockPosition();
        var found = scans.query(level, center, Set.of(target), want,
                Math.max(1, (searchDistance + 15) / 16), 200, Set.of(), false);
        return new Scan(List.copyOf(found.hits()), found.complete());
    }

    private void ensureRegistered(ClientLevel level, Block target) {
        if (registeredLevel == level && registeredBlock == target) {
            return;
        }
        if (registeredLevel != null && registeredBlock != null) {
            scans.unregister(registeredLevel, Set.of(registeredBlock));
        }
        scans.register(level, Set.of(target));
        registeredLevel = level;
        registeredBlock = target;
    }
}
