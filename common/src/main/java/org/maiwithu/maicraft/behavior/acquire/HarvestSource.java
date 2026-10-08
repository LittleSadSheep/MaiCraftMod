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

/**
 * 采集的来源：收成熟作物。只收熟了的（没熟的被踩掉就白长了），收哪几格由许可说了算——
 * 别人田里的不碰。一格收完不一定掉一件，报价里如实写上这个变数；挖的动作本身走挖方块的执行接缝。
 */
public final class HarvestSource implements ItemSource {

    /** 找作物的半径：再远就不算田边顺手收，宁可去别处拿。 */
    public static final int SEARCH_RADIUS_BLOCKS = 48;

    private final ScansMatureCrops crops;
    private final DigsBlocks digs;
    private final PermissionCheck permission;
    /** 收完顺手补种的接缝；没接上时只收不补。 */
    private final ReplantsCrops replants;

    public HarvestSource(ScansMatureCrops crops, DigsBlocks digs, PermissionCheck permission) {
        this(crops, digs, permission, null);
    }

    public HarvestSource(ScansMatureCrops crops, DigsBlocks digs, PermissionCheck permission,
            ReplantsCrops replants) {
        this.crops = crops;
        this.digs = digs;
        this.permission = permission;
        this.replants = replants;
    }

    @Override public String describe() {
        return "收熟作物";
    }

    @Override public String route() {
        return AcquireRoutes.HARVEST;
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
        for (CropSpot spot : screened.allowed()) {
            Optional<Action> dig = digs.dig(spot.pos());
            if (dig.isEmpty()) {
                // 挖的入口接不上，一步都还没动：整串放弃，由引擎换别的路。
                return Optional.empty();
            }
            steps.add(dig.get());
            if (replants != null) {
                // 收完顺手把种子种回去；没有种子时补种动作自己会照常收场，不冒充补上了，也不白收。
                replants.replant(spot.pos()).ifPresent(steps::add);
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
}
