package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.engine.dto.Token;

import java.util.Optional;
import java.util.UUID;

/**
 * WO-DEBT-1b: домен Tokens.
 */
public interface TokenDbOperations {

    Token createToken(UUID parentId);

    Token createToken(UUID parentId, UUID scopeActivityId);

    Token getToken(UUID tokenId);

    /**
     * WO-REL-30 (B-4): non-throwing token read — callers that tolerate a missing
     * token (stale references after cancel/cleanup races) branch on empty instead
     * of catching {@code NoSuchElementException} from {@link #getToken}.
     */
    Optional<Token> findToken(UUID tokenId);

    void deleteToken(UUID tokenId);
}
