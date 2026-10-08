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

package baritone.api.command.exception;

import baritone.api.command.argument.ICommandArgument;

public abstract class CommandInvalidArgumentException extends CommandErrorMessageException {

    public final ICommandArgument arg;

    protected CommandInvalidArgumentException(ICommandArgument arg, String message) {
        super(formatMessage(arg, message));
        this.arg = arg;
    }

    protected CommandInvalidArgumentException(ICommandArgument arg, String message, Throwable cause) {
        super(formatMessage(arg, message), cause);
        this.arg = arg;
    }

    private static String formatMessage(ICommandArgument arg, String message) {
        return String.format(
                "Error at argument #%s: %s",
                arg.getIndex() == -1 ? "<unknown>" : Integer.toString(arg.getIndex() + 1),
                message
        );
    }
}
