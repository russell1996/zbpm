package com.zorrodev.bpm.engine.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * WO-CFG-1: подключение {@code classpath:/zorrobpm-engine.properties} — конфигурации движка,
 * которая до этого WO не читалась в собранном приложении НИКАК.
 *
 * <p><b>Дефект.</b> Файл назывался {@code application.properties}. Spring Boot грузит
 * {@code classpath:/application.properties} как ОДИН ресурс — ПЕРВЫЙ на classpath, а в fat-jar
 * это {@code BOOT-INF/classes/application.properties} модуля zorrobpm-app. Файл движка лежал
 * внутри {@code BOOT-INF/lib/zorrobpm-engine-*.jar} и молча проигрывал: 24 ключа с
 * {@code ${ENV:default}} (retention, outbox, script-пул, business-timezone, лимиты сброса пароля,
 * io-mapping.strict-missing, servicetask.watchdog, два ключа C8-36) в задеплоенном приложении
 * просто отсутствовали в {@code Environment}. Оператор задавал переменную — приложение работало
 * на значении по умолчанию, без ошибки и без предупреждения.
 *
 * <p><b>Почему {@link PropertySource}, а не {@code spring.config.import}.</b> Импорт — тоже
 * штатный механизм, и он прямо предписан в WO как вариант, но на живом контексте измерено
 * обратное: в Spring Boot 4 импортированный документ получает приоритет <b>выше</b> файла, который
 * его импортирует. Проверено на {@code @SpringBootTest(classes = APP.class)} с дампом
 * {@code Environment.getPropertySources()}: строки «Config resource … zorrobpm-rest.properties»,
 * «… zorrobpm-engine.properties», «… application.properties» идут именно в таком порядке, и
 * {@code spring.application.name} разрешается в {@code zorrobpm-engine} вместо
 * {@code zorrobpm-app}. То есть импорт тихо переименовал бы приложение в проде (все три
 * пересекающихся ключа — {@code spring.application.name}, {@code spring.datasource.url},
 * {@code spring.rabbitmq.port} — иначе не заметил бы, что приоритет поменялся: первые два в
 * проде всегда перекрыты переменными окружения).
 *
 * <p>{@code @PropertySource} добавляет источник в конец {@code Environment.getPropertySources()},
 * то есть <b>ниже</b> всего, что приходит из config-data (приложение, профильные документы,
 * переменные окружения, параметры запуска). Это ровно те отношения, которые были до переименования
 * файла, и ровно те, которых требует этот класс. Прецедент в проекте — «клиентский» файл
 * {@code zorrobpm-client.properties}, подключённый точно так же в
 * {@code com.zorrodev.bpm.client.configuration.ClientConfiguration}.
 *
 * <p>Файл переименован, а не перемещён в app: граница модулей на месте, у движка остаётся своя
 * конфигурация по умолчанию для сборки, где zorrobpm-app не участвует, и новая ручка добавляется
 * туда же, куда добавляли все предыдущие.
 *
 * <p>Имя файла вернуть в {@code application.properties} НЕЛЬЗЯ — перекрытие вернётся молча.
 * Сторожевой тест {@code ModuleConfigImportGuardTest} краснеет на этом.
 */
@Configuration
@PropertySource("classpath:zorrobpm-engine.properties")
public class EnginePropertiesSource {
}