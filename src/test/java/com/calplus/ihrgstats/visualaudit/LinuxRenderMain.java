package com.calplus.ihrgstats.visualaudit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Lane a3 (Linux / Java 25 rehearsal): runs lane a2's {@link VisualAuditHarness}
 * datasets from a plain {@code main} inside the runbook container, where no
 * Maven or JUnit launcher is installed. Same order as a2's Windows run
 * (synthetic, matrix, generator, corpus); each runs between the harness's own
 * setUp/tearDown, exactly as JUnit would call them. Output lands where the
 * harness always writes it: {@code <user.dir>/temp/visual-audit/exports/}.
 * <p>
 * Usage: {@code java -cp test-classes:classes:lib/* ...LinuxRenderMain [method...]}
 */
public final class LinuxRenderMain {

    private LinuxRenderMain() {
    }

    public static void main(String[] args) throws Exception {
        String[] methods = args.length > 0 ? args
                : new String[]{"synthetic_variantMatrix", "matrix_neverRenderedCases", "generator_boundaryCases",
                "corpus_expEloPopulatedVariants"};
        int failed = 0;
        for (String name : methods) {
            VisualAuditHarness h = new VisualAuditHarness();
            Method setUp = VisualAuditHarness.class.getDeclaredMethod("setUp");
            Method test = VisualAuditHarness.class.getDeclaredMethod(name);
            Method tearDown = VisualAuditHarness.class.getDeclaredMethod("tearDown");
            setUp.setAccessible(true);
            test.setAccessible(true);
            tearDown.setAccessible(true);
            long t0 = System.nanoTime();
            String result = "PASS";
            setUp.invoke(h);
            try {
                test.invoke(h);
            } catch (InvocationTargetException e) {
                failed++;
                result = "FAIL " + e.getCause();
                e.getCause().printStackTrace(System.out);
            } finally {
                tearDown.invoke(h);
            }
            System.out.printf("[linux-render] %s %s in %d ms%n", name, result, (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("[linux-render] methods=" + methods.length + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
