package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.TaskFormDTO;
import com.zorrodev.bpm.engine.entity.FormEntity;
import com.zorrodev.bpm.engine.repository.FormRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * WO-FORM-5: Shared form resolution logic (P-14 — used by FormResource + RuntimeResource).
 * Resolves formKey → type/schema/data/url.
 *
 * <p>WO-API-1 (F16): единый резолвер эффективной схемы для рендера И сабмита.
 * Раньше чтение (`TaskFormOperationsImpl`: formId/deployment/versionTag) и
 * валидация сабмита (`FormArtifactService`: formKey+latest) шли двумя разными
 * путями — запиненная v1 валидировалась по latest v2. Теперь обе стороны идут
 * через {@link #resolveEffectiveSchema}: formId-ветка (deployment/versionTag/
 * latest) имеет приоритет над formKey, как в рендере (C8-22). Принимает готовые
 * скаляры (JPA остаётся у вызывающих — P-24, новых зависимостей нет).
 * Null-схема = валидация не применима (та же no-op семантика, что раньше при
 * missing form / external-URL / kind не FORM_JS).
 */
@Component
@RequiredArgsConstructor
public class FormResolver {

    private final FormRepository formRepository;

    public TaskFormDTO resolveTaskForm(String formKey, java.util.Map<String, String> prefillData) {
        if (formKey == null || formKey.isBlank()) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("none");
            return dto;
        }

        if (formKey.startsWith("http://") || formKey.startsWith("https://")) {
            TaskFormDTO dto = new TaskFormDTO();
            dto.setType("external");
            dto.setUrl(formKey);
            return dto;
        }

        FormEntity form = formRepository.findTopByFormKeyOrderByVersionDesc(formKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for key: " + formKey));

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        if (prefillData != null) dto.setData(prefillData);
        return dto;
    }

    /**
     * WO-C8-22: resolves a Modeler-linked form by its {@code formId} — always the latest
     * deployed version carrying this id (binding {@code latest}; {@code deployment} and
     * {@code versionTag} are separate WOs). A missing form behaves exactly like a missing
     * {@code formKey} above (404, same shape) — never a silent {@code type: "none"}.
     */
    public TaskFormDTO resolveTaskFormByFormId(String formId, java.util.Map<String, String> prefillData) {
        FormEntity form = (formId == null || formId.isBlank()) ? null
            : formRepository.findTopByFormIdOrderByVersionDesc(formId).orElse(null);
        if (form == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for id: " + formId);
        }

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        if (prefillData != null) dto.setData(prefillData);
        return dto;
    }

