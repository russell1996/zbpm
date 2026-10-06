package com.zorrodev.bpm.rest.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * WO-CFG-1: подключение {@code classpath:/zorrobpm-rest.properties} — конфигурации REST-модуля,
 * которая до этого WO не читалась в собранном приложении.
 *
 * <p>Причина ровно та же, что у движка (см. {@link com.zorrodev.bpm.engine.configuration.EnginePropertiesSource}):
 * файл назывался {@code application.properties} и проигрывал
 * {@code BOOT-INF/classes/application.properties} модуля zorrobpm-app — «первый на classpath
 * выигрывает», без ошибки и без предупреждения. Для REST-модуля ущерб был меньше (его файла
 * касался всего один ключ — {@code spring.rabbitmq.host}, и объявлял он то же значение, что и
 * app-файл), но механика та же: любая новая ручка, добавленная в этот файл, была бы мертва.
 *
 * <p>Почему {@code @PropertySource}, а не {@code spring.config.import}, и что именно измерено —
 * в javadoc {@code EnginePropertiesSource}; здесь причина та же, а исключение одно: в файле
 * модуля есть {@code spring.application.name=zorrobpm-engine}, и при более высоком приоритете
 * импорта это переименовало бы приложение в проде.
 *
 * <p>Соседний {@code application-prod.yml} намеренно остался под своим именем: профильный
 * документ резолвится по тому же правилу «первый на classpath», а перекрывать его нечем
 * (одноимённого файла у zorrobpm-app нет). Что он ЗАГРУЖАЕТСЯ, следует из этого правила Spring
 * плюс двух проверок на диске: ресурс лежит в classpath и ни один другой модуль такого документа
 * не заводит (обе — в {@code ModuleConfigImportGuardTest}). Отдельного теста «поднять контекст с
 * профилем prod и прочитать ключ только из этого файла» здесь нет; если перекрытие всё же
 * появится (например, zorrobpm-app заведёт свой {@code application-prod.yml}), это увидит
 * {@code noTwoModulesShipTheSameProfileDocument}.
 */
@Configuration
@PropertySource("classpath:zorrobpm-rest.properties")
public class RestPropertiesSource {
}