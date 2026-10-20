package com.github.randir7.userservice.dto;

import java.time.LocalDateTime;

/**
 * DTO ОТВЕТА API: то, что клиент увидит в JSON.
 *
 * <p> Здесь НЕТ аннотаций валидации — они нужны только
 * на ВХОДЕ. На выходе данные отдаются без проверки.
 *
 * <p>Поле createdAt сериализуется Jackson'ом в ISO-8601 строку,
 * например: "2026-01-10T20:59:22.743" — благодаря настройке
 * write-dates-as-timestamps: false в application.yml.
 * Без нее клиент получил бы нечитаемый массив [2026, 1, 10, 20, 59, 22].
 */
public record UserDto(
        Long id,
        String name,
        String email,
        Integer age,
        LocalDateTime createdAt
) {
}