package com.calplus.ihrgstats.databasemanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed tests for {@link E17_MlModels}: upsert semantics (versions are
 * stable identities, not duplicates), champion flag lifecycle, and
 * recency ordering.
 */
public class E17_MlModelsTest {

    private static final String NOW = "2026-01-01 00:00:00.000";

    private String originalUserDir;
    private final E17_MlModels dao = new E17_MlModels();

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        new DatabaseSchema().createDatabase("default.db");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void upsertIsIdempotentPerVersion() throws Exception {
        dao.upsertModel("logistic.r10.aaaa1111", E17_MlModels.FAMILY_LOGISTIC, "{\"v\":1}", "{}", 100, false, NOW);
        dao.upsertModel("logistic.r10.aaaa1111", E17_MlModels.FAMILY_LOGISTIC, "{\"v\":2}", "{\"m\":1}", 120, true, NOW);

        List<E17_MlModels.MlModel> all = dao.getRecent(10);
        assertEquals(1, all.size(), "same version must update in place, not duplicate");
        assertEquals("{\"v\":2}", all.get(0).paramsJson);
        assertEquals(120, all.get(0).trainedBoards);
        assertTrue(all.get(0).isChampion);
    }

    @Test
    void championLifecycle() throws Exception {
        dao.upsertModel("glicko_baseline.r10.bbbb2222", E17_MlModels.FAMILY_GLICKO_BASELINE, "{}", "{}", 100, true, NOW);
        assertEquals("glicko_baseline.r10.bbbb2222", dao.getChampion().modelVersion);

        // New training cycle: clear, crown a different run.
        dao.clearChampionFlags(NOW);
        assertNull(dao.getChampion());
        dao.upsertModel("logistic.r11.cccc3333", E17_MlModels.FAMILY_LOGISTIC, "{}", "{}", 110, true, NOW);
        dao.upsertModel("glicko_baseline.r11.dddd4444", E17_MlModels.FAMILY_GLICKO_BASELINE, "{}", "{}", 110, false, NOW);

        E17_MlModels.MlModel champion = dao.getChampion();
        assertEquals("logistic.r11.cccc3333", champion.modelVersion);
        assertEquals(E17_MlModels.FAMILY_LOGISTIC, champion.family);
    }

    @Test
    void getRecentOrdersNewestFirstAndLimits() throws Exception {
        for (int i = 1; i <= 5; i++) {
            dao.upsertModel("m.r" + i + ".v", E17_MlModels.FAMILY_LOGISTIC, "{}", "{}", i, false, NOW);
        }
        List<E17_MlModels.MlModel> recent = dao.getRecent(3);
        assertEquals(3, recent.size());
        assertEquals("m.r5.v", recent.get(0).modelVersion);
        assertEquals("m.r3.v", recent.get(2).modelVersion);
        assertNull(dao.getByVersion("nope"));
        assertNotNull(dao.getByVersion("m.r4.v"));
    }
}
