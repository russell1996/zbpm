package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueryPaginationSupportTest {

    private final QueryPaginationSupport support = new QueryPaginationSupport();

    // --- MAX_PAGE_SIZE ---
    @Test
    void maxPageSize_is200() {
        assertThat(QueryPaginationSupport.MAX_PAGE_SIZE).isEqualTo(200);
    }

    // --- clampedPage ---
    @Test
    void clampedPage_defaults() {
        PageRequest pr = support.clampedPage(null, null, Sort.unsorted());
        assertThat(pr.getPageNumber()).isEqualTo(0);
        assertThat(pr.getPageSize()).isEqualTo(10);
        assertThat(pr.getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    void clampedPage_negativeClamped() {
        PageRequest pr = support.clampedPage(-5, -1, Sort.by("x"));
        assertThat(pr.getPageNumber()).isEqualTo(0);
        assertThat(pr.getPageSize()).isEqualTo(1);
    }

    @Test
    void clampedPage_capsAtMax() {
        PageRequest pr = support.clampedPage(0, 999, Sort.unsorted());
        assertThat(pr.getPageSize()).isEqualTo(200);
    }

    @Test
    void clampedPage_exactValues() {
        PageRequest pr = support.clampedPage(2, 50, Sort.by("dueAt").ascending());
        assertThat(pr.getPageNumber()).isEqualTo(2);
        assertThat(pr.getPageSize()).isEqualTo(50);
        assertThat(pr.getSort()).isEqualTo(Sort.by("dueAt").ascending());
    }

    @Test
    void clampedPage_preservesSort() {
        Sort sort = Sort.by("createdAt").descending();
        PageRequest pr = support.clampedPage(1, 10, sort);
        assertThat(pr.getSort()).isEqualTo(sort);
    }

    // --- emptyPage ---
    @Test
    void emptyPage_returnsEmpty() {
        PagedDataDTO<String> dto = support.emptyPage(new Object());
        assertThat(dto.getPageIndex()).isZero();
        assertThat(dto.getPageSize()).isEqualTo(10);
        assertThat(dto.getTotalElements()).isZero();
        assertThat(dto.getData()).isEmpty();
    }

    @Test
    void emptyPage_genericWorks() {
        PagedDataDTO<Integer> dto = support.emptyPage("query");
        assertThat(dto.getData()).isEmpty();
        assertThat(dto.getTotalElements()).isZero();
    }

    // --- toDTO ---
    @Test
    void toDTO_convertsPage() {
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 10), 2);
        PagedDataDTO<Integer> dto = support.toDTO(page, String::length);
        assertThat(dto.getTotalElements()).isEqualTo(2);
        assertThat(dto.getPageIndex()).isEqualTo(0);
        assertThat(dto.getPageSize()).isEqualTo(10);
        assertThat(dto.getData()).containsExactly(1, 1);
    }

    @Test
    void toDTO_emptyPage() {
        Page<String> page = Page.empty();
        PagedDataDTO<String> dto = support.toDTO(page, Function.identity());
        assertThat(dto.getData()).isEmpty();
        assertThat(dto.getTotalElements()).isZero();
    }

    // --- toDTOBulk ---
    @Test
    void toDTOBulk_convertsViaBulk() {
        Page<String> page = new PageImpl<>(List.of("a", "b", "c"), PageRequest.of(0, 10), 3);
        PagedDataDTO<String> dto = support.toDTOBulk(page, list -> list.stream().map(String::toUpperCase).toList());
        assertThat(dto.getData()).containsExactly("A", "B", "C");
        assertThat(dto.getTotalElements()).isEqualTo(3);
        assertThat(dto.getPageIndex()).isZero();
    }

    @Test
    void toDTOBulk_empty() {
        Page<String> page = Page.empty();
        PagedDataDTO<String> dto = support.toDTOBulk(page, list -> List.of());
        assertThat(dto.getData()).isEmpty();
    }

    // --- processInstanceInAllowedDefinitions ---
    @Test
    void processInstanceInAllowedDefinitions_createsSpec() {
        Collection<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        Specification<Object> spec = support.processInstanceInAllowedDefinitions(ids);
        assertThat(spec).isNotNull();
        Root<Object> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Subquery<UUID> subquery = mock(Subquery.class);
        Root<?> piRoot = mock(Root.class);
        Path<Object> piIdPath = mock(Path.class);
        Path<Object> processInstanceIdPath = mock(Path.class);
        lenient().when(query.subquery(UUID.class)).thenReturn((Subquery) subquery);
        lenient().when(subquery.from(any(Class.class))).thenReturn((Root) piRoot);
        lenient().when(subquery.select(any())).thenReturn(subquery);
        lenient().when(subquery.where(any(Predicate.class))).thenReturn(subquery);
        lenient().when(piRoot.get(anyString())).thenReturn((Path) piIdPath);
        lenient().when(piIdPath.in(anyCollection())).thenReturn(mock(Predicate.class));
        lenient().when(root.get(anyString())).thenReturn((Path) processInstanceIdPath);
        Predicate mockPredicate = mock(Predicate.class);
        doReturn(mockPredicate).when(processInstanceIdPath).in(any(Subquery.class));

        spec.toPredicate(root, query, cb);
        verify(query).subquery(UUID.class);
        verify(subquery).from(ProcessInstanceEntity.class);
        verify(subquery).select(any());
        verify(root).get("processInstanceId");
    }

    @Test
    void processInstanceInAllowedDefinitions_nullHandledByCaller() {
        // Direct call with empty collection should still create spec (emptyPage guard is in caller, not here)
        Specification<Object> spec = support.processInstanceInAllowedDefinitions(List.of());
        assertThat(spec).isNotNull();
    }
}
