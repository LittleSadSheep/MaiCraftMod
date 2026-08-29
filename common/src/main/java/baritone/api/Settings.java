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
     * </pre>
     * Example:
     * <pre>
     *     stone->cobblestone,andesite,oak_planks->birch_planks,acacia_planks,glass
     * </pre>
     */
    public final Setting<Map<Block, List<Block>>> buildSubstitutes = new Setting<>(new HashMap<>());

    /**
     * A list of blocks to become air
     * <p>
     * If a schematic asks for a block on this list, only air will be accepted at that location (and nothing on buildIgnoreBlocks)
     */
    public final Setting<List<Block>> okIfAir = new Setting<>(new ArrayList<>(Arrays.asList(

    )));

    /**
     * If this is true, the builder will treat all non-air blocks as correct. It will only place new blocks.
     */
    public final Setting<Boolean> buildIgnoreExisting = new Setting<>(false);

    /**
     * If this is true, the builder will ignore directionality of certain blocks like glazed terracotta.
     */
    public final Setting<Boolean> buildIgnoreDirection = new Setting<>(false);

    /**
     * A list of names of block properties the builder will ignore.
     */
    public final Setting<List<String>> buildIgnoreProperties = new Setting<>(new ArrayList<>(Arrays.asList(
    )));

    /**
     * If this setting is true, Baritone will never break a block that is adjacent to an unsupported falling block.
     * <p>
     * I.E. it will never trigger cascading sand / gravel falls
     */
    public final Setting<Boolean> avoidUpdatingFallingBlocks = new Setting<>(true);

    /**
     * Enables some more advanced vine features. They're honestly just gimmicks and won't ever be needed in real
     * pathing scenarios. And they can cause Baritone to get trapped indefinitely in a strange scenario.
     * <p>
     * Almost never turn this on lol
     */
    public final Setting<Boolean> allowVines = new Setting<>(false);

    /**
     * Slab behavior is complicated, disable this for higher path reliability. Leave enabled if you have bottom slabs
     * everywhere in your base.
     */
    public final Setting<Boolean> allowWalkOnBottomSlab = new Setting<>(true);

    /**
     * You know what it is
     * <p>
     * But it's very unreliable and falls off when cornering like all the time so.
     * <p>
     * It also overshoots the landing pretty much always (making contact with the next block over), so be careful
     */
    public final Setting<Boolean> allowParkour = new Setting<>(false);

    /**
     * Actually pretty reliable.
     * <p>
     * Doesn't make it any more dangerous compared to just normal allowParkour th
     */
    public final Setting<Boolean> allowParkourPlace = new Setting<>(false);

    /**
     * For example, if you have Mining Fatigue or Haste, adjust the costs of breaking blocks accordingly.
     */
    public final Setting<Boolean> considerPotionEffects = new Setting<>(true);

    /**
     * Sprint and jump a block early on ascends wherever possible
     */
    public final Setting<Boolean> sprintAscends = new Setting<>(true);

    /**
     * If we overshoot a traverse and end up one block beyond the destination, mark it as successful anyway.
     * <p>
     * This helps with speed exceeding 20m/s
     */
    public final Setting<Boolean> overshootTraverse = new Setting<>(true);

    /**
     * When breaking blocks for a movement, wait until all falling blocks have settled before continuing
     */
    public final Setting<Boolean> pauseMiningForFallingBlocks = new Setting<>(true);

    /**
     * How many ticks between right clicks are allowed. Default in game is 4
     */
    public final Setting<Integer> rightClickSpeed = new Setting<>(4);

    /**
     * How many degrees to randomize the yaw every tick. Set to 0 to disable
     */
    public final Setting<Double> randomLooking113 = new Setting<>(2d);

    /**
     * Block reach distance
     */
    public final Setting<Float> blockReachDistance = new Setting<>(4.5f);

    /**
     * How many ticks between breaking a block and starting to break the next block. Default in game is 6 ticks.
     * Values under 1 will be clamped. The delay only applies to non-instant (1-tick) breaks.
     */
    public final Setting<Integer> blockBreakSpeed = new Setting<>(6);

    /**
     * How many degrees to randomize the pitch and yaw every tick. Set to 0 to disable
     */
    public final Setting<Double> randomLooking = new Setting<>(0.01d);

    /**
     * This is the big A* setting.
     * As long as your cost heuristic is an *underestimate*, it's guaranteed to find you the best path.
     * 3.5 is always an underestimate, even if you are sprinting.
     * If you're walking only (with allowSprint off) 4.6 is safe.
     * Any value below 3.5 is never worth it. It's just more computation to find the same path, guaranteed.
     * (specifically, it needs to be strictly slightly less than ActionCosts.WALK_ONE_BLOCK_COST, which is about 3.56)
     * <p>
     * Setting it at 3.57 or above with sprinting, or to 4.64 or above without sprinting, will result in
     * faster computation, at the cost of a suboptimal path. Any value above the walk / sprint cost will result
     * in it going straight at its goal, and not investigating alternatives, because the combined cost / heuristic
     * metric gets better and better with each block, instead of slightly worse.
     * <p>
     * Finding the optimal path is worth it, so it's the default.
     */
    public final Setting<Double> costHeuristic = new Setting<>(3.563);

    // a bunch of obscure internal A* settings that you probably don't want to change
    /**
     * The maximum number of times it will fetch outside loaded or cached chunks before assuming that
     * pathing has reached the end of the known area, and should therefore stop.
     */
    public final Setting<Integer> pathingMaxChunkBorderFetch = new Setting<>(50);

    /**
     * Set to 1.0 to effectively disable this feature
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/18">Issue #18</a>
     */
    public final Setting<Double> backtrackCostFavoringCoefficient = new Setting<>(0.5);

    /**
     * Toggle the following 4 settings
     * <p>
     * They have a noticeable performance impact, so they default off
     * <p>
     * Specifically, building up the avoidance map on the main thread before pathing starts actually takes a noticeable
     * amount of time, especially when there are a lot of mobs around, and your game jitters for like 200ms while doing so
     */
    public final Setting<Boolean> avoidance = new Setting<>(false);

    /**
     * Set to 1.0 to effectively disable this feature
     * <p>
     * Set below 1.0 to go out of your way to walk near mob spawners
     */
    public final Setting<Double> mobSpawnerAvoidanceCoefficient = new Setting<>(2.0);

    /**
     * Distance to avoid mob spawners.
     */
    public final Setting<Integer> mobSpawnerAvoidanceRadius = new Setting<>(16);

    /**
     * Set to 1.0 to effectively disable this feature
     * <p>
     * Set below 1.0 to go out of your way to walk near mobs
     */
    public final Setting<Double> mobAvoidanceCoefficient = new Setting<>(1.5);

    /**
     * Distance to avoid mobs.
     */
    public final Setting<Integer> mobAvoidanceRadius = new Setting<>(8);

    /**
     * When running a goto towards a container block (chest, ender chest, furnace, etc),
     * right click and open it once you arrive.
     */
    public final Setting<Boolean> rightClickContainerOnArrival = new Setting<>(true);

    /**
     * When running a goto towards a nether portal block, walk all the way into the portal
     * instead of stopping one block before.
     */
    public final Setting<Boolean> enterPortal = new Setting<>(true);

    /**
     * Don't repropagate cost improvements below 0.01 ticks. They're all just floating point inaccuracies,
     * and there's no point.
     */
    public final Setting<Boolean> minimumImprovementRepropagation = new Setting<>(true);

    /**
     * After calculating a path (potentially through cached chunks), artificially cut it off to just the part that is
     * entirely within currently loaded chunks. Improves path safety because cached chunks are heavily simplified.
     * <p>
     * This is much safer to leave off now, and makes pathing more efficient. More explanation in the issue.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/114">Issue #114</a>
     */
    public final Setting<Boolean> cutoffAtLoadBoundary = new Setting<>(false);

    /**
     * If a movement's cost increases by more than this amount between calculation and execution (due to changes
     * in the environment / world), cancel and recalculate
     */
    public final Setting<Double> maxCostIncrease = new Setting<>(10D);

    /**
     * Stop 5 movements before anything that made the path COST_INF.
     * For example, if lava has spread across the path, don't walk right up to it then recalculate, it might
     * still be spreading lol
     */
    public final Setting<Integer> costVerificationLookahead = new Setting<>(5);

    /**
     * Static cutoff factor. 0.9 means cut off the last 10% of all paths, regardless of chunk load state
     */
    public final Setting<Double> pathCutoffFactor = new Setting<>(0.9);

    /**
     * Only apply static cutoff for paths of at least this length (in terms of number of movements)
     */
    public final Setting<Integer> pathCutoffMinimumLength = new Setting<>(30);

    /**
     * Start planning the next path once the remaining movements tick estimates sum up to less than this value
     */
    public final Setting<Integer> planningTickLookahead = new Setting<>(150);

    /**
     * Default size of the Long2ObjectOpenHashMap used in pathing
     */
    public final Setting<Integer> pathingMapDefaultSize = new Setting<>(1024);

    /**
     * Load factor coefficient for the Long2ObjectOpenHashMap used in pathing
     * <p>
     * Decrease for faster map operations, but higher memory usage
     */
    public final Setting<Float> pathingMapLoadFactor = new Setting<>(0.75f);

    /**
     * How far are you allowed to fall onto solid ground (without a water bucket)?
     * 3 won't deal any damage. But if you just want to get down the mountain quickly and you have
     * Feather Falling IV, you might set it a bit higher, like 4 or 5.
     */
    public final Setting<Integer> maxFallHeightNoWater = new Setting<>(3);

    /**
     * How far are you allowed to fall onto solid ground (with a water bucket)?
     * It's not that reliable, so I've set it below what would kill an unarmored player (23)
     */
    public final Setting<Integer> maxFallHeightBucket = new Setting<>(20);

    /**
     * Is it okay to sprint through a descend followed by a diagonal?
     * The player overshoots the landing, but not enough to fall off. And the diagonal ensures that there isn't
     * lava or anything that's !canWalkInto in that space, so it's technically safe, just a little sketchy.
     * <p>
     * Note: this is *not* related to the allowDiagonalDescend setting, that is a completely different thing.
     */
    public final Setting<Boolean> allowOvershootDiagonalDescend = new Setting<>(true);

    /**
     * If your goal is a GoalBlock in an unloaded chunk, assume it's far enough away that the Y coord
     * doesn't matter yet, and replace it with a GoalXZ to the same place before calculating a path.
     * Once a segment ends within chunk load range of the GoalBlock, it will go back to normal behavior
     * of considering the Y coord. The reasoning is that if your X and Z are 10,000 blocks away,
     * your Y coordinate's accuracy doesn't matter at all until you get much much closer.
     */
    public final Setting<Boolean> simplifyUnloadedYCoord = new Setting<>(true);

    /**
     * Whenever a block changes, repack the whole chunk that it's in
     */
    public final Setting<Boolean> repackOnAnyBlockChange = new Setting<>(true);

    /**
     * If a movement takes this many ticks more than its initial cost estimate, cancel it
     */
    public final Setting<Integer> movementTimeoutTicks = new Setting<>(100);

    /**
     * Pathing ends after this amount of time, but only if a path has been found
     * <p>
     * If no valid path (length above the minimum) has been found, pathing continues up until the failure timeout
     */
    public final Setting<Long> primaryTimeoutMS = new Setting<>(500L);

    /**
     * Pathing can never take longer than this, even if that means failing to find any path at all
     */
    public final Setting<Long> failureTimeoutMS = new Setting<>(2000L);

    /**
     * Planning ahead while executing a segment ends after this amount of time, but only if a path has been found
     * <p>
     * If no valid path (length above the minimum) has been found, pathing continues up until the failure timeout
     */
    public final Setting<Long> planAheadPrimaryTimeoutMS = new Setting<>(4000L);

    /**
     * Planning ahead while executing a segment can never take longer than this, even if that means failing to find any path at all
     */
    public final Setting<Long> planAheadFailureTimeoutMS = new Setting<>(5000L);

    /**
     * For debugging, consider nodes much much slower
     */
    public final Setting<Boolean> slowPath = new Setting<>(false);

    /**
     * Milliseconds between each node
     */
    public final Setting<Long> slowPathTimeDelayMS = new Setting<>(100L);

    /**
     * The alternative timeout number when slowPath is on
     */
    public final Setting<Long> slowPathTimeoutMS = new Setting<>(40000L);


    /**
     * allows baritone to save bed waypoints when interacting with beds
     */
    public final Setting<Boolean> doBedWaypoints = new Setting<>(true);

    /**
     * allows baritone to save death waypoints
     */
    public final Setting<Boolean> doDeathWaypoints = new Setting<>(true);

    /**
     * The big one. Download all chunks in simplified 2-bit format and save them for better very-long-distance pathing.
     */
    public final Setting<Boolean> chunkCaching = new Setting<>(true);

    /**
     * On save, delete from RAM any cached regions that are more than 1024 blocks away from the player
     * <p>
     * Temporarily disabled
     * <p>
     * Temporarily reenabled
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/248">Issue #248</a>
     */
    public final Setting<Boolean> pruneRegionsFromRAM = new Setting<>(true);

    /**
     * The chunk packer queue can never grow to larger than this, if it does, the oldest chunks are discarded
     * <p>
     * The newest chunks are kept, so that if you're moving in a straight line quickly then stop, your immediate render distance is still included
     */
    public final Setting<Integer> chunkPackerQueueMaxSize = new Setting<>(2000);

    /**
     * Fill in blocks behind you
     */
    public final Setting<Boolean> backfill = new Setting<>(false);

    /**
     * Shows popup message in the upper right corner, similarly to when you make an advancement
     */
    public final Setting<Boolean> logAsToast = new Setting<>(false);

    /**
     * The time of how long the message in the pop-up will display
     * <p>
     * If below 1000L (1sec), it's better to disable this
     */
    public final Setting<Long> toastTimer = new Setting<>(5000L);

    /**
     * Print all the debug messages to chat
     */
    public final Setting<Boolean> chatDebug = new Setting<>(false);

    /**
     * Allow chat based control of Baritone. Most likely should be disabled when Baritone is imported for use in
     * something else
     */
    public final Setting<Boolean> chatControl = new Setting<>(true);

    /**
     * Some clients like Impact try to force chatControl to off, so here's a second setting to do it anyway
     */
    public final Setting<Boolean> chatControlAnyway = new Setting<>(false);

    /**
     * Render the path
     */
    public final Setting<Boolean> renderPath = new Setting<>(true);

    /**
     * Render the path as a line instead of a frickin thingy
     */
    public final Setting<Boolean> renderPathAsLine = new Setting<>(false);

    /**
     * Render the goal
     */
    public final Setting<Boolean> renderGoal = new Setting<>(true);

    /**
     * Render the goal as a sick animated thingy instead of just a box
     * (also controls animation of GoalXZ if {@link #renderGoalXZBeacon} is enabled)
     */
    public final Setting<Boolean> renderGoalAnimated = new Setting<>(true);

    /**
     * Render selection boxes
     */
    public final Setting<Boolean> renderSelectionBoxes = new Setting<>(true);

    /**
     * Ignore depth when rendering the goal
     */
    public final Setting<Boolean> renderGoalIgnoreDepth = new Setting<>(true);

    /**
     * Renders X/Z type Goals with the vanilla beacon beam effect. Combining this with
     * {@link #renderGoalIgnoreDepth} will cause strange render clipping.
     */
    public final Setting<Boolean> renderGoalXZBeacon = new Setting<>(false);

    /**
     * Ignore depth when rendering the selection boxes (to break, to place, to walk into)
     */
    public final Setting<Boolean> renderSelectionBoxesIgnoreDepth = new Setting<>(true);

    /**
     * Ignore depth when rendering the path
     */
    public final Setting<Boolean> renderPathIgnoreDepth = new Setting<>(true);

    /**
     * Line width of the path when rendered, in pixels
     */
    public final Setting<Float> pathRenderLineWidthPixels = new Setting<>(5F);

    /**
     * Line width of the goal when rendered, in pixels
     */
    public final Setting<Float> goalRenderLineWidthPixels = new Setting<>(3F);

    /**
     * Start fading out the path at 20 movements ahead, and stop rendering it entirely 30 movements ahead.
     * Improves FPS.
     */
    public final Setting<Boolean> fadePath = new Setting<>(false);

    /**
     * Move without having to force the client-sided rotations
     */
    public final Setting<Boolean> freeLook = new Setting<>(true);

    /**
     * Break and place blocks without having to force the client-sided rotations. Requires {@link #freeLook}.
     */
    public final Setting<Boolean> blockFreeLook = new Setting<>(false);

    /**
     * Automatically elytra fly without having to force the client-sided rotations.
     */
    public final Setting<Boolean> elytraFreeLook = new Setting<>(true);

    /**
     * Forces the client-sided yaw rotation to an average of the last {@link #smoothLookTicks} of server-sided rotations.
     */
    public final Setting<Boolean> smoothLook = new Setting<>(false);

    /**
     * Same as {@link #smoothLook} but for elytra flying.
     */
    public final Setting<Boolean> elytraSmoothLook = new Setting<>(false);

    /**
     * The number of ticks to average across for {@link #smoothLook};
     */
    public final Setting<Integer> smoothLookTicks = new Setting<>(5);

    /**
     * When true, the player will remain with its existing look direction as often as possible.
     * Although, in some cases this can get it stuck, hence this setting to disable that behavior.
     */
    public final Setting<Boolean> remainWithExistingLookDirection = new Setting<>(true);

    /**
     * Will cause some minor behavioral differences to ensure that Baritone works on anticheats.
     * <p>
     * At the moment this will silently set the player's rotations when using freeLook so you're not sprinting in
     * directions other than forward, which is picken up by more "advanced" anticheats like AAC, but not NCP.
     */
    public final Setting<Boolean> antiCheatCompatibility = new Setting<>(true);

    /**
     * Exclusively use cached chunks for pathing
     * <p>
     * Never turn this on
     */
    public final Setting<Boolean> pathThroughCachedOnly = new Setting<>(false);

    /**
     * Continue sprinting while in water
     */
    public final Setting<Boolean> sprintInWater = new Setting<>(true);

    /**
     * When GetToBlockProcess or MineProcess fails to calculate a path, instead of just giving up, mark the closest instance
     * of that block as "unreachable" and go towards the next closest. GetToBlock expands this search to the whole "vein"; MineProcess does not.
     * This is because MineProcess finds individual impossible blocks (like one block in a vein that has gravel on top then lava, so it can't break)
     * Whereas GetToBlock should blacklist the whole "vein" if it can't get to any of them.
     */
    public final Setting<Boolean> blacklistClosestOnFailure = new Setting<>(true);

    /**
     * 😎 Render cached chunks as semitransparent. Doesn't work with OptiFine 😭 Rarely randomly crashes, see <a href="https://github.com/cabaletta/baritone/issues/327">this issue</a>.
     * <p>
     * Can be very useful on servers with low render distance. After enabling, you may need to reload the world in order for it to have an effect
     * (e.g. disconnect and reconnect, enter then exit the nether, die and respawn, etc). This may literally kill your FPS and CPU because
     * every chunk gets recompiled twice as much as normal, since the cached version comes into range, then the normal one comes from the server for real.
     * <p>
     * Note that flowing water is cached as AVOID, which is rendered as lava. As you get closer, you may therefore see lava falls being replaced with water falls.
     * <p>
     * SOLID is rendered as stone in the overworld, netherrack in the nether, and end stone in the end
     */
    public final Setting<Boolean> renderCachedChunks = new Setting<>(false);

    /**
     * 0.0f = not visible, fully transparent (instead of setting this to 0, turn off renderCachedChunks)
     * 1.0f = fully opaque
     */
    public final Setting<Float> cachedChunksOpacity = new Setting<>(0.5f);

    /**
     * Whether or not to allow you to run Baritone commands with the prefix
     */
    public final Setting<Boolean> prefixControl = new Setting<>(true);

    /**
     * The command prefix for chat control
     */
    public final Setting<String> prefix = new Setting<>("#");

    /**
     * Use a short Baritone prefix [B] instead of [Baritone] when logging to chat
     */
    public final Setting<Boolean> shortBaritonePrefix = new Setting<>(false);

    /**
     * Use a modern message tag instead of a prefix when logging to chat
     */
    public final Setting<Boolean> useMessageTag = new Setting<>(false);

    /**
     * Echo commands to chat when they are run
     */
    public final Setting<Boolean> echoCommands = new Setting<>(true);

    /**
     * Censor coordinates in goals and block positions
     */
    public final Setting<Boolean> censorCoordinates = new Setting<>(false);

    /**
     * Censor arguments to ran commands, to hide, for example, coordinates to #goal
     */
    public final Setting<Boolean> censorRanCommands = new Setting<>(false);

    /**
     * Stop using tools just before they are going to break.
     */
    public final Setting<Boolean> itemSaver = new Setting<>(false);

    /**
     * Durability to leave on the tool when using itemSaver
     */
    public final Setting<Integer> itemSaverThreshold = new Setting<>(10);

    /**
     * Always prefer silk touch tools over regular tools. This will not sacrifice speed, but it will always prefer silk
     * touch tools over other tools of the same speed. This includes always choosing ANY silk touch tool over your hand.
     */
    public final Setting<Boolean> preferSilkTouch = new Setting<>(false);

    /**
     * Don't stop walking forward when you need to break blocks in your way
     */
    public final Setting<Boolean> walkWhileBreaking = new Setting<>(true);

    /**
     * When a new segment is calculated that doesn't overlap with the current one, but simply begins where the current segment ends,
     * splice it on and make a longer combined path. If this setting is off, any planned segment will not be spliced and will instead
     * be the "next path" in PathingBehavior, and will only start after this one ends. Turning this off hurts planning ahead,
     * because the next segment will exist even if it's very short.
     *
     * @see #planningTickLookahead
     */
    public final Setting<Boolean> splicePath = new Setting<>(true);

    /**
     * If we are more than 300 movements into the current path, discard the oldest segments, as they are no longer useful
     */
    public final Setting<Integer> maxPathHistoryLength = new Setting<>(300);

    /**
     * If the current path is too long, cut off this many movements from the beginning.
     */
    public final Setting<Integer> pathHistoryCutoffAmount = new Setting<>(50);

    /**
     * Rescan for the goal once every 5 ticks.
     * Set to 0 to disable.
     */
    public final Setting<Integer> mineGoalUpdateInterval = new Setting<>(5);

    /**
     * After finding this many instances of the target block in the cache, it will stop expanding outward the chunk search.
     */
    public final Setting<Integer> maxCachedWorldScanCount = new Setting<>(10);

    /**
     * Mine will not scan for or remember more than this many target locations.
     * Note that the number of locations retrieved from cache is additionaly
     * limited by {@link #maxCachedWorldScanCount}.
     */
    public final Setting<Integer> mineMaxOreLocationsCount = new Setting<>(64);

    /**
     * Sets the minimum y level whilst mining - set to 0 to turn off.
     * if world has negative y values, subtract the min world height to get the value to put here
     */
    public final Setting<Integer> minYLevelWhileMining = new Setting<>(0);

    /**
     * Sets the maximum y level to mine ores at.
     */
    public final Setting<Integer> maxYLevelWhileMining = new Setting<>(2031);

    /**
     * This will only allow baritone to mine exposed ores, can be used to stop ore obfuscators on servers that use them.
     */
    public final Setting<Boolean> allowOnlyExposedOres = new Setting<>(false);

    /**
     * When allowOnlyExposedOres is enabled this is the distance around to search.
     * <p>
     * It is recommended to keep this value low, as it dramatically increases calculation times.
     */
    public final Setting<Integer> allowOnlyExposedOresDistance = new Setting<>(1);

    /**
     * When GetToBlock or non-legit Mine doesn't know any locations for the desired block, explore randomly instead of giving up.
     */
    public final Setting<Boolean> exploreForBlocks = new Setting<>(true);

    /**
     * While exploring the world, offset the closest unloaded chunk by this much in both axes.
     * <p>
     * This can result in more efficient loading, if you set this to the render distance.
     */
    public final Setting<Integer> worldExploringChunkOffset = new Setting<>(0);

    /**
     * Take the 10 closest chunks, even if they aren't strictly tied for distance metric from origin.
     */
    public final Setting<Integer> exploreChunkSetMinimumSize = new Setting<>(10);

    /**
     * Attempt to maintain Y coordinate while exploring
     * <p>
     * -1 to disable
     */
    public final Setting<Integer> exploreMaintainY = new Setting<>(64);

    /**
     * Replant normal Crops while farming and leave cactus and sugarcane to regrow
     */
    public final Setting<Boolean> replantCrops = new Setting<>(true);

    /**
     * Replant nether wart while farming. This setting only has an effect when replantCrops is also enabled
     */
    public final Setting<Boolean> replantNetherWart = new Setting<>(false);

    /**
     * Farming will scan for at most this many blocks.
     */
    public final Setting<Integer> farmMaxScanSize = new Setting<>(256);

    /**
     * When the cache scan gives less blocks than the maximum threshold (but still above zero), scan the main world too.
     * <p>
     * Only if you have a beefy CPU and automatically mine blocks that are in cache
     */
    public final Setting<Boolean> extendCacheOnThreshold = new Setting<>(false);

    /**
     * Don't consider the next layer in builder until the current one is done
     */
    public final Setting<Boolean> buildInLayers = new Setting<>(false);

    /**
     * false = build from bottom to top
     * <p>
     * true = build from top to bottom
     */
    public final Setting<Boolean> layerOrder = new Setting<>(false);

    /**
     * How high should the individual layers be?
     */
    public final Setting<Integer> layerHeight = new Setting<>(1);

    /**
     * Start building the schematic at a specific layer.
     * Can help on larger builds when schematic wants to break things its already built
     */
    public final Setting<Integer> startAtLayer = new Setting<>(0);

    /**
     * If a layer is unable to be constructed, just skip it.
     */
    public final Setting<Boolean> skipFailedLayers = new Setting<>(false);

    /**
     * Only build the selected part of schematics
     */
    public final Setting<Boolean> buildOnlySelection = new Setting<>(false);

    /**
     * How far to move before repeating the build. 0 to disable repeating on a certain axis, 0,0,0 to disable entirely
     */
    public final Setting<Vec3i> buildRepeat = new Setting<>(new Vec3i(0, 0, 0));

    /**
     * How many times to buildrepeat. -1 for infinite.
     */
    public final Setting<Integer> buildRepeatCount = new Setting<>(-1);

    /**
     * Don't notify schematics that they are moved.
     * e.g. replacing will replace the same spots for every repetition
     * Mainly for backward compatibility.
     */
    public final Setting<Boolean> buildRepeatSneaky = new Setting<>(true);

    /**
     * Allow standing above a block while mining it, in BuilderProcess
     * <p>
     * Experimental
     */
    public final Setting<Boolean> breakFromAbove = new Setting<>(false);

    /**
     * As well as breaking from above, set a goal to up and to the side of all blocks to break.
     * <p>
     * Never turn this on without also turning on breakFromAbove.
     */
    public final Setting<Boolean> goalBreakFromAbove = new Setting<>(false);

    /**
     * Build in map art mode, which makes baritone only care about the top block in each column
     */
    public final Setting<Boolean> mapArtMode = new Setting<>(false);

    /**
     * Override builder's behavior to not attempt to correct blocks that are currently water
     */
    public final Setting<Boolean> okIfWater = new Setting<>(false);

    /**
     * The set of incorrect blocks can never grow beyond this size
     */
    public final Setting<Integer> incorrectSize = new Setting<>(100);

    /**
     * Multiply the cost of breaking a block that's correct in the builder's schematic by this coefficient
     */
    public final Setting<Double> breakCorrectBlockPenaltyMultiplier = new Setting<>(10d);

    /**
     * Multiply the cost of placing a block that's incorrect in the builder's schematic by this coefficient
     */
    public final Setting<Double> placeIncorrectBlockPenaltyMultiplier = new Setting<>(2d);

    /**
     * When this setting is true, build a schematic with the highest X coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationX = new Setting<>(false);

    /**
     * When this setting is true, build a schematic with the highest Y coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationY = new Setting<>(false);

    /**
     * When this setting is true, build a schematic with the highest Z coordinate being the origin, instead of the lowest
     */
    public final Setting<Boolean> schematicOrientationZ = new Setting<>(false);

    /**
     * Rotates the schematic before building it.
     * Possible values are
     * <ul>
     *  <li> NONE - No rotation </li>
     *  <li> CLOCKWISE_90 - Rotate 90° clockwise </li>
     *  <li> CLOCKWISE_180 - Rotate 180° clockwise </li>
     *  <li> COUNTERCLOCKWISE_90 - Rotate 270° clockwise </li>
     * </ul>
     */
    public final Setting<Rotation> buildSchematicRotation = new Setting<>(Rotation.NONE);

    /**
     * Mirrors the schematic before building it.
     * Possible values are
     * <ul>
     *  <li> FRONT_BACK - mirror the schematic along its local x axis </li>
     *  <li> LEFT_RIGHT - mirror the schematic along its local z axis </li>
     * </ul>
     */
    public final Setting<Mirror> buildSchematicMirror = new Setting<>(Mirror.NONE);

    /**
     * The fallback used by the build command when no extension is specified. This may be useful if schematics of a
     * particular format are used often, and the user does not wish to have to specify the extension with every usage.
     */
    public final Setting<String> schematicFallbackExtension = new Setting<>("schematic");

    /**
     * Distance to scan every tick for updates. Expanding this beyond player reach distance (i.e. setting it to 6 or above)
     * is only necessary in very large schematics where rescanning the whole thing is costly.
     */
    public final Setting<Integer> builderTickScanRadius = new Setting<>(5);

    /**
     * While mining, should it also consider dropped items of the correct type as a pathing destination (as well as ore blocks)?
     */
    public final Setting<Boolean> mineScanDroppedItems = new Setting<>(true);

    /**
     * While mining, wait this number of milliseconds after mining an ore to see if it will drop an item
     * instead of immediately going onto the next one
     * <p>
     * Thanks Louca
     */
    public final Setting<Long> mineDropLoiterDurationMSThanksLouca = new Setting<>(250L);

    /**
     * Trim incorrect positions too far away, helps performance but hurts reliability in very large schematics
     */
    public final Setting<Boolean> distanceTrim = new Setting<>(true);

    /**
     * Cancel the current path if the goal has changed, and the path originally ended in the goal but doesn't anymore.
     * <p>
     * Currently only runs when either MineBehavior or FollowBehavior is active.
     * <p>
     * For example, if Baritone is doing "mine iron_ore", the instant it breaks the ore (and it becomes air), that location
     * is no longer a goal. This means that if this setting is true, it will stop there. If this setting were off, it would
     * continue with its path, and walk into that location. The tradeoff is if this setting is true, it mines ores much faster
     * since it doesn't waste any time getting into locations that no longer contain ores, but on the other hand, it misses
     * some drops, and continues on without ever picking them up.
     * <p>
     * Also on cosmic prisons this should be set to true since you don't actually mine the ore it just gets replaced with stone.
     */
    public final Setting<Boolean> cancelOnGoalInvalidation = new Setting<>(true);

    /**
     * The "axis" command (aka GoalAxis) will go to a axis, or diagonal axis, at this Y level.
     */
    public final Setting<Integer> axisHeight = new Setting<>(120);

    /**
     * Disconnect from the server upon arriving at your goal
     */
    public final Setting<Boolean> disconnectOnArrival = new Setting<>(false);

    /**
     * Disallow MineBehavior from using X-Ray to see where the ores are. Turn this option on to force it to mine "legit"
     * where it will only mine an ore once it can actually see it, so it won't do or know anything that a normal player
     * couldn't. If you don't want it to look like you're X-Raying, turn this on
     * This will always explore, regardless of exploreForBlocks
     */
    public final Setting<Boolean> legitMine = new Setting<>(false);

    /**
     * What Y level to go to for legit strip mining
     */
    public final Setting<Integer> legitMineYLevel = new Setting<>(-59);
