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

package baritone.utils;

import baritone.api.utils.IPlayerContext;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

/**
 * @author Brady
 * @since 8/25/2018
 */
public final class BlockBreakHelper {
    BlockBreakHelper(IPlayerContext ignored) {}

    public void stopBreakingBlock() {
        EmbeddedBaritoneRuntime.requestStopBreaking();
    }
}
