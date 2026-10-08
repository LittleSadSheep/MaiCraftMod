/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.cache;

/**
 * @author Brady
 * @since 9/24/2018
 */
public interface ICachedRegion extends IBlockTypeAccess {

    /**
     * Returns whether or not the block at the specified X and Z coordinates
     * is cached in this world. Similar to {@link ICachedWorld#isCached(int, int)},
     * however, the block coordinates should in on a scale from 0 to 511 (inclusive)
     * because region sizes are 512x512 blocks.
     *
     * @param blockX The block X coordinate
     * @param blockZ The block Z coordinate
     * @return Whether or not the specified XZ location is cached
     * @see ICachedWorld#isCached(int, int)
     */
    boolean isCached(int blockX, int blockZ);

    /**
     * @return The X coordinate of this region
     */
    int getX();

    /**
     * @return The Z coordinate of this region
     */
    int getZ();
}
