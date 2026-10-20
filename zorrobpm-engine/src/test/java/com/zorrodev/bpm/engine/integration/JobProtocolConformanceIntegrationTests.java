package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * WO-OPS-30 F2a — измерение протокола job на НАШЕМ движке (колонка «ours»
 * матрицы {@code docs/analysis/job-protocol-conformance-matrix.md}).
 *
 * <p>Кейсы — те же, что исполняются против живого Zeebe 8.5
 * ({@code docs/analysis/c8-oracle/job-protocol.js}), из общего списка
 * {@code job-protocol-cases.json}. Класс НЕ придумывает ожидаемых значений:
 * он меряет поведение реального движка (G-N: {@code RuntimeService} /
 * {@code ActivityService} → handler'ы → outbox-тело задания → таблицы) и
 * пишет наблюдения в {@code target/job-protocol-observed.json}; сверка с
 * живым 8.5 — в матрице (G-Q).
 *
 * <p>Имя класса оканчивается на {@code IntegrationTests} — иначе его не заберёт
 * ни surefire, ни failsafe (урок WO-C8-40: {@code *IT} не попадает ни в один
 * из них, и тест не выполняется в CI вообще).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class JobProtocolConformanceIntegrationTests extends JobProtocolConformanceSupport {

    @Transactional
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void measure(Map<String, Object> c) throws Exception {
        measureCase(c);
    }
}
