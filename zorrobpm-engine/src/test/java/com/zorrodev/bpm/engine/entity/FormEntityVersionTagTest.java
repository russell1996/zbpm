package com.zorrodev.bpm.engine.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-C8-31, крит. 1 (helper-уровень): lenient-извлечение top-level
 * {@code versionTag} из `.form` JSON. Проводка в строки доказывается
 * интеграционно ({@code formVersionTagBinding_*} — регистрар/загрузка).
 */
class FormEntityVersionTagTest {

    @Test
    void extractVersionTag_present_returnsTag() {
        assertThat(FormEntity.extractVersionTag(
            "{\"id\": \"f1\", \"type\": \"default\", \"versionTag\": \"v1.0\", \"components\": []}"))
            .isEqualTo("v1.0");
    }

    @Test
    void extractVersionTag_absent_returnsNull() {
        // Норма для большинства форм (WO-C8-27: поле опционально).
        assertThat(FormEntity.extractVersionTag("{\"id\": \"f1\", \"components\": []}")).isNull();
    }

    @Test
    void extractVersionTag_blankOrNonTextual_returnsNull() {
        assertThat(FormEntity.extractVersionTag("{\"versionTag\": \"  \"}")).isNull();
        assertThat(FormEntity.extractVersionTag("{\"versionTag\": 5}")).isNull();
        assertThat(FormEntity.extractVersionTag("{\"versionTag\": null}")).isNull();
    }

    @Test
    void extractVersionTag_brokenOrEmpty_returnsNull() {
        assertThat(FormEntity.extractVersionTag("{not json")).isNull();
        assertThat(FormEntity.extractVersionTag("")).isNull();
        assertThat(FormEntity.extractVersionTag(null)).isNull();
    }
}
