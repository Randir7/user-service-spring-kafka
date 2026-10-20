package com.github.randir7.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO запроса на СОЗДАНИЕ пользователя (POST /api/v1/users).
 *
 * <p><b>Почему record, а не класс?</b>
 * Record (Java 16+) — идеален для DTO:
 * - неизменяемый (immutable) — поля final, сеттеров нет;
 * - компилятор сам генерирует конструктор, геттеры, equals, hashCode, toString;
 * - Jackson умеет десериализовать JSON в record "из коробки":
 *   поле "name" из JSON попадет в компонент name по имени.
 */
public record CreateUserRequest(
        
        // @NotBlank = значение не null И после trim() не пустая строка.
        // Т.е. строка "   " НЕ пройдет проверку (в отличие от @NotEmpty).
        @NotBlank(message = "Имя не должно быть пустым")
        @Size(max = 100, message = "Имя не должно превышать 100 символов")
        String name,
        
        // @Email проверяет базовый формат: наличие @ и точки после нее.
        @NotBlank(message = "Email не должен быть пустым")
        @Email(message = "Некорректный формат email")
        @Size(max = 255, message = "Email не должен превышать 255 символов")
        String email,
        
        // Integer, а не int! Если клиент не пришлет age, Jackson поставит null,
        // и @NotNull сможет это поймать. С примитивом int вместо null
        // автоматически подставился бы 0, и валидация потеряла бы смысл.
        @NotNull(message = "Возраст обязателен для заполнения")
        @Min(value = 0, message = "Возраст не может быть отрицательным")
        @Max(value = 150, message = "Возраст не может превышать 150")
        Integer age
) {
}