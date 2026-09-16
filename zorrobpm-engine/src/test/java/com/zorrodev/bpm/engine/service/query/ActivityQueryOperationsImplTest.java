package com.zorrodev.bpm.engine.service.query;

import com.zorrodev.bpm.contract.model.ActivityInstance;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.mapper.ActivityInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityQueryOperationsImplTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private ActivityInstanceMapper activityInstanceMapper;
    @InjectMocks private ActivityQueryOperationsImpl impl;

    @Test
    void getActivities_mapsAndPreservesOrder() {
        UUID pi = UUID.randomUUID();
        ActivityEntity e1 = new ActivityEntity();
        e1.setId(UUID.randomUUID());
        e1.setProcessInstanceId(pi);
        e1.setCreatedAt(Instant.now().minusSeconds(10));
        ActivityEntity e2 = new ActivityEntity();
        e2.setId(UUID.randomUUID());
        e2.setProcessInstanceId(pi);
        e2.setCreatedAt(Instant.now());

        ActivityInstance dto1 = new ActivityInstance();
        dto1.setId(e1.getId());
        dto1.setProcessInstanceId(pi);
        ActivityInstance dto2 = new ActivityInstance();
        dto2.setId(e2.getId());
        dto2.setProcessInstanceId(pi);

        when(activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(eq(pi), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(e1, e2)));
        when(activityInstanceMapper.toDTO(e1)).thenReturn(dto1);
        when(activityInstanceMapper.toDTO(e2)).thenReturn(dto2);

        List<ActivityInstance> result = impl.getActivities(pi);

        assertThat(result).containsExactly(dto1, dto2);
        verify(activityRepository).findByProcessInstanceIdOrderByCreatedAtAsc(eq(pi), any(Pageable.class));
        verify(activityInstanceMapper).toDTO(e1);
        verify(activityInstanceMapper).toDTO(e2);
    }

    @Test
    void getActivities_emptyReturnsEmpty() {
        UUID pi = UUID.randomUUID();
        when(activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(eq(pi), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        List<ActivityInstance> result = impl.getActivities(pi);

        assertThat(result).isEmpty();
        verify(activityRepository).findByProcessInstanceIdOrderByCreatedAtAsc(eq(pi), any(Pageable.class));
    }

    @Test
    void getActivities_singleElement_mapped() {
        UUID pi = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity();
        e.setId(UUID.randomUUID());
        e.setProcessInstanceId(pi);
        e.setCreatedAt(Instant.now());
        ActivityInstance dto = new ActivityInstance();
        dto.setId(e.getId());
        dto.setProcessInstanceId(pi);

        when(activityRepository.findByProcessInstanceIdOrderByCreatedAtAsc(eq(pi), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(e)));
        when(activityInstanceMapper.toDTO(e)).thenReturn(dto);

        List<ActivityInstance> result = impl.getActivities(pi);

        assertThat(result).containsExactly(dto);
        verify(activityRepository).findByProcessInstanceIdOrderByCreatedAtAsc(eq(pi), any(Pageable.class));
        verify(activityInstanceMapper).toDTO(e);
    }
}
