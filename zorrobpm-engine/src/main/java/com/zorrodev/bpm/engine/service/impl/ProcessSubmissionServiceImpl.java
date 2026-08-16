package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.ProcessRole;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionStatus;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.ProcessSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WO-ACL-3 implementation. Submission keeps the BPMN in the DB (no filesystem state);
 * approval deploys it through the regular {@link ProcessDefinitionService} path and registers
 * the submitter as OWNER — all inside ONE transaction so a failure cannot leave an orphaned
 * version or an ownerless process (see {@link #approve(UUID, UUID)}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessSubmissionServiceImpl implements ProcessSubmissionService {

    /** 256 KiB — submissions are review payloads, not deployment artifacts (WO-ACL-3). */
    static final int MAX_BPMN_LENGTH = 262_144;

    /**
     * Naming convention for NEW process keys (WO-ACL-3): lowercase letter first, then
     * lowercase letters/digits/underscore/hyphen, 3..64 chars. Underscore is allowed because
     * existing registry keys use it (mt7_*, acl2_*); dots and uppercase are not (URL/extension
     * ambiguity, XML id case-sensitivity, uniformity).
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{2,63}$");

    private static final String KEY_CONVENTION_MESSAGE =
        "Process key must match the naming convention: ^[a-z][a-z0-9_-]{2,63}$ "
            + "(3-64 characters, starts with a lowercase letter, lowercase/digits/underscore/hyphen only)";

    private final ProcessSubmissionRepository submissionRepository;
    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final ProcessDefinitionService processDefinitionService;
    private final BpmnParseService bpmnParseService;
    private final AuditLogService auditLogService;

    @Override
    public ProcessSubmissionDTO submit(String bpmn, Principal principal) {
        if (bpmn == null || bpmn.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BPMN XML is required");
        }
        if (bpmn.length() > MAX_BPMN_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "BPMN XML exceeds the 256 KB submission limit");
        }

        BpmnProcessDefinitionModel model;
        try {
            model = bpmnParseService.parse(bpmn);
        } catch (BpmnParseException e) {
            // Deliberately NOT exposing parser internals (WO-SEC-33 pattern): a rejected
            // submission must be actionable for the user, not a stack dump.
            log.warn("Submission rejected: BPMN does not parse (submitter={})", principalId(principal));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "BPMN could not be parsed — fix the XML and resubmit");
        }

        String key = model.getKey();

        // Registry check BEFORE the naming convention: an existing key means "update the
        // deployed process", which this flow deliberately does not offer (WO-ACL-4 is the
        // in-process update path). The message must tell the user where to go.
        if (processRepository.findByDefinitionKey(key).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Process with key '" + key + "' already exists — update the model from inside "
                    + "the process, or request access from its owner");
        }

        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid process key '" + key + "': " + KEY_CONVENTION_MESSAGE);
        }

        ProcessSubmissionEntity entity = new ProcessSubmissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setBpmn(bpmn);
        entity.setProcessKey(key);
        entity.setName(model.getName());
        entity.setSubmittedBy(principalId(principal));
        entity.setSubmittedAt(Instant.now());
        entity.setStatus(ProcessSubmissionStatus.PENDING.name());

        // Resubmission chain: link to the user's previous submission of the same key.
        submissionRepository.findFirstBySubmittedByAndProcessKeyOrderBySubmittedAtDesc(
                entity.getSubmittedBy(), key)
            .ifPresent(prev -> entity.setPreviousSubmissionId(prev.getId()));

        submissionRepository.save(entity);
        auditLogService.record(principal, "SUBMISSION_SUBMIT", key, entity.getId().toString());
        log.info("Process submission {} created (key={}, submitter={})", entity.getId(), key, entity.getSubmittedBy());
        return toDTO(entity);
    }

    @Override
    public List<ProcessSubmissionDTO> listMine(UUID userId) {
        return submissionRepository.findBySubmittedByOrderBySubmittedAtDesc(userId).stream()
            .map(this::toDTO)
            .toList();
    }

    @Override
    public List<ProcessSubmissionDTO> listPending() {
        return submissionRepository.findByStatusOrderBySubmittedAtAsc(
                ProcessSubmissionStatus.PENDING.name()).stream()
            .map(this::toDTO)
            .toList();
    }

    /**
     * WO-ACL-3 criterion 4 — the atomicity guarantee this WO exists for:
     * deploy + registry process + OWNER membership + submission status update happen in ONE
     * transaction. A failure after the version was created rolls everything back: no orphaned
     * process_definition, no process without an owner, submission stays PENDING for a retry.
     *
     * {@code ProcessDefinitionServiceImpl.addProcessDefinition} joins this transaction
     * (its TransactionTemplate defaults to PROPAGATION_REQUIRED), so its DB artifacts commit
     * or roll back together with ours. (Known limitation, not in this WO's scope: the BPMN
     * file written by FileService sits on disk outside any transaction and survives a rollback.)
     */
    @Transactional
    @Override
    public ProcessSubmissionDTO approve(UUID submissionId, Principal admin) {
        UUID adminId = adminUser(admin);
        ProcessSubmissionEntity submission = requirePending(submissionId);
        String key = submission.getProcessKey();

        // Registry check first for a clear error message; the version check after the deploy
        // is the race guard (first approval wins).
        if (processRepository.findByDefinitionKey(key).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Process with key '" + key + "' was already created — the first approval wins");
        }

        var created = processDefinitionService.addProcessDefinition(submission.getBpmn());

        // Race guard: a concurrent approval may have deployed the same key between our registry
        // check and the deploy. If OUR deploy produced version > 1, someone else won — rolling
        // back our version is the only correct outcome.
        if (created.getVersion() != null && created.getVersion() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Process with key '" + key + "' was already created — the first approval wins");
        }

        ProcessEntity process = new ProcessEntity();
        process.setId(UUID.randomUUID());
        process.setDefinitionKey(key);
        process.setName(submission.getName() != null ? submission.getName() : key);
        process.setCreatedAt(Instant.now());
        processRepository.save(process);

        // ADR-8 п.3: the submitter becomes OWNER of the approved process — they asked for it.
        ProcessMemberEntity owner = new ProcessMemberEntity();
        owner.setProcessId(process.getId());
        owner.setUserId(submission.getSubmittedBy());
        owner.setRole(ProcessRole.OWNER.name());
        owner.setAddedBy(adminId);
        owner.setAddedAt(Instant.now());
        processMemberRepository.save(owner);

        submission.setStatus(ProcessSubmissionStatus.APPROVED.name());
        submission.setReviewedBy(adminId);
        submission.setReviewedAt(Instant.now());
        submission.setApprovedDefinitionId(created.getId());
        submissionRepository.save(submission);

        auditLogService.record(admin, "SUBMISSION_APPROVE", key, submissionId.toString());
        log.info("Process submission {} approved: deployed {} v{} and made {} OWNER of {}",
            submissionId, key, created.getVersion(), submission.getSubmittedBy(), process.getId());
        return toDTO(submission);
    }

    @Transactional
    @Override
    public ProcessSubmissionDTO reject(UUID submissionId, String reason, Principal admin) {
        UUID adminId = adminUser(admin);
        ProcessSubmissionEntity submission = requirePending(submissionId);

        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reject reason is required");
        }
        if (reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Reject reason must be at most 500 characters");
        }

        submission.setStatus(ProcessSubmissionStatus.REJECTED.name());
        submission.setReviewedBy(adminId);
        submission.setReviewedAt(Instant.now());
        submission.setRejectReason(reason.trim());
        submissionRepository.save(submission);

        auditLogService.record(admin, "SUBMISSION_REJECT",
            submission.getProcessKey(), submissionId.toString());
        log.info("Process submission {} rejected by {}", submissionId, adminId);
        return toDTO(submission);
    }

    private ProcessSubmissionEntity requirePending(UUID submissionId) {
        ProcessSubmissionEntity submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));
        if (!ProcessSubmissionStatus.PENDING.name().equals(submission.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Submission was already reviewed");
        }
        return submission;
    }

    private UUID principalId(Principal principal) {
        if (principal instanceof Principal.UserPrincipal u) {
            return u.userId();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Process submissions are a user self-service — API keys cannot submit");
    }

    /** Admin actions are recorded with the admin's principal; only users may review. */
    private UUID adminUser(Principal admin) {
        return principalId(admin);
    }

    private ProcessSubmissionDTO toDTO(ProcessSubmissionEntity entity) {
        ProcessSubmissionDTO dto = new ProcessSubmissionDTO();
        dto.setId(entity.getId());
        dto.setProcessKey(entity.getProcessKey());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setSubmittedBy(entity.getSubmittedBy());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setReviewedBy(entity.getReviewedBy());
        dto.setReviewedAt(entity.getReviewedAt());
        dto.setRejectReason(entity.getRejectReason());
        dto.setApprovedDefinitionId(entity.getApprovedDefinitionId());
        dto.setPreviousSubmissionId(entity.getPreviousSubmissionId());
        return dto;
    }
}