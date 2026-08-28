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

package baritone.api.command.argument;

import baritone.api.command.ICommand;
import baritone.api.command.argparser.IArgParser;
import baritone.api.command.datatypes.IDatatype;
import baritone.api.command.datatypes.IDatatypeFor;
import baritone.api.command.datatypes.IDatatypePost;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.exception.CommandNotEnoughArgumentsException;
import baritone.api.command.exception.CommandTooManyArgumentsException;
import baritone.api.utils.Helper;
import java.util.Deque;
import java.util.LinkedList;
import java.util.stream.Stream;
import net.minecraft.core.Direction;

/**
 * The {@link IArgConsumer} is how {@link ICommand}s read the arguments passed to them. This class has many benefits:
 *
 * <ul>
 * <li>Mutability. The whole concept of the {@link IArgConsumer}} is to let you gradually consume arguments in any way
 * you'd like. You can change your consumption based on earlier arguments, for subcommands for example.</li>
 * <li>You don't need to keep track of your consumption. The {@link IArgConsumer}} keeps track of the arguments you
 * consume so that it can throw detailed exceptions whenever something is out of the ordinary. Additionally, if you
 * need to retrieve an argument after you've already consumed it - look no further than {@link #consumed()}!</li>
 * <li>Easy retrieval of many different types. If you need to retrieve an instance of an int or float for example,
 * look no further than {@link #getAs(Class)}. If you need a more powerful way of retrieving data, try out the many
 * {@code getDatatype...} methods.</li>
 * <li>It's very easy to throw detailed exceptions. The {@link IArgConsumer}} has many different methods that can
 * enforce the number of arguments, the type of arguments, and more, throwing different types of
 * {@link CommandException}s if something seems off. You're recommended to do all validation and store all needed
 * data in variables BEFORE logging any data to chat via {@link Helper#logDirect(String)}, so that the error
 * handlers can do their job and log the error to chat.</li>
 * </ul>
 */
public interface IArgConsumer {

    LinkedList<ICommandArgument> getArgs();

    Deque<ICommandArgument> getConsumed();

    /**
     * @param num The number of arguments to check for
     * @return {@code true} if there are <i>at least</i> {@code num} arguments left in this {@link IArgConsumer}}
     * @see #hasAny()
     * @see #hasAtMost(int)
     * @see #hasExactly(int)
     */
    boolean has(int num);

    /**
     * @return {@code true} if there is <i>at least</i> 1 argument left in this {@link IArgConsumer}}
     * @see #has(int)
     * @see #hasAtMostOne()
     * @see #hasExactlyOne()
     */
    boolean hasAny();

    /**
     * @param num The number of arguments to check for
     * @return {@code true} if there are <i>at most</i> {@code num} arguments left in this {@link IArgConsumer}}
     * @see #has(int)
     * @see #hasAtMost(int)
     * @see #hasExactly(int)
     */
    boolean hasAtMost(int num);

    /**
     * @return {@code true} if there is <i>at most</i> 1 argument left in this {@link IArgConsumer}}
     * @see #hasAny()
     * @see #hasAtMostOne()
     * @see #hasExactlyOne()
     */
    boolean hasAtMostOne();

    /**
     * @param num The number of arguments to check for
     * @return {@code true} if there are <i>exactly</i> {@code num} arguments left in this {@link IArgConsumer}}
     * @see #has(int)
     * @see #hasAtMost(int)
     */
    boolean hasExactly(int num);

    /**
     * @return {@code true} if there is <i>exactly</i> 1 argument left in this {@link IArgConsumer}}
     * @see #hasAny()
     * @see #hasAtMostOne()
     */
    boolean hasExactlyOne();

    /**
     * @param index The index to peek
     * @return The argument at index {@code index} in this {@link IArgConsumer}}, with 0 being the next one. This does not
     * mutate the {@link IArgConsumer}}
     * @throws CommandNotEnoughArgumentsException If there is less than {@code index + 1} arguments left
     * @see #peek()
     * @see #peekString(int)
     * @see #peekAs(Class, int)
     * @see #get()
     */
    ICommandArgument peek(int index) throws CommandNotEnoughArgumentsException;

