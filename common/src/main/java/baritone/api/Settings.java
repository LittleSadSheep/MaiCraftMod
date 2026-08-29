/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api;

import baritone.api.utils.Helper;
import baritone.api.utils.NotificationHelper;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.TypeUtils;
import baritone.api.utils.gui.BaritoneToast;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Baritone's settings. Settings apply to all Baritone instances.
 *
 * @author leijurv
 */
public final class Settings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Baritone");

    /**
     * Allow Baritone to break blocks
     */
    public final Setting<Boolean> allowBreak = new Setting<>(true);

    /**
     * Blocks that baritone will be allowed to break even with allowBreak set to false
     */
    public final Setting<List<Block>> allowBreakAnyway = new Setting<>(new ArrayList<>());

    /**
     * Allow Baritone to sprint
     */
    public final Setting<Boolean> allowSprint = new Setting<>(true);

    /**
     * Allow Baritone to place blocks
     */
    public final Setting<Boolean> allowPlace = new Setting<>(true);

    /**
     * Allow Baritone to place blocks in fluid source blocks
     */
    public final Setting<Boolean> allowPlaceInFluidsSource = new Setting<>(true);

    /**
     * Allow Baritone to place blocks in flowing fluid
     */
    public final Setting<Boolean> allowPlaceInFluidsFlow = new Setting<>(true);

    /**
     * Allow Baritone to move items in your inventory to your hotbar
     */
    public final Setting<Boolean> allowInventory = new Setting<>(false);

    /**
     * Wait this many ticks between InventoryBehavior moving inventory items
     */
    public final Setting<Integer> ticksBetweenInventoryMoves = new Setting<>(1);

    /**
     * Come to a halt before doing any inventory moves. Intended for anticheat such as 2b2t
     */
    public final Setting<Boolean> inventoryMoveOnlyIfStationary = new Setting<>(false);

    /**
     * Disable baritone's auto-tool at runtime, but still assume that another mod will provide auto tool functionality
     * <p>
     * Specifically, path calculation will still assume that an auto tool will run at execution time, even though
     * Baritone itself will not do that.
     */
    public final Setting<Boolean> assumeExternalAutoTool = new Setting<>(false);

    /**
     * Automatically select the best available tool
     */
    public final Setting<Boolean> autoTool = new Setting<>(true);

    /**
     * It doesn't actually take twenty ticks to place a block, this cost is so high
     * because we want to generally conserve blocks which might be limited.
     * <p>
     * Decrease to make Baritone more often consider paths that would require placing blocks
     */
    public final Setting<Double> blockPlacementPenalty = new Setting<>(20D);

    /**
     * This is just a tiebreaker to make it less likely to break blocks if it can avoid it.
     * For example, fire has a break cost of 0, this makes it nonzero, so all else being equal
     * it will take an otherwise equivalent route that doesn't require it to put out fire.
     */
    public final Setting<Double> blockBreakAdditionalPenalty = new Setting<>(2D);

    /**
     * Additional penalty for hitting the space bar (ascend, pillar, or parkour) because it uses hunger
     */
    public final Setting<Double> jumpPenalty = new Setting<>(2D);

    /**
     * Walking on water uses up hunger really quick, so penalize it
     */
    public final Setting<Double> walkOnWaterOnePenalty = new Setting<>(3D);

    /**
     * Don't allow breaking blocks next to liquids.
     * <p>
     * Enable if you have mods adding custom fluid physics.
     */
    public final Setting<Boolean> strictLiquidCheck = new Setting<>(false);

    /**
     * Allow Baritone to fall arbitrary distances and place a water bucket beneath it.
     * Reliability: questionable.
     */
    public final Setting<Boolean> allowWaterBucketFall = new Setting<>(true);

    /**
     * Allow Baritone to assume it can walk on still water just like any other block.
     * This functionality is assumed to be provided by a separate library that might have imported Baritone.
     * <p>
     * Note: This will prevent some usage of the frostwalker enchantment, like pillaring up from water.
     */
    public final Setting<Boolean> assumeWalkOnWater = new Setting<>(false);

    /**
     * If you have Fire Resistance and Jesus then I guess you could turn this on lol
     */
    public final Setting<Boolean> assumeWalkOnLava = new Setting<>(false);

    /**
     * Assume step functionality; don't jump on an Ascend.
     */
    public final Setting<Boolean> assumeStep = new Setting<>(false);

    /**
     * Assume safe walk functionality; don't sneak on a backplace traverse.
     * <p>
     * Warning: if you do something janky like sneak-backplace from an ender chest, if this is true
     * it won't sneak right click, it'll just right click, which means it'll open the chest instead of placing
     * against it. That's why this defaults to off.
     */
    public final Setting<Boolean> assumeSafeWalk = new Setting<>(false);

    /**
     * If true, parkour is allowed to make jumps when standing on blocks at the maximum height, so player feet is y=256
     * <p>
     * Defaults to false because this fails on constantiam. Please let me know if this is ever disabled. Please.
     */
    public final Setting<Boolean> allowJumpAtBuildLimit = new Setting<>(false);

    /**
     * Just here so mods that use the API don't break. Does nothing.
     */
    @Deprecated
    @JavaOnly
    public final Setting<Boolean> allowJumpAt256 = new Setting<>(false);

    /**
     * This should be monetized it's so good
     * <p>
     * Defaults to true, but only actually takes effect if allowParkour is also true
     */
    public final Setting<Boolean> allowParkourAscend = new Setting<>(true);

    /**
     * Allow descending diagonally
     * <p>
     * Safer than allowParkour yet still slightly unsafe, can make contact with unchecked adjacent blocks, so it's unsafe in the nether.
     * <p>
     * For a generic "take some risks" mode I'd turn on this one, parkour, and parkour place.
     */
    public final Setting<Boolean> allowDiagonalDescend = new Setting<>(false);

    /**
     * Allow diagonal ascending
     * <p>
     * Actually pretty safe, much safer than diagonal descend tbh
     */
    public final Setting<Boolean> allowDiagonalAscend = new Setting<>(false);

    /**
     * Allow mining the block directly beneath its feet
     * <p>
     * Turn this off to force it to make more staircases and less shafts
     */
    public final Setting<Boolean> allowDownward = new Setting<>(true);

    /**
     * Blocks that Baritone is allowed to place (as throwaway, for sneak bridging, pillaring, etc.)
     */
    public final Setting<List<Item>> acceptableThrowawayItems = new Setting<>(new ArrayList<>(Arrays.asList(
            Blocks.DIRT.asItem(),
            Blocks.COBBLESTONE.asItem(),
            Blocks.NETHERRACK.asItem(),
            Blocks.STONE.asItem()
    )));

    /**
     * Blocks that Baritone will attempt to avoid (Used in avoidance)
     */
    public final Setting<List<Block>> blocksToAvoid = new Setting<>(new ArrayList<>(List.of(
            Blocks.TRIPWIRE
    )));

    /**
     * Blocks that Baritone is not allowed to break
     */
    public final Setting<List<Block>> blocksToDisallowBreaking = new Setting<>(new ArrayList<>(
            // Leave Empty by Default
    ));

    /**
     * blocks that baritone shouldn't break, but can if it needs to.
     */
    public final Setting<List<Block>> blocksToAvoidBreaking = new Setting<>(new ArrayList<>(Arrays.asList( // TODO can this be a HashSet or ImmutableSet?
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST
    )));

    /**
     * this multiplies the break speed, if set above 1 it's "encourage breaking" instead
     */
    public final Setting<Double> avoidBreakingMultiplier = new Setting<>(.1);

    /**
     * A list of blocks to be treated as if they're air.
     * <p>
     * If a schematic asks for air at a certain position, and that position currently contains a block on this list, it will be treated as correct.
     */
    public final Setting<List<Block>> buildIgnoreBlocks = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * A list of blocks to be treated as correct.
     * <p>
     * If a schematic asks for any block on this list at a certain position, it will be treated as correct, regardless of what it currently is.
     */
    public final Setting<List<Block>> buildSkipBlocks = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * A mapping of blocks to blocks treated as correct in their position
     * <p>
     * If a schematic asks for a block on this mapping, all blocks on the mapped list will be accepted at that location as well
     * <p>
     * Syntax same as <a href="https://baritone.leijurv.com/baritone/api/Settings.html#buildSubstitutes">buildSubstitutes</a>
     */
    public final Setting<Map<Block, List<Block>>> buildValidSubstitutes = new Setting<>(new HashMap<>());

    /**
     * A mapping of blocks to blocks to be built instead
     * <p>
     * If a schematic asks for a block on this mapping, Baritone will place the first placeable block in the mapped list
     * <p>
     * Usage Syntax:
     * <pre>
     *      sourceblockA->blockToSubstituteA1,blockToSubstituteA2,...blockToSubstituteAN,sourceBlockB->blockToSubstituteB1,blockToSubstituteB2,...blockToSubstituteBN,...sourceBlockX->blockToSubstituteX1,blockToSubstituteX2...blockToSubstituteXN
