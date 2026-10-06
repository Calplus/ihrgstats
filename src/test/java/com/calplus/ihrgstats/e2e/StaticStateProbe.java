package com.calplus.ihrgstats.e2e;

import java.lang.reflect.Field;
import java.util.Map;

/** Lane a1b: reads the sizes of the static wizard-state maps the rig's StaticState covers (read-only). */
final class StaticStateProbe {

    private StaticStateProbe() {
    }

    /** Total entries across every covered static map (TelegramListener.userNameCache included). */
    static int totalEntries() {
        int n = 0;
        try {
            for (Field f : StaticState.wizardMapFields()) {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof Map<?, ?> m) {
                    n += m.size();
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return n;
    }

    /** "Class.field=size" for every non-empty covered map. */
    static String describe() {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : StaticState.wizardMapFields()) {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                    sb.append("  ").append(f.getDeclaringClass().getSimpleName()).append('.').append(f.getName())
                            .append('=').append(m.size()).append('\n');
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }
}
