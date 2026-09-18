package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
import com.zorrodev.bpm.contract.exception.ApiException;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.UiUser;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mapper.UiUserMapper;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.repository.RefreshTokenRepository;
import com.zorrodev.bpm.engine.security.AdminPasswordValidator;
import com.zorrodev.bpm.engine.security.PasswordHasher;
import com.zorrodev.bpm.engine.security.TokenService;
import com.zorrodev.bpm.engine.service.UiUserService;
import com.zorrodev.bpm.engine.service.query.QueryPaginationSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UiUserServiceImpl implements UiUserService {

    private final UiUserRepository repository;
    private final UiUserMapper mapper;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final com.zorrodev.bpm.engine.repository.PasswordTokenRepository passwordTokenRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * WO-AUTH-1: вход по username ИЛИ email в поле {@code LoginDTO.username} (поле НЕ
     * переименовано — контракт). Порядок: СНАЧАЛА username, потом email. Если чей-то
     * username буквально совпадает с чужим email — побеждает username-владелец (первым
     * проверяется); оба уникальны, коллизия крайне маловероятна, явное правило лучше
     * «угадывания по наличию @». Email на входе нормализуется той же
     * {@link #normalizeEmail} (stored email всегда lowercase → exact match работает).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<AuthResponse> login(LoginDTO dto) {
        if (dto.getUsername() == null || dto.getPassword() == null) return Optional.empty();
        UiUserEntity user = repository.findByUsername(dto.getUsername()).orElse(null);
        if (user == null) {
            user = repository.findByEmail(normalizeEmail(dto.getUsername())).orElse(null);
        }

        // WO-SEC-17 M7 + WO-SEC-63 (F21): constant-time — always one KDF compare for the full path.
        // For an unknown user we compare against the PRE-COMPUTED dummy hash (same PBKDF2 cost
        // as a real stored hash): exactly ONE matches() in both branches. The old code hashed
        // the submitted password first for unknown users (TWO PBKDF2 runs) — a timing oracle
        // and double CPU burn on unknown logins.
        String hashToCheck = user != null ? user.getPasswordHash() : PasswordHasher.CONSTANT_TIME_DUMMY_HASH;
        boolean passwordMatches = passwordHasher.matches(dto.getPassword(), hashToCheck);
        boolean valid = user != null && user.isActive() && passwordMatches;

        if (!valid) return Optional.empty();

        AuthResponse response = new AuthResponse();
        // WO-SEC-63: access token carries the CURRENT token_version; a later logout/password
        // change bumps it and invalidates this token at the next filter check.
        response.setToken(tokenService.issue(
            user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion()));
        response.setUser(mapper.toDTO(user));
        return Optional.of(response);
    }

    @Override
    @Transactional(readOnly = true)
    public UiUser getById(UUID id) {
        return mapper.toDTO(repository.findById(id).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public UiUser getByUsername(String username) {
        return mapper.toDTO(repository.findByUsername(username).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedDataDTO<UiUser> find(UiUserQuery query) {
        List<Specification<UiUserEntity>> specs = new LinkedList<>();
        if (query.getUsername() != null) specs.add(UiUserRepository.byUsernameContains(query.getUsername()));
        if (query.getActive() != null) specs.add(UiUserRepository.byActive(query.getActive()));
        int clampedPageIndex = query.getPageIndex() != null ? Math.max(0, query.getPageIndex()) : 0;
        int clampedPageSize = com.zorrodev.bpm.engine.service.query.QueryPaginationSupport.MAX_PAGE_SIZE;
        int reqSize = query.getPageSize() != null ? query.getPageSize() : 10;
        clampedPageSize = Math.min(QueryPaginationSupport.MAX_PAGE_SIZE, Math.max(1, reqSize));
        PageRequest page = PageRequest.of(clampedPageIndex, clampedPageSize, Sort.by("username").ascending());
        Page<UiUserEntity> result = repository.findAll(Specification.allOf(specs), page);

        PagedDataDTO<UiUser> dto = new PagedDataDTO<>();
        dto.setTotalElements(result.getTotalElements());
        dto.setPageIndex(result.getNumber());
        dto.setPageSize(result.getSize());
        List<UiUser> data = new ArrayList<>();
        java.util.Set<UUID> pendingIds = java.util.Set.of();
        List<UiUserEntity> content = result.getContent();
        if (!content.isEmpty()) {
            List<UUID> ids = content.stream().map(UiUserEntity::getId).toList();
            pendingIds = new java.util.HashSet<>(passwordTokenRepository
                .findUserIdsWithPendingInvite(ids, "INVITE", Instant.now()));
        }
        for (UiUserEntity e : content) {
            UiUser u = mapper.toDTO(e);
            u.setPendingInvitation(pendingIds.contains(e.getId()));
            data.add(u);
        }
        dto.setData(data);
        return dto;
    }

    @Override
    @Transactional
    public UUID create(CreateUiUserDTO dto) {
        if (dto.getUsername() == null || dto.getUsername().isBlank()) throw new EngineException("Username is required");
        if (repository.existsByUsername(dto.getUsername())) throw new EngineException("Username already exists");

        boolean system = "SYSTEM".equalsIgnoreCase(dto.getUserType());
        // WO-REG-1: canonical form BEFORE validation/storage (same transform the
        // readers apply — UserInvitationService.requestReset looks up lowercased).
        String normalizedEmail = normalizeEmail(dto.getEmail());
        // WO-ACL-19 (P2): a HUMAN account MUST have a valid email — every password-reset path
        // (public forgot-password, admin reset, invitation) is email-driven and silently no-ops
        // when email is missing, which is worse than a clear 400 at creation time.
        if (!system) {
            if (normalizedEmail == null || normalizedEmail.isBlank()) {
                throw new EngineException("Email is required for HUMAN users");
            }
            if (!isValidEmail(normalizedEmail)) {
                throw new EngineException("Email format is invalid");
            }
            if (repository.existsByEmail(normalizedEmail)) throw new EngineException("Email already exists");
        } else if (normalizedEmail != null && !normalizedEmail.isBlank()
            && repository.existsByEmail(normalizedEmail)) {
            throw new EngineException("Email already exists");
        }
        // WO-ACL-18: INVITE mode creates the account WITHOUT a usable password — the user
        // receives a one-time link to set it. SYSTEM accounts are never invited.
        boolean invite = "INVITE".equalsIgnoreCase(dto.getCreationMode());
        // WO-INT-4: a SYSTEM account has NO password — login is impossible. The password
        // supplied in the DTO (if any) is deliberately ignored: storing it would create a
        // second way in (one day forcePasswordChange would lock the integration, and someone
        // would "fix" it by using the password). We still store a hash — of an unknowable
        // random secret — so the constant-time login path (WO-SEC-17 M7) compares against
        // a real hash instead of short-circuiting on null.
        if (invite) {
            // email presence/format already enforced above for HUMAN users
        } else if (!system) {
            if (dto.getPassword() == null || dto.getPassword().isBlank()) throw new EngineException("Password is required");
            // WO-SEC-46: enforce password complexity on create
            if (AdminPasswordValidator.isWeak(dto.getPassword())) throw new EngineException("Password does not meet complexity requirements");
        }

        UiUserEntity entity = new UiUserEntity();
        entity.setId(UUID.randomUUID());
        entity.setUsername(dto.getUsername());
        entity.setPasswordHash(invite || system
            ? passwordHasher.hash(UUID.randomUUID().toString())
            : passwordHasher.hash(dto.getPassword()));
        entity.setFullName(dto.getFullName());
        entity.setEmail(normalizedEmail);
        entity.setRole(normalizeRole(dto.getRole()));
        entity.setActive(dto.getActive() == null || dto.getActive());
        entity.setUserType(system ? "SYSTEM" : "HUMAN");
        entity.setForcePasswordChange(false);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        try {
            repository.save(entity);
            // flush (not deferred to commit): the race window between the existsByEmail
            // check above and the INSERT must surface INSIDE this method to be
            // translated. save() stays (existing tests verify it), flush forces timing.
            repository.flush();
        } catch (DataAccessException e) {
            // WO-REG-1: PG+Hibernate surfaces this race NOT as DataIntegrityViolation
            // but as JpaSystemException(25P02) (proven live: two threads racing one
            // address) — so catch the parent. Precision kept by the re-check below.
            // WO-AUDIT-5: email first (existing behavior), then username — a username
            // race rethrows the raw cause from the email translator, translate it here.
            try {
                throw translateEmailConflict(normalizedEmail, e);
            } catch (DataAccessException stillRaw) {
                throw translateUsernameConflict(dto.getUsername(), stillRaw);
            }
        }
        return entity.getId();
    }

    @Override
    @Transactional
    public UUID changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        // WO-SEC-58: self-service change — only the CALLER's own row is touched,
        // and only the password (no role/active/fullName surface here).
        UiUserEntity entity = repository.findById(userId)
            .orElseThrow(() -> new NoSuchElementException("User not found"));

        boolean system = "SYSTEM".equals(entity.getUserType());
        if (system) {
            // WO-INT-4: a SYSTEM account has no usable password by design
            throw new EngineException("System accounts cannot change a password");
        }
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new EngineException("Current password is required");
        }
        // Constant-time compare against the stored hash; wrong current password → refuse.
        if (!passwordHasher.matches(currentPassword, entity.getPasswordHash())) {
            throw new EngineException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.isBlank()) throw new EngineException("Password is required");
        // Same complexity rules as admin-set passwords — no second rule set (WO-SEC-46).
        if (AdminPasswordValidator.isWeak(newPassword)) throw new EngineException("Password does not meet complexity requirements");

        entity.setPasswordHash(passwordHasher.hash(newPassword));
        entity.setForcePasswordChange(false);

        // WO-SEC-63: changing the password must invalidate ALL outstanding access tokens for
        // this user (same guarantee as logout — copied tokens stop working immediately).
        entity.setTokenVersion(entity.getTokenVersion() + 1);
        // ...and their refresh tokens: a stolen refresh cookie would otherwise keep minting
        // fresh access tokens past the password change (mirror of WO-SEC-59 #6 admin reset).
        refreshTokenRepository.revokeAllByUserId(userId);

        entity.setUpdatedAt(java.time.Instant.now());
        repository.save(entity);
        return entity.getId();
    }
    @Override
    @Transactional
    public UUID update(UUID id, UpdateUiUserDTO dto) {
        UiUserEntity entity = repository.findById(id).orElseThrow();
        boolean system = "SYSTEM".equals(entity.getUserType());
        String previousRole = entity.getRole();
        boolean previousActive = entity.isActive();
        // WO-SEC-60: protect last active SUPER_ADMIN — check BEFORE mutating the entity,
        // otherwise the persistence context flush would make countByRoleAndActive see the
        // already-demoted state and the count would be off by one.
        String newRole = dto.getRole() != null ? normalizeRole(dto.getRole()) : previousRole;
        boolean newActive = dto.getActive() != null ? dto.getActive() : previousActive;
        boolean wasActiveSuperAdmin = "SUPER_ADMIN".equals(previousRole) && previousActive;
        boolean willBeActiveSuperAdmin = "SUPER_ADMIN".equals(newRole) && newActive;
        if (wasActiveSuperAdmin && !willBeActiveSuperAdmin) {
            // Use PESSIMISTIC_WRITE to prevent race where two concurrent demotions both see count=2
            long activeSuperAdminCount = repository.findByRoleAndActiveAndUserTypeForUpdate("SUPER_ADMIN", true, "HUMAN").size();
            if (activeSuperAdminCount <= 1) {
                throw new EngineException("Cannot demote or deactivate the last active SUPER_ADMIN");
            }
        }
        if (dto.getFullName() != null) entity.setFullName(dto.getFullName());
        if (dto.getEmail() != null) {
            // WO-REG-1: normalize everywhere an address enters from input (both paths).
            String normalizedEmail = normalizeEmail(dto.getEmail());
            // WO-ACL-19 (P2): a HUMAN account cannot be left without a valid email.
            if (system) {
                entity.setEmail(normalizedEmail);
            } else {
                if (normalizedEmail == null || normalizedEmail.isBlank()) {
                    throw new EngineException("Email is required for HUMAN users");
                }
                if (!isValidEmail(normalizedEmail)) {
                    throw new EngineException("Email format is invalid");
                }
                // WO-REG-1: skip the check when the address does not really change;
                // compare by id (not text) so a legacy mixed-case row never conflicts
                // with its own normalized form.
                if (!normalizedEmail.equals(entity.getEmail())) {
                    repository.findByEmail(normalizedEmail)
                        .filter(u -> !u.getId().equals(entity.getId()))
                        .ifPresent(u -> {
                            throw new EngineException("Email already exists");
                        });
                    entity.setEmail(normalizedEmail);
                }
            }
        }
        if (dto.getRole() != null) entity.setRole(newRole);
        if (dto.getActive() != null) entity.setActive(newActive);
        // WO-SEC-63 (F01): identity attributes (role / active) are part of the access-token
        // contract — any change must invalidate outstanding tokens, otherwise a demoted/deactivated
        // user keeps their old role until the 30-minute access TTL expires. The filter also checks
        // active + role per request (defense-in-depth), but bumping the version here forces a
        // refresh/re-login cycle immediately and keeps the persisted state consistent.
        boolean identityChanged = (dto.getRole() != null && !newRole.equals(previousRole))
            || (dto.getActive() != null && newActive != previousActive);
        // WO-INT-4: a system account never gets a password and forcePasswordChange is
        // not applicable to it — both are ignored so the integration cannot be locked
        // by a password-flow decision.
        if (!system && dto.getPassword() != null && !dto.getPassword().isBlank()) {
            // WO-SEC-46: enforce password complexity on update
            if (AdminPasswordValidator.isWeak(dto.getPassword())) throw new EngineException("Password does not meet complexity requirements");
            entity.setPasswordHash(passwordHasher.hash(dto.getPassword()));
            entity.setForcePasswordChange(false);
            // WO-SEC-59 #6: admin password reset must revoke all of the user's refresh tokens,
            // otherwise a stolen/held token keeps refreshing access after the reset.
            refreshTokenRepository.revokeAllByUserId(id);
            // WO-SEC-63: and invalidate all outstanding access tokens for the same reason.
            identityChanged = true;
        }
        if (identityChanged) {
            entity.setTokenVersion(entity.getTokenVersion() + 1);
            // WO-SEC-63 (F01): deactivation (without password change) must ALSO revoke the
            // refresh tokens — an active check alone leaves the stolen refresh cookie reusable
            // if the user is later re-activated.
            if (dto.getActive() != null && !newActive && previousActive) {
                refreshTokenRepository.revokeAllByUserId(id);
            }
        }
        entity.setUpdatedAt(Instant.now());
        try {
            repository.save(entity);
            // flush: same race reasoning as create() — surface here, translate below.
            repository.flush();
        } catch (DataAccessException e) {
            throw translateEmailConflict(entity.getEmail(), e);
        }
        return entity.getId();
    }

    /**
     * WO-REG-1: canonical email form — trimmed + lowercased.
     *
     * WO-QW-1 S-11: {@code Locale.ROOT} explicitly. Plain {@code toLowerCase()}
     * is locale-sensitive (tr_TR turns "MIKE" into "mıke", caught live by
     * UiUserServiceImplLocaleTest) while PostgreSQL {@code lower(email)} in the
     * uniqueness index (changeset 101) folds per DB collation — a tr-locale JVM
     * and the DB would canonicalize the same address differently, breaking the
     * write/read contract the old comment claimed. All Java-side lowercasings
     * on this path use ROOT, so write and read agree byte-for-byte everywhere.
     */
    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * WO-REG-1: translates a unique-violation into the domain conflict. The catching
     * transaction is already aborted (PG aborts on the first error), so the re-check
     * MUST run outside it — a SELECT here would fail the same way. Fresh read-only
     * REQUIRES_NEW template per call (a shared bean's propagation must never be
     * mutated — not thread-safe); the violation path is rare, allocation is free.
     * Under READ_COMMITTED a concurrent uncommitted rival is invisible, so a taken
     * address here means OUR constraint fired — anything else rethrows as-is.
     */
    private EngineException translateEmailConflict(String normalizedEmail,
            DataAccessException cause) {
        TransactionTemplate tpl = new TransactionTemplate(transactionManager);
        tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tpl.setReadOnly(true);
        Boolean taken = tpl.execute(status ->
            normalizedEmail != null && !normalizedEmail.isBlank() && repository.existsByEmail(normalizedEmail));
        if (Boolean.TRUE.equals(taken)) {
            return new EngineException("Email already exists");
        }
        throw cause;
    }

    /**
     * WO-AUDIT-5 (C5): username counterpart of {@link #translateEmailConflict}.
     * A parallel registration of the same username loses check-then-act and hits
     * {@code uk_ui_users__username}; without translation the loser gets a raw 500.
     * Same mechanics: the catching transaction is already aborted, so the re-check
     * runs in a fresh read-only REQUIRES_NEW transaction. A taken username becomes
     * a 409 {@code USERNAME_ALREADY_EXISTS} (ApiException pattern per
     * ProcessSubmissionServiceImpl WO-ACL-12); anything else rethrows as-is.
     */
    private ApiException translateUsernameConflict(String username,
            DataAccessException cause) {
        TransactionTemplate tpl = new TransactionTemplate(transactionManager);
        tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tpl.setReadOnly(true);
        Boolean taken = tpl.execute(status ->
            username != null && !username.isBlank() && repository.existsByUsername(username));
        if (Boolean.TRUE.equals(taken)) {
            return new ApiException(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS",
                "Username '" + username + "' is already taken",
                Map.of("username", username));
        }
        throw cause;
    }

    private static String normalizeRole(String role) {
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) return "SUPER_ADMIN";
        if ("ADMIN".equalsIgnoreCase(role)) return "ADMIN";
        return "USER";
    }

    private static boolean isValidEmail(String email) {
        if (email == null) return false;
        // Minimal but sufficient: one @, no spaces, a dot in the domain part.
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return false;
        String domain = email.substring(at + 1);
        return domain.contains(".") && !email.contains(" ") && !domain.contains(" ");
    }
}
