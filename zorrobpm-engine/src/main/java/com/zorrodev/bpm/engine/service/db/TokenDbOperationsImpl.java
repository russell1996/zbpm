package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-1e: домен Tokens — реализация.
 * Перенесено 1:1 из DBServiceImpl (4 метода + toToken).
 */
@Service
@RequiredArgsConstructor
public class TokenDbOperationsImpl implements TokenDbOperations {

    private final TokenRepository tokenRepository;

    @Override
    public Token createToken(UUID parentId) {
        return createToken(parentId, null);
    }

    @Override
    public Token createToken(UUID parentId, UUID scopeActivityId) {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(UUID.randomUUID());
        tokenEntity.setParentId(parentId);
        tokenEntity.setScopeActivityId(scopeActivityId);
        tokenRepository.save(tokenEntity);

        return toToken(tokenEntity);
    }

    @Override
    public Token getToken(UUID tokenId) {
        return findToken(tokenId).orElseThrow();
    }

    @Override
    public Optional<Token> findToken(UUID tokenId) {
        return tokenRepository.findById(tokenId).map(this::toToken);
    }

    @Override
    public void deleteToken(UUID tokenId) {
        tokenRepository.deleteById(tokenId);
    }

    private Token toToken(TokenEntity entity) {
        Token token = new Token();
        token.setId(entity.getId());
        token.setParentId(entity.getParentId());
        token.setScopeActivityId(entity.getScopeActivityId());
        token.setPendingBranches(entity.getPendingBranches());
        return token;
    }
}
