package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * WO-OPS-28 ФАЗА 1 — «Задачи и данные»: измерение поведения НАШЕГО движка на корпусе,
 * который тем же составом измерен на живом Zeebe 8.5.0
 * ({@code docs/analysis/c8-oracle/ops28-phase1-oracle.js}).
 *
 * <p>Класс ИЗМЕРЯЕТ, а не утверждает «правильность»: расхождения с Zeebe 8.5 — это
 * кандидаты в отдельные баг-WO (кампания WO-OPS-28: правки ядра идут отдельными WO
 * с красной командой). Здесь проверяется ровно одно — что измерение состоялось
 * (кейс дал исход) и что инвариант утечек G-P для инстанса кейса не нарушен.</p>
 *
 * <p>G-N: каждое наблюдение снято с ПРОД-пути (движок → обработчики → {@code ElementSupport}
 * → переменные/БД), а не с копии логики движка.</p>
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Phase1TaskDataConformanceIntegrationTests extends Phase1TaskDataConformanceSupport {

    @Transactional
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void measure(Map<String, Object> c) throws Exception {
        measureCase(c);
    }
}