    /**
     * WO-C8-23: resolves a Modeler-linked form pinned to a deployment
     * ({@code bindingType="deployment"} — «the version deployed together with the
     * currently running version of the process in the same deployment»).
     *
     * <p>A missing pair is an explicit 404 naming the id, the deployment and the remedy —
     * never a silent latest fallback (the quiet divergence this WO fixes). A null
     * {@code deploymentId} (process not batch-deployed) 404s before any query: passing it
     * into the derived query would match {@code IS NULL} rows (Spring null semantics) and
     * silently resurrect the singly-deployed form — the exact trap of criterion п.9.
     */
    public TaskFormDTO resolveTaskFormByFormIdAndDeployment(String formId, java.util.UUID deploymentId,
            java.util.Map<String, String> prefillData) {
        FormEntity form = (formId == null || formId.isBlank() || deploymentId == null) ? null
            : formRepository.findFirstByFormIdAndDeploymentIdOrderByVersionDesc(formId, deploymentId).orElse(null);
        if (form == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for id: " + formId + " in deployment: " + deploymentId
                    + " — deploy the form together with the process version (POST /deployments)");
        }

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        if (prefillData != null) dto.setData(prefillData);
        return dto;
    }

    /**
     * WO-C8-31: resolves a Modeler-linked form pinned by version tag
     * ({@code bindingType="versionTag"} — top-level {@code versionTag} of the
     * {@code .form} JSON, WO-C8-27; one tag on several versions pins the latest).
     *
     * <p>A missing pair is an explicit 404 naming both the id and the tag — never a
     * silent latest fallback (the quiet divergence this WO fixes). A null/blank
     * {@code versionTag} 404s before any query: passing it into the derived query
     * would match {@code IS NULL} rows (Spring null semantics) and silently resurrect
     * an untagged form — the exact trap of C8-23 п.9.
     */
    public TaskFormDTO resolveTaskFormByFormIdAndVersionTag(String formId, String versionTag,
            java.util.Map<String, String> prefillData) {
        FormEntity form = (formId == null || formId.isBlank() || versionTag == null || versionTag.isBlank()) ? null
            : formRepository.findFirstByFormIdAndVersionTagOrderByVersionDesc(formId, versionTag).orElse(null);
        if (form == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Form schema not found for id: " + formId + " with version tag: " + versionTag
                    + " — deploy a form version carrying this tag");
        }

        TaskFormDTO dto = new TaskFormDTO();
        dto.setType("embedded");
        dto.setKind(form.getKind() != null ? form.getKind().name() : null);
        dto.setSchema(form.getSchemaJson());
        if (prefillData != null) dto.setData(prefillData);
        return dto;
    }

    public String getSchemaJson(String formKey) {
        if (formKey == null || formKey.isBlank()) return null;
        if (formKey.startsWith("http://") || formKey.startsWith("https://")) return null;
        return formRepository.findTopByFormKeyOrderByVersionDesc(formKey)
            .map(FormEntity::getSchemaJson)
            .orElse(null);
    }

    /** WO-API-1 (F16): эффективная сущность + её происхождение (для диагностики). */
    public record EffectiveSchema(FormEntity form, String origin) {
        public String schemaJson() {
            return form != null ? form.getSchemaJson() : null;
        }
    }

    /**
     * WO-API-1 (F16): единая резолюция эффективной схемы. Порядок веток зеркалит
     * рендер (`TaskFormOperationsImpl.getUserTaskForm`): formId (deployment →
     * versionTag → latest) выигрывает у formKey (C8-22); formKey → latest;
     * external-URL → null (рендер отдаёт type=external без схемы — валидировать
     * нечего). Kind-гейт НЕ здесь, а в `FormArtifactService` (VARIABLE_SCHEMA →
     * no-op, как раньше) — резолвер отдаёт схему, фасад решает применимость.
     *
     * @param formId связанный id формы (nullable — key/external-модели)
     * @param bindingType deployment/versionTag/null
     * @param formKey скалярный ключ (nullable)
     * @param versionTagForElement статический тег элемента (nullable)
     * @param deploymentId деплоймент версии процесса (nullable)
     */
    public EffectiveSchema resolveEffectiveSchema(String formId, String bindingType,
            String formKey, String versionTagForElement, java.util.UUID deploymentId) {
        if (formId != null && !formId.isBlank()) {
            if ("deployment".equals(bindingType)) {
                var form = (deploymentId == null) ? null
                    : formRepository.findFirstByFormIdAndDeploymentIdOrderByVersionDesc(formId, deploymentId)
                        .orElse(null);
                if (form != null) {
                    return new EffectiveSchema(form,
                        "formId=" + formId + " deployment=" + deploymentId);
                }
                // WO-C8-23: пин без пары — явный null (валидация неприменима),
                // не тихий latest (та же fail-closed дисциплина, что 404 рендера).
                return null;
            }
            if ("versionTag".equals(bindingType)) {
                var form = (versionTagForElement == null || versionTagForElement.isBlank()) ? null
                    : formRepository.findFirstByFormIdAndVersionTagOrderByVersionDesc(formId, versionTagForElement)
                        .orElse(null);
                if (form != null) {
                    return new EffectiveSchema(form,
                        "formId=" + formId + " versionTag=" + versionTagForElement);
                }
                return null;
            }
            var latest = formRepository.findTopByFormIdOrderByVersionDesc(formId).orElse(null);
            if (latest != null) {
                return new EffectiveSchema(latest, "formId=" + formId + " latest");
            }
            return null;
        }
        if (formKey == null || formKey.isBlank()) return null;
        if (formKey.startsWith("http://") || formKey.startsWith("https://")) return null;
        var keyed = formRepository.findTopByFormKeyOrderByVersionDesc(formKey).orElse(null);
        if (keyed == null) return null;
        return new EffectiveSchema(keyed, "formKey=" + formKey + " latest");
    }
}
