package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID>, JpaSpecificationExecutor<AuditLogEntity> {

    default List<AuditLogEntity> findByFilters(String processKey, UUID ownerUserId,
                                               Instant fromTime, Instant toTime) {
        Specification<AuditLogEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (processKey != null) {
                predicates.add(cb.equal(root.get("processKey"), processKey));
            }
            if (ownerUserId != null) {
                predicates.add(cb.equal(root.get("ownerUserId"), ownerUserId));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("at"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("at"), toTime));
            }
            query.orderBy(cb.desc(root.get("at")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findAll(spec);
    }

    /**
     * WO-AUDIT-3 (P2): the same filters as {@link #findByFilters} but as a bounded
     * keyset window — newest first. Cursor is the exclusive (at, id) of the last row
     * of the previous page; the id tiebreak keeps rows sharing an instant from being
     * skipped or duplicated at page boundaries. {@code limit} is the raw window size
     * (callers pass pageSize + 1 and derive {@code hasMore}).
     */
    default List<AuditLogEntity> findPage(String processKey, UUID ownerUserId,
                                          Instant fromTime, Instant toTime,
                                          Instant cursorAtExclusive, UUID cursorIdExclusive,
                                          int limit) {
        Specification<AuditLogEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (processKey != null) {
                predicates.add(cb.equal(root.get("processKey"), processKey));
            }
            if (ownerUserId != null) {
                predicates.add(cb.equal(root.get("ownerUserId"), ownerUserId));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("at"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("at"), toTime));
            }
            if (cursorAtExclusive != null) {
                Predicate before = cb.lessThan(root.get("at"), cursorAtExclusive);
                if (cursorIdExclusive != null) {
                    Predicate sameInstant = cb.and(
                        cb.equal(root.get("at"), cursorAtExclusive),
                        cb.lessThan(root.get("id"), cursorIdExclusive));
                    predicates.add(cb.or(before, sameInstant));
                } else {
                    predicates.add(before);
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return findBy(spec, q -> q
            .sortBy(Sort.by(Sort.Direction.DESC, "at").and(Sort.by(Sort.Direction.DESC, "id")))
            .limit(limit)
            .all());
    }
}