    /**
     * @return The next argument in this {@link IArgConsumer}}. This does not mutate the {@link IArgConsumer}}
     * @throws CommandNotEnoughArgumentsException If there is less than one argument left
     * @see #peek(int)
     * @see #peekString()
     * @see #peekAs(Class)
     * @see #get()
     */
    ICommandArgument peek() throws CommandNotEnoughArgumentsException;

    /**
     * @param index The index to peek
     * @param type  The type to check for
     * @return If an ArgParser.Stateless for the specified {@code type} would succeed in parsing the next
     * argument
     * @throws CommandNotEnoughArgumentsException If there is less than {@code index + 1} arguments left
     * @see #peek()
     * @see #getAs(Class)
     */
    boolean is(Class<?> type, int index) throws CommandNotEnoughArgumentsException;

    /**
     * @param type The type to check for
     * @return If an ArgParser.Stateless for the specified {@code type} would succeed in parsing the next
     * argument
     * @throws CommandNotEnoughArgumentsException If there is less than one argument left
     * @see #peek()
     * @see #getAs(Class)
     */
    boolean is(Class<?> type) throws CommandNotEnoughArgumentsException;

    /**
     * @param index The index to peek
     * @return The value of the argument at index {@code index} in this {@link IArgConsumer}}, with 0 being the next one
     * This does not mutate the {@link IArgConsumer}}
     * @throws CommandNotEnoughArgumentsException If there is less than {@code index + 1} arguments left
     * @see #peek()
     * @see #peekString()
     */
    String peekString(int index) throws CommandNotEnoughArgumentsException;

    /**
     * @return The value of the next argument in this {@link IArgConsumer}}. This does not mutate the {@link IArgConsumer}}
     * @throws CommandNotEnoughArgumentsException If there is less than one argument left
     * @see #peekString(int)
     * @see #getString()
     */
    String peekString() throws CommandNotEnoughArgumentsException;

    /**
     * @param index     The index to peek
     * @param enumClass The class to search
     * @return From the specified enum class, an enum constant of that class. The enum constant's name will match the
     * next argument's value
     * @throws java.util.NoSuchElementException If the constant couldn't be found
     * @see #peekEnumOrNull(Class)
     * @see #getEnum(Class)
     * @see ICommandArgument#getEnum(Class)
     */
    <E extends Enum<?>> E peekEnum(Class<E> enumClass, int index) throws CommandInvalidTypeException, CommandNotEnoughArgumentsException;

    /**
     * @param enumClass The class to search
     * @return From the specified enum class, an enum constant of that class. The enum constant's name will match the
     * next argument's value
     * @throws CommandInvalidTypeException If the constant couldn't be found
     * @see #peekEnumOrNull(Class)
     * @see #getEnum(Class)
     * @see ICommandArgument#getEnum(Class)
     */
    <E extends Enum<?>> E peekEnum(Class<E> enumClass) throws CommandInvalidTypeException, CommandNotEnoughArgumentsException;

    /**
     * @param index     The index to peek
     * @param enumClass The class to search
     * @return From the specified enum class, an enum constant of that class. The enum constant's name will match the
     * next argument's value. If no constant could be found, null
     * @see #peekEnum(Class)
     * @see #getEnumOrNull(Class)
     * @see ICommandArgument#getEnum(Class)
     */
    <E extends Enum<?>> E peekEnumOrNull(Class<E> enumClass, int index) throws CommandNotEnoughArgumentsException;

    /**
     * @param enumClass The class to search
     * @return From the specified enum class, an enum constant of that class. The enum constant's name will match the
     * next argument's value. If no constant could be found, null
     * @see #peekEnum(Class)
     * @see #getEnumOrNull(Class)
     * @see ICommandArgument#getEnum(Class)
     */
    <E extends Enum<?>> E peekEnumOrNull(Class<E> enumClass) throws CommandNotEnoughArgumentsException;

