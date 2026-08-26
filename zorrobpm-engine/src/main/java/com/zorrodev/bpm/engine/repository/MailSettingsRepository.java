package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.MailSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MailSettingsRepository extends JpaRepository<MailSettingsEntity, UUID> {
    Optional<MailSettingsEntity> findFirstByOrderByIdAsc();
}
