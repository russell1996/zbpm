package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.*;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ADR-2: One API key per user + per-process grants.
 * Super-admin manages any user's key/grants.
 * User manages own key (view/rotate/revoke/issue, and grants on processes the user is a member of — WO-ACL-5).
 */
public interface ApiKeyManagementContract {

    // ==================== Super-admin endpoints ====================

    @PostExchange("/admin/users/{userId}/api-key")
    ApiKeyWithSecretDTO createApiKey(@PathVariable UUID userId);

    @GetExchange("/admin/users/{userId}/api-key")
    ApiKeyDTO getApiKey(@PathVariable UUID userId);

    @PutExchange("/admin/users/{userId}/api-key/grants")
    List<ApiKeyGrantDTO> setGrants(@PathVariable UUID userId, @RequestBody SetGrantsDTO dto);

    @PostExchange("/admin/users/{userId}/api-key/rotate")
    ApiKeyWithSecretDTO rotateApiKey(@PathVariable UUID userId);

    @PostExchange("/admin/users/{userId}/api-key/revoke")
    void revokeApiKey(@PathVariable UUID userId);

    // ==================== User self-service endpoints ====================

    @GetExchange("/me/api-key")
    ApiKeyDTO getMyApiKey();

    /**
     * WO-ACL-5 criterion #1: a user issues their own API key (secret shown once).
     * One active key per user — second issue while active → 409.
     */
    @PostExchange("/me/api-key")
    ApiKeyWithSecretDTO createMyApiKey();

    /**
     * WO-ACL-5 criterion #2: a user sets grants for their own key, but only on
     * processes they are a member of (a grant on an inaccessible process → 400).
     */
    @PutExchange("/me/api-key/grants")
    List<ApiKeyGrantDTO> setMyGrants(@RequestBody SetGrantsDTO dto);

    @PostExchange("/me/api-key/rotate")
    ApiKeyWithSecretDTO rotateMyApiKey();

    @PostExchange("/me/api-key/revoke")
    void revokeMyApiKey();
}
