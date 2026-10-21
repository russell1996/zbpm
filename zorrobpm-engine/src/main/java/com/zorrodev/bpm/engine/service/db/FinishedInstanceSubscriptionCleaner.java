package com.zorrodev.bpm.engine.service.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WO-OPS-34: зачистка instance-scoped подписок завершённого инстанса.
 *
 * <p><b>Симптом (WO-OPS-32 §2, корень F2 — 17 нарушений).</b> Инстанс, который
 * нормально завершился ({@code completed_at}, НЕ отменён), оставлял в БД
 * {@code message_subscriptions}/{@code signal_subscriptions} с
 * {@code consumed = false}. Инстанс мёртв — доставка ему не нужна, а сообщение,
 * пришедшее после завершения, не должно уходить в мёртвый инстанс (в Zeebe 8.5
 * подписка снимается вместе со scope).
 *
 * <p><b>Кто и почему не чистил (до WO-OPS-38).</b>
 * <ul>
 *   <li>message-подписки — удалялись ТОЛЬКО в kill-пути
 *       ({@code InstanceCancellationService.kill}) и в операторской отмене
 *       ({@code ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance}),
 *       то есть НИКОГДА при нормальном завершении;</li>
 *   <li>signal-подписки — не удалял НИКТО: ни kill-путь, ни отмена. C37-3
 *       ({@code ActivityDbOperationsImpl.applyCompletion}) гасит
 *       message-подписки завершённого ХОСТА, но не signal-подписки — поэтому
 *       signal-boundary переживала и хост, и инстанс;</li>
 *   <li>event-subprocess-подписки (message и signal, {@code activity_id = NULL})
 *       — переживают любой ОТДЕЛЬНЫЙ хост по замыслу («outlive any single host
 *       by design»), значит единственное место, где они умирают, — терминальный
 *       переход инстанса.</li>
 * </ul>
 *
 * <p><b>WO-OPS-38: правило распространено на отмену.</b> Kill-путь и
 * операторская отмена снимали ТОЛЬКО message-подписки — signal-подписки
 * отменённого/убитого инстанса оставались (broadcast сигнала уходил в мёртвый
 * инстанс). Теперь оба пути идут через тот же компонент: зачистка стоит в
 * ТЕРМИНАЛЬНОМ переходе отмены ({@code ProcessInstanceDbOperationsImpl
 * .cancelProcessInstance}) — рядом с зачисткой при нормальном завершении.
 * Асимметрия путей закрыта; правило «терминальный инстанс не держит подписок»
 * — одно.
 *
 * <p><b>Почему это отдельный узкий компонент, а не ещё один метод в
 * {@code ProcessInstanceDbOperationsImpl}.</b> Подписки — чужой домен
 * (message/signal), и правило «терминальный инстанс не держит подписок» должно
 * быть ОДНИМ, а не размазанным по путям. Сейчас этот компонент зовут оба
 * терминальных перехода инстанса: нормальное завершение (WO-OPS-34) и отмена
 * (WO-OPS-38). Прецедент в этом же пакете — {@code CancelledScopeStateCleaner}
 * (WO-C8-46): модельная половина зачистки отмены тоже вынесена из класса
 * инстансов.
 *
 * <p><b>Почему зачистка НЕ в фазе коммита</b> (в отличие от arrival-строк
 * join-ов, WO-C8-48).* Arrival-строки читает резюм припаркованных join-ов,
 * который работает ПОСЛЕ терминальной пометки, в той же транзакции, — им надо
 * дожить до конца хвоста деактивации. Подписки никто в хвосте деактивации не
 * читает: их читает ТОЛЬКО доставка (корреляция сообщения / broadcast сигнала),
 * и доставка обязана НЕ ДОХОДИТЬ до мёртвого инстанса. Поэтому подписки снимаются
 * сразу, в терминальной транзакции, — иначе инвариант утечек видел бы их и в
 * прод-состоянии, и в @Transactional-тесте.
 *
 * <p><b>Порядок внутри терминального перехода.</b> Зачистка идёт ПОСЛЕ терминальной
 * пометки инстанса: доставка, которая успела забрать подписку миллисекундой
 * раньше, упирается в guard «instance already completed»
 * ({@code EventTrigger.triggerEventSubprocess}) и гасится. Обратный порядок
 * (снести подписки ДО пометки) оставил бы окно, в котором инстанс ещё «жив»,
 * а доставка не-interrupting event-subprocess (она не consume-ит подписку, а
 * зовёт триггер напрямую) успела бы запустить handler в умирающем инстансе.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinishedInstanceSubscriptionCleaner {

    private final MessageSubscriptionDbOperations messageSubscriptionDbOperations;
    private final SignalSubscriptionDbOperations signalSubscriptionDbOperations;

    /**
     * Снимает все instance-scoped подписки инстанса (message и signal).
     *
     * @param processInstanceId инстанс, ушедший в терминальное состояние
     */
    public void clear(UUID processInstanceId) {
        messageSubscriptionDbOperations.deleteMessageSubscriptionsByProcessInstanceId(processInstanceId);
        signalSubscriptionDbOperations.deleteSignalSubscriptionsByProcessInstanceId(processInstanceId);
        log.info("{}: terminal instance — dropped its message/signal subscriptions", processInstanceId);
    }
}
