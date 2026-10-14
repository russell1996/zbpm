# Воркеры, ретраи, инциденты и HTTP-коннектор

> Как исполняется сервис-задача, как писать воркер и что происходит при ошибке. Вход в документацию — [README](../../README.md), карта — [docs/README.md](../README.md).

## Сервис-задачи: написание воркера

Сервис-задача в BPMN объявляет **тип job** (в стиле Zeebe `taskDefinition` type). Создайте приложение-воркер:

1. Добавьте зависимость:

```xml
<dependency>
  <groupId>com.zorrodev.bpm</groupId>
  <artifactId>zorrobpm-job-handler-spring-boot-starter</artifactId>
  <version>0.8.0-SNAPSHOT</version>
</dependency>
```

2. Реализуйте обработчик на каждый тип job:

```java
@Component
public class ChargeHandler implements JobHandler {
    @Override public String getJob() { return "charge-card"; }   // совпадает с типом job у задачи

    @Override public List<ProcessVariable> handleJob(JobDetailModel job) {
        // ... работа с job.getVariables() ...
        ProcessVariable result = new ProcessVariable();
        result.setName("chargeStatus"); result.setType("STRING"); result.setValue("OK");
        return List.of(result);
    }
}
```

Стартер автоматически подписывается на `zorrobpm.jobs.<job>` и публикует завершение в `zorrobpm.complete-service-task`. Воркер должен смотреть на тот же брокер RabbitMQ, что и движок.

## Ошибки воркера и инциденты

Движок и воркер развязаны через RabbitMQ — об ошибке воркер сообщает **обратным событием** (тем же каналом `zorrobpm.complete-service-task`), просто с `status="FAILED"` и текстом ошибки. Движок сам ведёт счётчик попыток и решает: повторить или создать инцидент. Модель — как в Camunda («fail job» с retries → инцидент).

**Откуда движок знает, что за ошибка:** только воркер видел исключение — поэтому он кладёт его в сообщение. Текст ошибки (`errorMessage`) пишется воркером из пойманного исключения; движок добавляет контекст («где»: service-task, инстанс) и сохраняет всё в инцидент.

**Кол-во повторов** задаётся на задаче (`zeebe:taskDefinition retries="3"`, по умолчанию **3**):

```xml
<bpmn:serviceTask id="charge">
  <bpmn:extensionElements>
    <zeebe:taskDefinition type="charge-card" retries="3" />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

**Через SDK** (рекомендуется) — просто бросьте исключение из `handleJob`; стартер сам отправит `FAILED` с текстом исключения:

```java
@Override public List<ProcessVariable> handleJob(JobDetailModel job) {
    throw new IllegalStateException("downstream 503");   // → движок применит retries → при 0 создаст инцидент
}
```

**Через REST** (для не-RabbitMQ интеграций) — сообщить о сбое напрямую:

```bash
# по умолчанию: уменьшить бюджет попыток на 1 (инцидент — когда дойдёт до 0)
curl -X POST http://localhost:8080/service-tasks/<SERVICE_TASK_ID>/fail \
  -H 'Content-Type: application/json' \
  -d '{"message":"downstream 503"}'

# retries=0 (как Camunda failJob) — инцидент сразу, независимо от оставшегося бюджета
curl -X POST http://localhost:8080/service-tasks/<SERVICE_TASK_ID>/fail \
  -H 'Content-Type: application/json' \
  -d '{"message":"fatal: bad config","retries":0}'
```

Поле `retries` необязательное: если указать — бюджет попыток **выставляется** в это значение (`0` → инцидент немедленно; `>0` → задача переотправляется с этим бюджетом); если не указывать — бюджет **уменьшается на 1**.

**Что происходит:**
1. На каждый `FAILED` движок уменьшает `retries_remaining` сервис-задачи.
2. Пока `> 0` — задача **переотправляется** воркеру (тот же job-эвент); инцидент НЕ создаётся.
3. При `0` — активность помечается `ERROR` и создаётся **инцидент** с текстом ошибки воркера; токен остаётся припаркованным.
4. Оператор смотрит инцидент через `GET /incidents` (поля: `activityId`, `message`, `createdAt`).
5. **Resolve** — `POST /incidents/{id}/resolve` (можно передать переменные) → задача исполняется заново (свежий бюджет retries) и при успехе процесс идёт дальше.

> Бизнес-ошибки (ожидаемые исходы) — это **не** инцидент: их моделируют через **error boundary / event-subprocess** по `errorCode`, а не через `FAILED`.


(Подробности встроенного HTTP-коннектора — [docs/guides/http-connector.md](../guides/http-connector.md).)
