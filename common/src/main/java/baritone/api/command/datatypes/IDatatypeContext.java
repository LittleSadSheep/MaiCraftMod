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

package baritone.api.command.datatypes;

import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;

/**
 * Provides an {@link IDatatype} with contextual information so
 * that it can perform the desired operation on the target level.
 *
 * @author Brady
 * @see IDatatype
 * @since 9/26/2019
 */
public interface IDatatypeContext {

    /**
     * Provides the {@link IBaritone} instance that is associated with the action relating to datatype handling.
     *
     * @return The context {@link IBaritone} instance.
     */
    IBaritone getBaritone();

    /**
     * Provides the {@link IArgConsumer}} to fetch input information from.
     *
     * @return The context {@link IArgConsumer}}.
     */
    IArgConsumer getConsumer();
}
