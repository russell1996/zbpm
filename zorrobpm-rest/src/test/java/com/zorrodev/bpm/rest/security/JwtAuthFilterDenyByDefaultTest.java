package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-26: Reflective test — every contract endpoint is either in the
 * public allowlist (login/refresh) or protected by JwtAuthFilter.
 * Catches any future endpoint that's accidentally anonymous.
 */
class JwtAuthFilterDenyByDefaultTest {

    private static final Class<?>[] CONTRACT_CLASSES = {
        com.zorrodev.bpm.contract.AuthContract.class,
        com.zorrodev.bpm.contract.RuntimeContract.class,
        com.zorrodev.bpm.contract.UserContract.class,
        com.zorrodev.bpm.contract.QueryContract.class,
        com.zorrodev.bpm.contract.ProcessDefinitionContract.class,
        com.zorrodev.bpm.contract.MemberContract.class,
        com.zorrodev.bpm.contract.AuditLogContract.class,
        com.zorrodev.bpm.contract.ApiKeyManagementContract.class,
        com.zorrodev.bpm.contract.DmnContract.class,
        com.zorrodev.bpm.contract.DeploymentContract.class,
        com.zorrodev.bpm.contract.FormContract.class,
        com.zorrodev.bpm.contract.VariableSchemaContract.class,
        com.zorrodev.bpm.contract.OutboxAdminContract.class,
    };

    @Test
    void allContractEndpoints_mustRequireAuth() {
        for (Class<?> contract : CONTRACT_CLASSES) {
            for (Method method : contract.getDeclaredMethods()) {
                String methodPath = extractHttpPath(method);
                if (methodPath == null) continue;

                assertThat(JwtAuthFilter.isPublicPath(methodPath))
                    .as("Endpoint %s (%s.%s) must NOT be in public allowlist",
                        methodPath, contract.getSimpleName(), method.getName())
                    .isFalse();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractHttpPath(Method method) {
        // @*Mapping (Spring MVC)
        Optional<String> path = extractValue(method, PostMapping.class)
            .or(() -> extractValue(method, GetMapping.class))
            .or(() -> extractValue(method, PutMapping.class))
            .or(() -> extractValue(method, DeleteMapping.class))
            .or(() -> extractValue(method, PatchMapping.class));

        if (path.isPresent()) return path.get();

        // @*Exchange (Spring HTTP interfaces)
        path = extractValue(method, PostExchange.class)
            .or(() -> extractValue(method, GetExchange.class))
            .or(() -> extractValue(method, PutExchange.class))
            .or(() -> extractValue(method, DeleteExchange.class))
            .or(() -> extractValue(method, PatchExchange.class));

        return path.orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> extractValue(Method method, Class<? extends Annotation> annotationType) {
        Annotation ann = method.getAnnotation(annotationType);
        if (ann == null) return Optional.empty();
        try {
            Method valueMethod = annotationType.getMethod("value");
            Object val = valueMethod.invoke(ann);
            if (val instanceof String[] arr && arr.length > 0) return Optional.of(arr[0]);
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}
