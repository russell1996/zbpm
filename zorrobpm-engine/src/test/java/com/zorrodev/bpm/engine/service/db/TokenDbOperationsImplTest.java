package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenDbOperationsImplTest {

    @Mock private TokenRepository tokenRepository;
    @InjectMocks private TokenDbOperationsImpl db;

    @Test
    void createToken_withParent_saves() {
        UUID parent = UUID.randomUUID();
        Token result = db.createToken(parent);
        ArgumentCaptor<TokenEntity> captor = ArgumentCaptor.forClass(TokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getParentId()).isEqualTo(parent);
        assertThat(result.getParentId()).isEqualTo(parent);
    }

    @Test
    void createToken_withScope_savesScope() {
        UUID parent = UUID.randomUUID(); UUID scope = UUID.randomUUID();
        Token result = db.createToken(parent, scope);
        ArgumentCaptor<TokenEntity> captor = ArgumentCaptor.forClass(TokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getScopeActivityId()).isEqualTo(scope);
        assertThat(result.getScopeActivityId()).isEqualTo(scope);
    }

    @Test
    void getToken_maps() {
        // CTO (WO-DEBT-1e review): asserts all 4 toToken fields, not just id — the previous
        // version only checked id, so a mutation dropping parentId/scopeActivityId/pendingBranches
        // from the mapping would not have failed any test (createToken_persistsPendingBranchesNull
        // below can't catch this either: createToken never sets pendingBranches on the entity in
        // the first place, so it's always null there regardless of whether toToken maps it).
        UUID id = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID scopeActivityId = UUID.randomUUID();
        TokenEntity e = new TokenEntity();
        e.setId(id);
        e.setParentId(parentId);
        e.setScopeActivityId(scopeActivityId);
        e.setPendingBranches(3);
        when(tokenRepository.findById(id)).thenReturn(Optional.of(e));
        Token result = db.getToken(id);
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getParentId()).isEqualTo(parentId);
        assertThat(result.getScopeActivityId()).isEqualTo(scopeActivityId);
        assertThat(result.getPendingBranches()).isEqualTo(3);
    }

    @Test
    void deleteToken_deletes() {
        UUID id = UUID.randomUUID();
        db.deleteToken(id);
        verify(tokenRepository).deleteById(id);
    }

    @Test
    void createToken_persistsPendingBranchesNull() {
        UUID parent = UUID.randomUUID();
        Token result = db.createToken(parent, null);
        assertThat(result.getPendingBranches()).isNull();
        verify(tokenRepository).save(any(TokenEntity.class));
    }
}
