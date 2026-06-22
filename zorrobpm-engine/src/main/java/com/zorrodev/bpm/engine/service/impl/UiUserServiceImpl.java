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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UiUserServiceImpl implements UiUserService {

    private final UiUserRepository repository;
    private final UiUserMapper mapper;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthResponse> login(LoginDTO dto) {
        if (dto.getUsername() == null || dto.getPassword() == null) return Optional.empty();
        UiUserEntity user = repository.findByUsername(dto.getUsername()).orElse(null);
        if (user == null || !user.isActive() || !passwordHasher.matches(dto.getPassword(), user.getPasswordHash())) {
            return Optional.empty();
        }
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
        for (UiUserEntity e : result.getContent()) data.add(mapper.toDTO(e));
        dto.setData(data);
        return dto;
    }

    @Override
    @Transactional
    public UUID create(CreateUiUserDTO dto) {
        if (dto.getUsername() == null || dto.getUsername().isBlank()) throw new EngineException("Username is required");
        if (dto.getPassword() == null || dto.getPassword().isBlank()) throw new EngineException("Password is required");
        if (repository.existsByUsername(dto.getUsername())) throw new EngineException("Username already exists");

        UiUserEntity entity = new UiUserEntity();
        entity.setId(UUID.randomUUID());
        entity.setUsername(dto.getUsername());
        entity.setPasswordHash(passwordHasher.hash(dto.getPassword()));
        entity.setFullName(dto.getFullName());
        entity.setEmail(dto.getEmail());
        entity.setRole(normalizeRole(dto.getRole()));
        entity.setActive(dto.getActive() == null || dto.getActive());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return entity.getId();
    }

    @Override
    @Transactional
    public UUID update(UUID id, UpdateUiUserDTO dto) {
        UiUserEntity entity = repository.findById(id).orElseThrow();
        if (dto.getFullName() != null) entity.setFullName(dto.getFullName());
        if (dto.getEmail() != null) entity.setEmail(dto.getEmail());
        if (dto.getRole() != null) entity.setRole(normalizeRole(dto.getRole()));
        if (dto.getActive() != null) entity.setActive(dto.getActive());
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            entity.setPasswordHash(passwordHasher.hash(dto.getPassword()));
        }
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return entity.getId();
    }

    private static String normalizeRole(String role) {
        return "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "USER";
    }
}
