package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.Token;

import java.util.UUID;

/**
 * WO-DEBT-1b: домен Tokens.
 */
public interface TokenDbOperations {

    Token createToken(UUID parentId);

    Token createToken(UUID parentId, UUID scopeActivityId);

    Token getToken(UUID tokenId);

    void deleteToken(UUID tokenId);
}
