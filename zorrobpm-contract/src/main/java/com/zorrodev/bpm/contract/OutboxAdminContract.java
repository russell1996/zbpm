package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.OutboxEntryDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

public interface OutboxAdminContract {

    @GetExchange("/admin/outbox")
    List<OutboxEntryDTO> getOutbox(@RequestParam(required = false) String status);

    @PostExchange("/admin/outbox/{id}/redrive")
    OutboxEntryDTO redriveOutbox(@PathVariable UUID id);
}
