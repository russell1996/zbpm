package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.AuthResponse;
import com.zorrodev.bpm.contract.dto.CreateUiUserDTO;
import com.zorrodev.bpm.contract.dto.LoginDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.dto.query.UiUserQuery;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthResponse> login(LoginDTO dto) {
        if (dto.getUsername() == null || dto.getPassword() == null) return Optional.empty();
        UiUserEntity user = repository.findByUsername(dto.getUsername()).orElse(null);

        // WO-SEC-17 M7: constant-time — always compare hash, even for unknown/inactive users
        String dummyHash = user != null ? user.getPasswordHash() : passwordHasher.hash(dto.getPassword());
        boolean passwordMatches = passwordHasher.matches(dto.getPassword(), dummyHash);
        boolean valid = user != null && user.isActive() && passwordMatches;

        if (!valid) return Optional.empty();

        AuthResponse response = new AuthResponse();
        response.setToken(tokenService.issue(user.getId(), user.getUsername(), user.getRole()));
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
        PageRequest page = PageRequest.of(query.getPageIndex(), query.getPageSize(), Sort.by("username").ascending());
        Page<UiUserEntity> result = repository.findAll(Specification.allOf(specs), page);

        PagedDataDTO<UiUser> dto = new PagedDataDTO<>();
        dto.setTotalElements(result.getTotalElements());
        dto.setPageIndex(result.getNumber());
        dto.setPageSize(result.getSize());
        List<UiUser> data = new ArrayList<>();
        for (UiUserEntity e : result.getContent()) {
            UiUser u = mapper.toDTO(e);
            // WO-ACL-18 criterion 5: surface an outstanding invitation to the admin list.
            u.setPendingInvitation(passwordTokenRepository
                .existsByUserIdAndTypeAndUsedFalseAndExpiresAtAfter(e.getId(), "INVITE", Instant.now()));
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
        // WO-ACL-19 (P2): a HUMAN account MUST have a valid email — every password-reset path
        // (public forgot-password, admin reset, invitation) is email-driven and silently no-ops
        // when email is missing, which is worse than a clear 400 at creation time.
        if (!system) {
            if (dto.getEmail() == null || dto.getEmail().isBlank()) {
                throw new EngineException("Email is required for HUMAN users");
            }
            if (!isValidEmail(dto.getEmail())) {
                throw new EngineException("Email format is invalid");
            }
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
        entity.setEmail(dto.getEmail());
        entity.setRole(normalizeRole(dto.getRole()));
        entity.setActive(dto.getActive() == null || dto.getActive());
        entity.setUserType(system ? "SYSTEM" : "HUMAN");
        entity.setForcePasswordChange(false);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
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
            // WO-ACL-19 (P2): a HUMAN account cannot be left without a valid email.
            if (system) {
                entity.setEmail(dto.getEmail());
            } else {
                if (dto.getEmail().isBlank()) {
                    throw new EngineException("Email is required for HUMAN users");
                }
                if (!isValidEmail(dto.getEmail())) {
                    throw new EngineException("Email format is invalid");
                }
                entity.setEmail(dto.getEmail());
            }
        }
        if (dto.getRole() != null) entity.setRole(newRole);
        if (dto.getActive() != null) entity.setActive(newActive);
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
        }
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return entity.getId();
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
