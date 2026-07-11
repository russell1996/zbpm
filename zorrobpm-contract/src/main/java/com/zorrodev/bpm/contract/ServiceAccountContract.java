package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CreateServiceAccountDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ServiceAccountDTO;
import com.zorrodev.bpm.contract.dto.ServiceAccountWithKeyDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

public interface ServiceAccountContract {

    @PostExchange("/processes/{key}/service-accounts")
    ServiceAccountWithKeyDTO createServiceAccount(@PathVariable String key, @RequestBody CreateServiceAccountDTO dto);

    @GetExchange("/processes/{key}/service-accounts")
    List<ServiceAccountDTO> listServiceAccounts(@PathVariable String key);

    @PostExchange("/processes/{key}/service-accounts/{id}/rotate")
    ServiceAccountWithKeyDTO rotateServiceAccountKey(@PathVariable String key, @PathVariable UUID id);

    @PostExchange("/processes/{key}/service-accounts/{id}/revoke")
    IdDTO revokeServiceAccount(@PathVariable String key, @PathVariable UUID id);
}
