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

package baritone.api.behavior;

import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.listener.IGameEventListener;

/**
 * A behavior is simply a type that is able to listen to events.
 *
 * @author Brady
 * @see IGameEventListener
 * @since 9/23/2018
 */
public interface IBehavior extends AbstractGameEventListener {}
