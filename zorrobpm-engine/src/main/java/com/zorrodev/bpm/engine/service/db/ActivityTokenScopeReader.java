package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WO-C8-46: узкий ридер «activity-строка → scopeId её токена» для зачистки
 * отмены. Null = строка/токен на root (или строка чужая — вызывающий передаёт
 * только свои ids, см. {@code CancelledScopeStateCleaner}).
 */
@Service
public class ActivityTokenScopeReader {

    private final ActivityRepository activityRepository;
    private final TokenRepository tokenRepository;

    public ActivityTokenScopeReader(ActivityRepository activityRepository,
            TokenRepository tokenRepository) {
        this.activityRepository = activityRepository;
        this.tokenRepository = tokenRepository;
    }

    public UUID scopeOf(@NonNull UUID processInstanceId, @NonNull UUID activityId) {
        return activityRepository.findById(activityId)
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()))
            .map(a -> tokenRepository.findById(a.getToken())
                .map(com.zorrodev.bpm.engine.entity.TokenEntity::getScopeActivityId)
                .orElse(null))
            .orElse(null);
    }
}