    /**
     * Tries to use a <b>stateless</b> {@link IArgParser} to parse the argument at the specified index into the specified
     * class
     * <p>
     * A critical difference between {@link IDatatype}s and {@link IArgParser}s is how many arguments they can take.
     * While {@link IArgParser}s always operate on a single argument's value, {@link IDatatype}s get access to the entire
     * {@link IArgConsumer}}.
     *
     * @param type  The type to peek as
     * @param index The index to peek
     * @return An instance of the specified type
     * @throws CommandInvalidTypeException If the parsing failed
     * @see IArgParser
     * @see #peekAs(Class)
     * @see #peekAsOrDefault(Class, Object, int)
     * @see #peekAsOrNull(Class, int)
     */
    <T> T peekAs(Class<T> type, int index) throws CommandInvalidTypeException, CommandNotEnoughArgumentsException;

    /**
     * Tries to use a <b>stateless</b> {@link IArgParser} to parse the next argument into the specified class
     * <p>
     * A critical difference between {@link IDatatype}s and {@link IArgParser}s is how many arguments they can take.
     * While {@link IArgParser}s always operate on a single argument's value, {@link IDatatype}s get access to the entire
     * {@link IArgConsumer}}.
     *
     * @param type The type to peek as
     * @return An instance of the specified type
     * @throws CommandInvalidTypeException If the parsing failed
     * @see IArgParser
     * @see #peekAs(Class, int)
     * @see #peekAsOrDefault(Class, Object)
     * @see #peekAsOrNull(Class)
     */
    <T> T peekAs(Class<T> type) throws CommandInvalidTypeException, CommandNotEnoughArgumentsException;

    /**
     * Tries to use a <b>stateless</b> {@link IArgParser} to parse the argument at the specified index into the specified
     * class
     * <p>
     * A critical difference between {@link IDatatype}s and {@link IArgParser}s is how many arguments they can take.
     * While {@link IArgParser}s always operate on a single argument's value, {@link IDatatype}s get access to the entire
     * {@link IArgConsumer}}.
     *
     * @param type  The type to peek as
     * @param def   The value to return if the argument can't be parsed
     * @param index The index to peek
     * @return An instance of the specified type, or {@code def} if it couldn't be parsed
     * @see IArgParser
     * @see #peekAsOrDefault(Class, Object)
     * @see #peekAs(Class, int)
     * @see #peekAsOrNull(Class, int)
     */
    <T> T peekAsOrDefault(Class<T> type, T def, int index) throws CommandNotEnoughArgumentsException;

    /**
     * Tries to use a <b>stateless</b> {@link IArgParser} to parse the next argument into the specified class
     * <p>
     * A critical difference between {@link IDatatype}s and {@link IArgParser}s is how many arguments they can take.
     * While {@link IArgParser}s always operate on a single argument's value, {@link IDatatype}s get access to the entire
     * {@link IArgConsumer}}.
     *
     * @param type The type to peek as
     * @param def  The value to return if the argument can't be parsed
     * @return An instance of the specified type, or {@code def} if it couldn't be parsed
     * @see IArgParser
     * @see #peekAsOrDefault(Class, Object, int)
     * @see #peekAs(Class)
     * @see #peekAsOrNull(Class)
     */
    <T> T peekAsOrDefault(Class<T> type, T def) throws CommandNotEnoughArgumentsException;

    /**
     * Tries to use a <b>stateless</b> {@link IArgParser} to parse the argument at the specified index into the specified
     * class
     * <p>
     * A critical difference between {@link IDatatype}s and {@link IArgParser}s is how many arguments they can take.
     * While {@link IArgParser}s always operate on a single argument's value, {@link IDatatype}s get access to the entire
     * {@link IArgConsumer}}.
     *
     * @param type  The type to peek as
     * @param index The index to peek
     * @return An instance of the specified type, or {@code null} if it couldn't be parsed
     * @see IArgParser
     * @see #peekAsOrNull(Class)
     * @see #peekAs(Class, int)
     * @see #peekAsOrDefault(Class, Object, int)
