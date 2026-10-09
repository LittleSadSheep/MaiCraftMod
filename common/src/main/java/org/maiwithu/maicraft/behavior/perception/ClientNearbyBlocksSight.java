// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近功能方块的读端：在已登记的方块扫描上查角色附近看得见的设施方块。
 *
 * <p>复用游戏接口层的 {@code BlockScanService}：设施的种类（容器、工作设施、床）首次用到时
 * 向扫描服务登记一次，之后每次查询走索引，一刻只花小预算；扫完之前返回已找到的部分，
 * "没扫完"不冒充"没有"。查到的每一格再用与观察同一套遮挡判断筛一遍——
 * 墙后的箱子不报成看见。索引登记的方块种类以设施分类为限，感知只认设施。
 */
public final class ClientNearbyBlocksSight implements NearbyBlocksSight {

    // 观察要的是"附近有什么"，不是最近的一个：给够数量，超出由场景按远近取舍。
    private static final int WANT = 32;
    private static final int MAX_CHUNK_RADIUS = 2;
    private static final int BUILD_BUDGET = 4096;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private final FacilityKinds kinds;
    /** 设施种类与这次要看的方块按维度登记进扫描索引：没登记的方块索引里查不到。 */
    private final ScanTargets registered;
    /** 感知常驻关心的设施种类（容器、工作站、床）；第一次用到时按标签与注册表找齐。 */
    private Set<Block> facilities;

    public ClientNearbyBlocksSight(BlockScanService scans, Supplier<PlayerContext> context, FacilityKinds kinds) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public List<BlockSighting> nearby(Set<String> blockTypes) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null
                || blockTypes.isEmpty()) {
            return List.of();
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        Collection<Block> targets = blocksOf(blockTypes);
        if (targets.isEmpty()) {
            return List.of();
        }
        // 设施种类常驻登记；这次要看的方块不在其中时一并补登记，否则索引里查不到。
        registered.ensure(level, facilities());
        registered.ensure(level, targets);
        var result = scans.query(level, player.blockPosition(), targets,
                WANT, MAX_CHUNK_RADIUS, BUILD_BUDGET);
        List<BlockSighting> sightings = new ArrayList<>();
        for (BlockPos hit : result.hits()) {
            // 视线被挡住的功能方块不是看得见的设施；等角色走近、看得见了再报。
            if (!ObservationVisibility.block(player, hit)) {
                continue;
            }
            String typeId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit).getBlock()).toString();
            sightings.add(new BlockSighting(
                    WorldPosition.here(hit.getX(), hit.getY(), hit.getZ()), typeId));
        }
        return sightings;
    }

    // 感知常驻关心的设施种类：第一次用到时找齐，之后复用。
    private Set<Block> facilities() {
        if (this.facilities != null) {
            return this.facilities;
        }
        Set<Block> facilities = new LinkedHashSet<>();
        for (String typeId : kinds.containerBlockTypes()) {
            blockOf(typeId).ifPresent(facilities::add);
        }
        for (String typeId : kinds.workstationBlockTypes()) {
            blockOf(typeId).ifPresent(facilities::add);
        }
        // 床有十六种染色，按注册表按名找齐；名字以外的新床出现时也会在下次登记前进来。
        for (Block block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_bed")) {
                facilities.add(block);
            }
        }
        this.facilities = Set.copyOf(facilities);
        return this.facilities;
    }

    // 调用方给的是方块类型注册 ID 字符串；认得的换成方块对象，认不得的忽略。
    private static Collection<Block> blocksOf(Set<String> blockTypes) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String typeId : blockTypes) {
            blockOf(typeId).ifPresent(blocks::add);
        }
        return blocks;
    }

    private static Optional<Block> blockOf(String typeId) {
        return Optional.ofNullable(
                BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(typeId.toLowerCase(Locale.ROOT))));
    }
}
