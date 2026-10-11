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

package baritone.api.utils;

import baritone.api.pathing.calc.IPath;

import java.util.Objects;
import java.util.Optional;

public class PathCalculationResult {

    private final IPath path;
    private final Type type;
    /** 算路程序出错时的异常说明（类名加消息）；别的结论为 null。 */
    private final String failure;

    public PathCalculationResult(Type type) {
        this(type, null);
    }

    public PathCalculationResult(Type type, IPath path) {
        this(type, path, null);
    }

    private PathCalculationResult(Type type, IPath path, String failure) {
        Objects.requireNonNull(type);
        this.path = path;
        this.type = type;
        this.failure = failure;
    }

    /** 算路程序出错：记下异常本身，走到的结果据此说"程序出错"，不说成"没有路"。 */
    public static PathCalculationResult error(Throwable failure) {
        return new PathCalculationResult(Type.EXCEPTION, null, failure == null ? "未知异常" : failure.toString());
    }

    /** 程序出错时的异常说明；不是程序出错为空。 */
    public final Optional<String> getFailure() {
        return Optional.ofNullable(this.failure);
    }

    public final Optional<IPath> getPath() {
        return Optional.ofNullable(this.path);
    }

    public final Type getType() {
        return this.type;
    }

    public enum Type {
        SUCCESS_TO_GOAL,
        SUCCESS_SEGMENT,
        FAILURE,
        CANCELLATION,
        EXCEPTION,
    }
}
