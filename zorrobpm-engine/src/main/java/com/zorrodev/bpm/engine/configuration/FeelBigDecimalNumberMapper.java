package com.zorrodev.bpm.engine.configuration;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValNumber;
import org.camunda.feel.valuemapper.JavaCustomValueMapper;

import java.util.Optional;
import java.util.function.Function;

/**
 * WO-ENG-27 (NEW2-05): DMN/io-mapping FEEL-движок ({@code forJava}) отдавал
 * числа как {@code Double} — значение из 17 значащих цифр
 * ({@code 12345678901234567.89}) превращалось в {@code 1.2345678901234568E16},
 * и {@code toProcessVariable} молча хранил его как LONG '12345678901234568'
 * (Double ≥ 2⁵³ теряет дробную часть → ложно считается «целым»).
 *
 * <p>Этот маппер распаковывает {@code ValNumber} в {@code java.math.BigDecimal}
 * ({@code scala.BigDecimal.bigDecimal()} — точная конвертация без округления:
 * scala держит те же unscaledValue и scale, что java). Внутрь FEEL ничего не
 * меняется — арифметика и так шла на scala BigDecimal; меняется только
 * Java-представление результата. Всё остальное (boolean/string/list/map/date)
 * проходит мимо — возвращается {@code Optional.empty()}, и работает дефолтный
 * маппер. Входная конвертация (Java → Val) не переопределяется осознанно:
 * переменные уже входят как BigDecimal/Long/Boolean
 * ({@code DmnServiceImpl.toVariableMap}, {@code ScriptServiceImpl.buildContext}).
 *
 * <p>Приёмник уже готов (WO-ENG-25): {@code ElementSupport.toProcessVariable}
 * держит {@code java.math.BigDecimal} точно (isIntegral через scale, DOUBLE
 * через toPlainString), {@code DmnServiceImpl.toBigDecimal} пропускает его
 * напрямую. Script/JSR-223-путь не тронут — у него свой движок.
 */
public class FeelBigDecimalNumberMapper extends JavaCustomValueMapper {

    @Override
    public Optional<Val> toValue(Object o, Function<Object, Val> f) {
        return Optional.empty();
    }

    @Override
    public Optional<Object> unpackValue(Val v, Function<Val, Object> f) {
        if (v instanceof ValNumber n) {
            return Optional.of(n.value().bigDecimal());
        }
        return Optional.empty();
    }
}
