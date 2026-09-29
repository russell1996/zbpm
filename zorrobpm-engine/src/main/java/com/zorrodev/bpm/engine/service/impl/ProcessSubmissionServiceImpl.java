package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.ProcessRole;
import com.zorrodev.bpm.contract.dto.ProcessSubmissionDTO;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.entity.ProcessEntity;
import com.zorrodev.bpm.engine.entity.ProcessMemberEntity;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionEntity;
import com.zorrodev.bpm.engine.entity.ProcessSubmissionStatus;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.ProcessMemberRepository;
import com.zorrodev.bpm.engine.repository.ProcessRepository;
import com.zorrodev.bpm.engine.repository.ProcessSubmissionRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.ProcessSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Naming convention for NEW process keys (WO-ACL-3, updated WO-ACL-9): letter first (any case),
     * then letters/digits/underscore/hyphen, 3..64 chars. Underscore is allowed because
     * existing registry keys use it (mt7_*, acl2_*); dots are not (URL/extension ambiguity).
     * Uppercase is now allowed — BPMN id is case-sensitive, and camelCase keys like
     * approvalProcess are standard Camunda convention.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{2,63}$");

    private static final String KEY_CONVENTION_MESSAGE =
        "Process key must match the naming convention: ^[a-zA-Z][a-zA-Z0-9_-]{2,63}$ "
            + "(3-64 characters, starts with a letter, letters/digits/underscore/hyphen only, case-sensitive)";

    private final ProcessSubmissionRepository submissionRepository;
    private final ProcessRepository processRepository;
    private final ProcessMemberRepository processMemberRepository;
    private final ProcessDefinitionService processDefinitionService;
    private final BpmnParseService bpmnParseService;
    private final AuditLogService auditLogService;
    private final UiUserRepository uiUserRepository;

    @Override
    public ProcessSubmissionDTO submit(String bpmn, Principal principal) {
        if (bpmn == null || bpmn.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BPMN XML is required");
        }
        if (bpmn.length() > MAX_BPMN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BPMN_TOO_LARGE",
                "BPMN XML exceeds the 256 KB submission limit",
                Map.of("maxLength", MAX_BPMN_LENGTH, "actualLength", bpmn.length()));
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
            throw new ApiException(HttpStatus.CONFLICT, "PROCESS_ALREADY_EXISTS",
                "Process with key '" + key + "' already exists — update the model from inside "
                    + "the process, or request access from its owner",
                Map.of("processKey", key));
        }

        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROCESS_KEY",
                "Invalid process key '" + key + "': " + KEY_CONVENTION_MESSAGE,
                Map.of("key", key));
        }

        // WO-ACL-12 criterion 1: one PENDING per process key. This check only renders a clear
        // 409 — the GUARANTEE is the partial unique index uk_process_submission__pending_key
        // (the check alone loses to parallel submits; the index converts the race into the
        // same 409 instead of a 500, see the DataIntegrityViolationException catch below).
        if (submissionRepository.existsByProcessKeyAndStatus(key, ProcessSubmissionStatus.PENDING.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "PENDING_SUBMISSION_EXISTS",
                "A submission for process key '" + key + "' is already pending review — "
                    + "wait for the decision before resubmitting",
                Map.of("processKey", key));
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

        try {
            submissionRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // WO-ACL-12 criterion 2: a concurrent submit created a PENDING for this key
            // between our existence check and the save — the partial unique index wins the
            // race. Translate the constraint violation into the same user-facing 409.
            if (submissionRepository.existsByProcessKeyAndStatus(key, ProcessSubmissionStatus.PENDING.name())) {
                throw new ApiException(HttpStatus.CONFLICT, "PENDING_SUBMISSION_EXISTS",
                    "A submission for process key '" + key + "' is already pending review — "
                        + "wait for the decision before resubmitting",
                    Map.of("processKey", key));
            }
            throw e;
        }
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
    public List<ProcessSubmissionDTO> listPending(String status) {
        String effective = (status == null || status.isBlank())
            ? ProcessSubmissionStatus.PENDING.name()
            : status.trim().toUpperCase(java.util.Locale.ROOT);
        if ("ALL".equals(effective)) {
            return submissionRepository.findAllByOrderBySubmittedAtAsc().stream()
                .map(this::toDTO)
                .toList();
        }
        try {
            ProcessSubmissionStatus.valueOf(effective);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown submission status filter: '" + status + "' (expected PENDING, APPROVED, "
                    + "REJECTED, SUPERSEDED or ALL)");
        }
        return submissionRepository.findByStatusOrderBySubmittedAtAsc(effective).stream()
            .map(this::toDTO)
            .toList();
    }

    @Override
    public String getBpmn(UUID submissionId) {
        return submissionRepository.findById(submissionId)
            .map(ProcessSubmissionEntity::getBpmn)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND",
                "Submission not found", Map.of("submissionId", submissionId)));
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
            throw new ApiException(HttpStatus.CONFLICT, "PROCESS_ALREADY_EXISTS",
                "Process with key '" + key + "' was already created — the first approval wins",
                Map.of("processKey", key));
        }

        var created = processDefinitionService.addProcessDefinition(submission.getBpmn());

        // Race guard: a concurrent approval may have deployed the same key between our registry
        // check and the deploy. If OUR deploy produced version > 1, someone else won — rolling
        // back our version is the only correct outcome.
        if (created.getVersion() != null && created.getVersion() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "PROCESS_ALREADY_EXISTS",
                "Process with key '" + key + "' was already created — the first approval wins",
                Map.of("processKey", key));
        }

        // WO-ENG-18: the registry row already exists — addProcessDefinition above funnels
        // through the shared ensureProcessRow helper (P-24: reuse, don't duplicate). The
        // submitter becomes OWNER below exactly as before (ADR-8 п.3); name falls back to
        // the submission name only if the row somehow predates this approve (same value
        // submit() stored — model name — so no observable change either way).
        ProcessEntity process = processDefinitionService.ensureProcessRow(
            key, submission.getName() != null ? submission.getName() : key);

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
        // WO-REL-39 (F18): CAS transition — the row lock serialises concurrent
        // approve/approve and approve/reject pairs. The loser blocks here until
        // the winner commits, then reads the decided status below and takes the
        // 409 path (SUBMISSION_ALREADY_REVIEWED). All side effects (deploy,
        // registry process, OWNER membership) happen after this lock, in the
        // same transaction — the loser never reaches them.
        ProcessSubmissionEntity submission = submissionRepository.findByIdForUpdate(submissionId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND",
                "Submission not found", Map.of("submissionId", submissionId)));
        if (!ProcessSubmissionStatus.PENDING.name().equals(submission.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBMISSION_ALREADY_REVIEWED",
                "Submission was already reviewed", Map.of("submissionId", submissionId,
                    "status", submission.getStatus()));
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

        // WO-ACL-9: enrich submitter identity (null fields if user was deleted)
        enrichIdentity(dto.getSubmittedBy()).ifPresent(u -> {
            dto.setSubmittedByUsername(u.getUsername());
            dto.setSubmittedByFullName(u.getFullName());
            dto.setSubmittedByEmail(u.getEmail());
        });
        // WO-ACL-9: enrich reviewer identity
        enrichIdentity(entity.getReviewedBy()).ifPresent(u ->
            dto.setReviewedByUsername(u.getUsername())
        );

        return dto;
    }

    /** WO-ACL-9: look up user by ID; returns empty if user was deleted (criteria 4). */
    private Optional<UiUserEntity> enrichIdentity(UUID userId) {
        if (userId == null) return Optional.empty();
        return uiUserRepository.findById(userId);
    }
}