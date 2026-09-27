package com.zorrodev.bpm.contract.dto;

/**
 * WO-INT-9: выдача RabbitMQ-пароля SYSTEM-юзера — показывается один раз,
 * зеркало {@code ApiKeyWithSecretDTO}. Отдельный секрет, не API-ключ:
 * разные blast radius, разные поводы для ротации.
 */
public class RabbitMqPasswordDTO {

    /** Сгенерированный пароль в открытом виде — единственный показ, не хранится. */
    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
