package com.zorrodev.bpm.contract.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Общий конверт постраничных ответов ({@code pageIndex}, {@code pageSize},
 * {@code totalElements}, {@code data}).
 *
 * <p><b>WO-AUDIT-14 (F-18): {@code totalElements} — это размер ВЫДАННОЙ
 * страницы, а не число элементов во всей выборке.</b> Так его отдают все
 * эндпоинты проекта (постраничная выборка из БД без отдельного COUNT), и
 * менять смысл поля у 13 эндпоинтов — это ломающий контракт, а не гигиена.
 * Клиенту, которому нужно общее число, считать его самому или использовать
 * пагинацию до конца. Для курсорной ленты событий ({@code GET /events})
 * «есть ли ещё» отдаётся отдельными полями {@link #hasMore} /
 * {@link #nextCursor} — это и есть ответ на вопрос «а не потерял ли я
 * хвост?»; {@code totalElements} там равен размеру страницы, как и везде.
 *
 * <p>{@link #hasMore} и {@link #nextCursor} — <b>необязательные</b> поля:
 * они помечены {@code @JsonInclude(NON_NULL)}, поэтому у всех эндпоинтов,
 * которые их не заполняют, они просто не сериализуются, и их ответы остаются
 * байтово прежними. Заполняет их только курсорный {@code /events}.
 */
@Getter
@Setter
public class PagedDataDTO<T> {
    private Integer pageIndex;
    private Integer pageSize;
    private Long totalElements;
    private List<T> data;

    /**
     * Есть ли ещё элементы за пределами выданной страницы.
     * {@code null} = эндпоинт не курсорный (поле не сериализуется).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean hasMore;

    /**
     * Курсор следующей страницы (для курсорной ленты — feed-позиция
     * последнего выданного события). {@code null} = следующей страницы нет
     * либо эндпоинт не курсорный (поле не сериализуется).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long nextCursor;
}
