package com.calplus.ihrgstats.e2e;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Saves, clears and later restores the process-wide state an end-to-end
 * scenario can leak into the next one:
 * <ul>
 *   <li>all system properties (user.dir, the env-file values the bot loads into
 *       system properties, the base-URL and timeout overrides);</li>
 *   <li>every static {@code Map} field of every class in
 *       {@code telegrambot.commands} (the wizard-state maps - discovered from the
 *       compiled classes, so a new command's map is covered automatically) and
 *       {@code TelegramListener.userNameCache};</li>
 *   <li>{@code PredictionService.cachedChampion} (a model cached from another
 *       scenario's database).</li>
 * </ul>
 */
public final class StaticState {

    private static final String COMMANDS_PACKAGE = "com.calplus.ihrgstats.telegrambot.commands";
    private static final List<String> EXTRA_MAP_HOLDERS = List.of(
            "com.calplus.ihrgstats.telegrambot.listener.TelegramListener");
    private static final String PREDICTION_SERVICE = "com.calplus.ihrgstats.ml.PredictionService";
    private static final String CACHED_CHAMPION_FIELD = "cachedChampion";

    private final Properties systemProperties;
    private final Map<Field, Map<Object, Object>> maps = new LinkedHashMap<>();
    private final Object cachedChampion;

    private StaticState() throws ReflectiveOperationException {
        this.systemProperties = new Properties();
        this.systemProperties.putAll(System.getProperties());
        for (Field f : wizardMapFields()) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> live = (Map<Object, Object>) f.get(null);
            maps.put(f, new HashMap<>(live));
        }
        Field champion = championField();
        this.cachedChampion = champion == null ? null : champion.get(null);
    }

    /** Snapshots everything, then clears the maps and the cached champion. */
    public static StaticState saveAndClear() {
        try {
            StaticState s = new StaticState();
            s.clearRuntimeState();
            return s;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot snapshot static state", e);
        }
    }

    /** Clears the wizard maps and the cached champion (system properties untouched). */
    public void clearRuntimeState() throws ReflectiveOperationException {
        for (Field f : maps.keySet()) {
            ((Map<?, ?>) f.get(null)).clear();
        }
        Field champion = championField();
        if (champion != null) {
            champion.set(null, null);
        }
    }

    /** Restores system properties, maps and the cached champion to the snapshot. */
    public void restore() {
        Properties live = System.getProperties();
        for (String key : new ArrayList<>(live.stringPropertyNames())) {
            if (!systemProperties.containsKey(key)) {
                System.clearProperty(key);
            }
        }
        for (String key : systemProperties.stringPropertyNames()) {
            String value = systemProperties.getProperty(key);
            if (!value.equals(live.getProperty(key))) {
                System.setProperty(key, value);
            }
        }
        try {
            for (Map.Entry<Field, Map<Object, Object>> e : maps.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> target = (Map<Object, Object>) e.getKey().get(null);
                target.clear();
                target.putAll(e.getValue());
            }
            Field champion = championField();
            if (champion != null) {
                champion.set(null, cachedChampion);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot restore static state", e);
        }
    }

    /** Names of the static map fields covered ("Class.field"), for reporting. */
    public static List<String> coveredMapFields() {
        try {
            return wizardMapFields().stream().map(f -> f.getDeclaringClass().getSimpleName() + "." + f.getName()).toList();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Field> wizardMapFields() throws ReflectiveOperationException {
        List<Field> out = new ArrayList<>();
        List<String> classNames = new ArrayList<>(commandClassNames());
        classNames.addAll(EXTRA_MAP_HOLDERS);
        for (String name : classNames) {
            Class<?> c = Class.forName(name, false, StaticState.class.getClassLoader());
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static List<String> commandClassNames() {
        try {
            Class<?> anchor = Class.forName(COMMANDS_PACKAGE + ".CommandHelp", false, StaticState.class.getClassLoader());
            Path root = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = root.resolve(COMMANDS_PACKAGE.replace('.', '/'));
            try (Stream<Path> files = Files.list(dir)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".class"))
                        .map(n -> COMMANDS_PACKAGE + "." + n.substring(0, n.length() - ".class".length()))
                        .sorted()
                        .toList();
            }
        } catch (ClassNotFoundException | URISyntaxException | IOException e) {
            throw new IllegalStateException("cannot list command classes", e);
        }
    }

    private static Field championField() throws ReflectiveOperationException {
        try {
            Class<?> c = Class.forName(PREDICTION_SERVICE, false, StaticState.class.getClassLoader());
            Field f = c.getDeclaredField(CACHED_CHAMPION_FIELD);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | ClassNotFoundException e) {
            return null;
        }
    }
}
