package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.DmnDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DmnDefinitionRepository extends JpaRepository<DmnDefinitionEntity, String> {
}
