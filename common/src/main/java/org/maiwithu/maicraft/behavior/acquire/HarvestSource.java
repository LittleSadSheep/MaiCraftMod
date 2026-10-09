// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;

/**
 * 采集的来源：收成熟作物。只收熟了的（没熟的被踩掉就白长了），收哪几格由许可说了算——
 * 别人田里的不碰。一格收完不一定掉一件，报价里如实写上这个变数；挖的动作本身走挖方块的执行接缝。
 */
public final class HarvestSource implements ItemSource {

    /** 找作物的半径：再远就不算田边顺手收，宁可去别处拿。 */
    public static final int SEARCH_RADIUS_BLOCKS = 48;

    private final ScansMatureCrops crops;
    private final CollectsBlocks collects;
    private final PermissionCheck permission;
    /** 收完顺手补种的接缝；没接上时只收不补。 */
    private final ReplantsCrops replants;

    public HarvestSource(ScansMatureCrops crops, CollectsBlocks collects, PermissionCheck permission) {
        this(crops, collects, permission, null);
    }

    public HarvestSource(ScansMatureCrops crops, CollectsBlocks collects, PermissionCheck permission,
            ReplantsCrops replants) {
        this.crops = crops;
        this.collects = collects;
        this.permission = permission;
        this.replants = replants;
    }

    @Override public String describe() {
        return "收熟作物";
    }

    @Override public AcquireVia via() {
        return AcquireVia.HARVEST;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<CropSpot> spots = crops.mature(request.wanted(), context.characterAt(), SEARCH_RADIUS_BLOCKS);
        if (spots.isEmpty()) {
            return new SourceQuote.Unavailable(describe(),
                    "附近没有熟了可以收的" + request.wanted().describe());
        }
        PermittedSpots.Screen<CropSpot> screened = PermittedSpots.screen(spots, CropSpot::pos,
                CropSpot::blockType, context.permissions(), permission);
        if (screened.allowed().isEmpty()) {
            return new SourceQuote.NeedsApproval(describe(), screened.firstRefusal());
        }
        return new SourceQuote.Offer(describe(), screened.allowed().size(),
                new AcquisitionCost(distanceToNearest(screened.allowed(), context), screened.allowed().size()),
                "一格不一定掉一件，收完以实际捡到的为准");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 动手前重新扫一遍：问价到动手之间庄稼可能被收走了，以现为准。
        List<CropSpot> spots = crops.mature(request.wanted(), context.characterAt(), SEARCH_RADIUS_BLOCKS);
        PermittedSpots.Screen<CropSpot> screened = PermittedSpots.screen(spots, CropSpot::pos,
                CropSpot::blockType, context.permissions(), permission);
        if (screened.allowed().isEmpty()) {
            return Optional.empty();
        }
        List<Action> steps = new ArrayList<>();
        // 一株至少收一件：要几件就收几株，不把附近的田一口气收光；收少了引擎清点后会再来。
        for (CropSpot spot : screened.allowed().stream().limit(request.count()).toList()) {
            Optional<Action> collect = collects.collect(spot.pos(), context.permissions());
            if (collect.isEmpty()) {
                // 收的入口接不上，一步都还没动：整串放弃，由引擎换别的路。
                return Optional.empty();
            }
            steps.add(collect.get());
            if (replants != null) {
                // 收完、掉落物进了包再顺手把种子种回去：种子从这次收获里出，收割前格子也还没空出来。
                steps.add(new ReplantAfterwards(spot.pos(), spot.blockType()));
            }
        }
        return Optional.of(new StepwiseActions("把熟了的" + request.wanted().describe()
                + "收进背包", steps.toArray(Action[]::new)));
    }

    private double distanceToNearest(List<CropSpot> spots, SourceContext context) {
        var here = context.characterAt();
        double nearest = Double.MAX_VALUE;
        for (CropSpot spot : spots) {
            BlockPos pos = spot.pos();
            double d = Math.sqrt(Math.pow(pos.getX() - here.x(), 2)
                    + Math.pow(pos.getY() - here.y(), 2)
                    + Math.pow(pos.getZ() - here.z(), 2));
            nearest = Math.min(nearest, d);
        }
        return nearest;
    }

    /** 到这一步才问补种：前面的收割与捡掉落物做完了，种子在包里、格子也空出来了。 */
    private final class ReplantAfterwards implements Action {

        private final BlockPos spot;
        private final String cropType;
        private Action replant;
        private boolean asked;

        ReplantAfterwards(BlockPos spot, String cropType) {
            this.spot = spot;
            this.cropType = cropType;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (!asked) {
                asked = true;
                replant = replants.replant(spot, cropType).orElse(null);
            }
            // 这一株补不了（不是种在耕地上的那类、格子被占了）：跳过，不让已收的白收。
            return replant == null ? ActionStatus.done() : replant.tick(context);
        }

        @Override public void pause() {
            if (replant != null) replant.pause();
        }

        @Override public void close() {
            if (replant != null) replant.close();
        }

        @Override public String describe() {
            return replant == null ? "补种 " + spot.toShortString() : replant.describe();
        }
    }
}
