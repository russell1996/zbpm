package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.AuditLogEntity;
import jakarta.persistence.criteria.Predicate;
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
}
