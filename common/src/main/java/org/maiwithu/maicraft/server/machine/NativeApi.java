// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Optional, public mod APIs only. Never links a client class or accesses private fields. */
public final class NativeApi {
    private static final ClassValue<Map<String, java.util.List<Method>>> METHODS = new ClassValue<>() {
        @Override protected Map<String, java.util.List<Method>> computeValue(Class<?> type) {
            return java.util.Arrays.stream(type.getMethods()).collect(java.util.stream.Collectors.groupingBy(Method::getName));
        }
    };
    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            int.class, Integer.class, long.class, Long.class, boolean.class, Boolean.class,
            double.class, Double.class, float.class, Float.class, byte.class, Byte.class);

    private NativeApi() {}

    public static Class<?> type(String name) {
        try { return Class.forName(name, false, NativeApi.class.getClassLoader()); }
        catch (ClassNotFoundException | LinkageError missing) { throw new Unavailable(name, missing); }
    }

    public static boolean present(String name) {
        try { type(name); return true; } catch (Unavailable missing) { return false; }
    }

    public static boolean is(Object value, String name) {
        return value != null && present(name) && type(name).isInstance(value);
    }

    public static Object call(Object target, String api, String method, Object... arguments) {
        Class<?> owner = api == null ? target.getClass() : type(api);
        Method found = null;
        java.util.List<Method> methods;
        try { methods = METHODS.get(owner).getOrDefault(method, java.util.List.of()); }
        catch (LinkageError unavailable) { throw new Unavailable(owner.getName(), unavailable); }
        for (Method candidate : methods) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                Class<?> expected = BOXED.getOrDefault(parameters[i], parameters[i]);
                if (arguments[i] == null ? parameters[i].isPrimitive() : !expected.isInstance(arguments[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (found != null && !found.isBridge() && !candidate.isBridge()
                        && !java.util.Arrays.equals(found.getParameterTypes(), candidate.getParameterTypes())) {
                    throw new Unavailable("Ambiguous API: " + owner.getName() + "." + method, null);
                }
                if (found == null || found.isBridge()) found = candidate;
            }
        }
        if (found == null) throw new Unavailable(owner.getName() + "." + method, null);
        try { return found.invoke(target, arguments); }
        catch (InvocationTargetException failed) {
            // A mod may have changed state before throwing. Callers must never report this as not applied.
            throw new NativeFailure(owner.getName() + "." + method, failed.getCause());
        } catch (ReflectiveOperationException | LinkageError missing) {
            throw new Unavailable(owner.getName() + "." + method, missing);
        }
    }

    public static Object field(Object target, String api, String name) {
        try { return (api == null ? target.getClass() : type(api)).getField(name).get(target); }
        catch (ReflectiveOperationException | LinkageError missing) { throw new Unavailable(name, missing); }
    }

    public static Object constant(String api, String name) { return field(null, api, name); }

    public static Object enumValue(String api, String name) {
        for (Object value : type(api).getEnumConstants()) {
            if (((Enum<?>) value).name().equalsIgnoreCase(name)) return value;
        }
        throw new IllegalArgumentException("Unknown " + api + " value: " + name);
    }

    public static long number(Object value) { return ((Number) value).longValue(); }
    public static boolean truth(Object value) { return Boolean.TRUE.equals(value); }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    public static final class NativeFailure extends RuntimeException {
        public NativeFailure(String message, Throwable cause) { super(message, cause); }
    }
}
