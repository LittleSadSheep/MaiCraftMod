// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采集的来源：只收熟了的作物，收哪几格由许可说了算；挖的动作走挖方块的执行接缝。
 */
class HarvestSourceTest {

    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    /** 替身：固定一批熟了的作物。 */
    private record FakeCrops(List<CropSpot> spots) implements ScansMatureCrops {
        @Override public List<CropSpot> mature(WantedItem wanted, WorldPosition center, int radiusBlocks) {
            return spots;
        }
    }

    /** 替身：挖一格记一格，当场做完；接不上时返回 empty。 */
    private static final class FakeDigs implements CollectsBlocks {
        final List<BlockPos> dug = new ArrayList<>();
        private boolean wired = true;

        FakeDigs wired(boolean wired) {
            this.wired = wired;
            return this;
        }

        @Override public Optional<Action> collect(BlockPos target, Permissions permissions) {
            if (!wired) return Optional.empty();
            dug.add(target);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "收一格";
                }
            });
        }
    }

    private HarvestSource source(ScansMatureCrops crops, CollectsBlocks digs) {
        // 真许可检查点加全空的替身保护：本场景里没有任何受保护的东西。
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<ReadsCreatureSituation.CreatureSituation> situationOf(
                            UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new HarvestSource(crops, digs, check);
    }

    @Test
    void 附近没有熟作物_如实回答给不了() {
        SourceQuote quote = source(new FakeCrops(List.of()), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:wheat"), 3, "烹饪原料"), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("熟了"));
    }

    @Test
    void 有熟作物_按格子数报价_并按由近及远挖() {
        FakeDigs digs = new FakeDigs();
        // 扫描接缝约定由近及远：替身按这个顺序给。
        HarvestSource harvest = source(new FakeCrops(List.of(
                new CropSpot(new BlockPos(2, 64, 0), "minecraft:wheat"),
                new CropSpot(new BlockPos(5, 64, 0), "minecraft:wheat"))), digs);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                harvest.quote(new ItemRequest(WantedItem.ofItem("minecraft:wheat"), 2, "烹饪原料"), CONTEXT));
        assertEquals(2, offer.obtainableCount());
        assertEquals(2.0, offer.cost().distanceBlocks(), 0.001);
        assertTrue(offer.risk().contains("不一定掉一件"));

        harvest.begin(new ItemRequest(WantedItem.ofItem("minecraft:wheat"), 2, "烹饪原料"),
                offer, CONTEXT).orElseThrow();
        assertEquals(2, digs.dug.size());
        assertEquals(new BlockPos(2, 64, 0), digs.dug.getFirst());
    }

    @Test
    void 挖的入口没接上_交回空由引擎换路() {
        assertTrue(source(new FakeCrops(List.of(new CropSpot(new BlockPos(2, 64, 0), "minecraft:wheat"))),
                new FakeDigs().wired(false))
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:wheat"), 1, "烹饪原料"),
                        new SourceQuote.Offer("收熟作物", 1, new AcquisitionCost(2, 1), null), CONTEXT)
                .isEmpty());
    }

    @Test
    void 补种在收完捡完之后才问_按收掉的作物认种子() {
        FakeDigs digs = new FakeDigs();
        List<String> asked = new ArrayList<>();
        ReplantsCrops replants = (spot, cropType) -> {
            asked.add(cropType + "@" + digs.dug.size());
            return Optional.empty();
        };
        HarvestSource harvest = new HarvestSource(new FakeCrops(List.of(
                new CropSpot(new BlockPos(2, 64, 0), "minecraft:carrots"))), digs, permissionCheck(), replants);
        Action action = harvest.begin(new ItemRequest(WantedItem.ofItem("minecraft:carrot"), 1, "烹饪原料"),
                new SourceQuote.Offer("收庄稼", 1, new AcquisitionCost(2, 1), null), CONTEXT).orElseThrow();
        assertTrue(asked.isEmpty(), "动手前不该问补种：那时格子还没空、种子也还没进包");
        while (!(action.tick(null) instanceof ActionStatus.Done)) {
            // 一步步推进到做完。
        }
        assertEquals(List.of("minecraft:carrots@1"), asked);
    }

    private static PermissionCheck permissionCheck() {
        return new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"),
                entityId -> Optional.empty());
    }
}
