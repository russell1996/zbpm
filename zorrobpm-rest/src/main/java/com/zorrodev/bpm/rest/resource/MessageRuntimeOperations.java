package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.MessagePublishResultDTO;
import com.zorrodev.bpm.contract.dto.PublishMessageDTO;

public interface MessageRuntimeOperations {

    /** WO-DIFF-5: publishes a message event into the engine's correlation machinery. */
    MessagePublishResultDTO publishMessage(PublishMessageDTO dto);
}
