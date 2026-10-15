// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 收回自己放下的东西：角色自己放下的床、工作台、熔炉（服务端归属记录写的是角色自己），
 * 要它时走过去挖下来捡进包，按路程算代价——床落在野外一百多格外，走回去拆回来比打羊、去远处打鱿鱼现做省事。
 *
 * <p>候选是世界记忆里记着的工作站（睡过的床、用过或看见过的工作台、熔炉），动手前到现场核对：
 * 那一格还是那个方块才算。别人放的、没有归属记录的不动；归属还没问到就等它问到，拿不准不动；
 * 放在记住的"家"附近的不收——家里的床是重生点，家里的台子、炉子是摆好用的。
 * 挖那一格照许可检查点的规矩来（自己放的东西 temporary 档也能收回）。
 */
public final class ReclaimSource implements ItemSource {

    /** 找多远的：和夜里找自己睡过的床同一个距离，再远就该先出行。 */
    public static final double SEARCH_RADIUS_BLOCKS = 128.0;
    private static final String CRAFTING_TABLE = "minecraft:crafting_table";
    private static final String FURNACE = "minecraft:furnace";

    private final WorldMemory memory;
    private final ReadsBlockTypes blocks;
    private final Protection protection;
    private final PermissionCheck permission;
    private final CollectsBlocks collects;
    private final ReadsItemTags tags;

    public ReclaimSource(WorldMemory memory, ReadsBlockTypes blocks, Protection protection, PermissionCheck permission,
            CollectsBlocks collects, ReadsItemTags tags) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.collects = Objects.requireNonNull(collects, "collects");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override public String describe() {
        return "收回自己放下的";
    }

    @Override public AcquireVia via() {
        return AcquireVia.RECLAIM;
    }

    /** 收得回的方块：床（各色）、工作台、熔炉；拆下来掉的就是同名物品。 */
    static boolean reclaimable(String blockType) {
        return blockType.endsWith("_bed") || CRAFTING_TABLE.equals(blockType) || FURNACE.equals(blockType);
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        Candidates found = candidates(request, context);
        if (!found.spots().isEmpty()) {
            Spot nearest = found.spots().getFirst();
            return new SourceQuote.Offer(describe(), found.spots().size(),
                    new AcquisitionCost(nearest.distance(), 1),
                    "走到 " + nearest.at().x() + ", " + nearest.at().y() + ", " + nearest.at().z()
                            + " 把自己放下的 " + nearest.blockType() + " 挖下来收进包");
        }
        // 记得的那几处归属还没问到：等一两刻再问，不把"还不知道是谁放的"当成"不是自己的"。
        if (found.pending() > 0) {
            return new SourceQuote.NotYet(describe(), "记得的 " + found.pending() + " 处还没问清是谁放的");
        }
        if (found.refusal() != null) {
            return new SourceQuote.NeedsApproval(describe(), found.refusal());
        }
        return new SourceQuote.Unavailable(describe(), "半径 " + (int) radius(context) + " 格内没有记得的、自己放下还在原处的"
                + request.wanted().describe() + "（只收床、工作台、熔炉：睡过的床、用过或见过的台子和炉子；家附近的不收）");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 动手前重新核对现场：问价到动手之间可能被别人拆了，以现场为准；挑最近的那一处。
        List<Spot> spots = candidates(request, context).spots();
        if (spots.isEmpty()) return Optional.empty();
        WorldPosition at = spots.getFirst().at();
        // 走到跟前、挖下来、捡起这一下掉出来的（床挖哪一半都整张掉下来，熔炉里的东西一并掉出来捡起）。
        return collects.collect(new BlockPos(at.x(), at.y(), at.z()), context.permissions());
    }

    /** 一处收得回的：在哪、是什么、离多远。 */
    private record Spot(WorldPosition at, String blockType, double distance) {}

    /** 找候选的结论：收得回的几处（近的在前）、归属还没问到的几处、被许可挡下的第一个原因。 */
    private record Candidates(List<Spot> spots, int pending, Problem refusal) {}

    // 记得的工作站里挑：是要的东西、收得回的种类、现场还是那个方块、不在家附近、归属问到了且是自己放的、许可准挖。
    private Candidates candidates(ItemRequest request, SourceContext context) {
        List<Spot> spots = new ArrayList<>();
        int pending = 0;
        Problem refusal = null;
        WorldPosition here = context.characterAt();
        for (MemoryRecord record : memory.recordsNear(here, radius(context))) {
            String type = record.blockType();
            if (record.kind() != MemoryKind.WORKSTATION || type == null || !reclaimable(type)) continue;
            if (!request.wanted().matches(type, tags.tagsOf(type))) continue;
            WorldPosition at = record.position();
            if (!type.equals(blocks.blockTypeAt(at).orElse(null)) || protection.insideHome(at)) continue;
            if (!protection.ownershipKnown(at)) {
                pending++;
                continue;
            }
            if (!protection.selfPlaced(at)) continue;
            Optional<Problem> refused = permission.blockAllowed(context.permissions(),
                    PermissionCheck.WorldAction.DIG_BLOCK, at, type);
            if (refused.isPresent()) {
                if (refusal == null) refusal = refused.get();
                continue;
            }
            spots.add(new Spot(at, type, distance(at, here)));
        }
        spots.sort(Comparator.comparingDouble(Spot::distance));
        return new Candidates(List.copyOf(spots), pending, refusal);
    }

    /** 这次找多远：任务给了半径就不越界，没给按自己的默认。 */
    private static double radius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    private static double distance(WorldPosition at, WorldPosition here) {
        double dx = at.x() - here.x();
        double dy = at.y() - here.y();
        double dz = at.z() - here.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
