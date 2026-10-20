package com.github.randir7.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.randir7.userservice.dto.CreateUserRequest;
import com.github.randir7.userservice.dto.UpdateUserRequest;
import com.github.randir7.userservice.dto.UserDto;
import com.github.randir7.userservice.exception.UserNotFoundException;
import com.github.randir7.userservice.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-тест веб-слоя.
 *
 * <p><b>@WebMvcTest(UserController.class)</b> поднимает УРЕЗАННЫЙ контекст
 * Spring: только то, что нужно для MVC:
 *   ✅ UserController
 *   ✅ GlobalExceptionHandler (@RestControllerAdvice тоже попадает в slice!)
 *   ✅ Jackson, конвертеры, валидаторы, MockMvc
 *   ❌ НЕ поднимает: UserRepository, UserService, подключение к БД, Tomcat
 *
 * Результат: тест стартует за ~1 секунду вместо ~10.
 *
 * <p>Поскольку настоящего UserService в контексте НЕТ, а контроллеру он
 * нужен — передается мок через @MockitoBean.
 *
 * <p>Имена методов декларативны: [метод][WithУсловие]Should[Ожидание],
 * описание сценария — в @DisplayName.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {
    
    @Autowired
    private MockMvc mockMvc;           // «виртуальный» HTTP-клиент для тестов
    
    @Autowired
    private ObjectMapper objectMapper; // настоящий Jackson из контекста slice
    
    @MockitoBean
    private UserService userService;   // мок сервиса: БД не участвует
    
    // Вспомогательные фабрики тестовых данных — убирают дублирование в тестах
    private CreateUserRequest validRequest() {
        return new CreateUserRequest("Ivan", "ivan@example.com", 28);
    }
    
    private UserDto sampleDto() {
        return new UserDto(1L, "Ivan", "ivan@example.com", 28,
                LocalDateTime.of(2026, 1, 10, 12, 0));
    }
    
    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }
    
    // ==================== POST ====================
    
    @Test
    @DisplayName("POST /users: валидный запрос → 201 + Location + тело")
    void createShouldReturn201WithLocationHeaderAndBody() throws Exception {
        // given
        when(userService.create(any(CreateUserRequest.class))).thenReturn(sampleDto());
        
        // when / then
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isCreated())
                // Location собирается из ТЕКУЩЕГО запроса; в MockMvc хост — localhost
                .andExpect(header().string("Location", "http://localhost/api/v1/users/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ivan"))
                .andExpect(jsonPath("$.email").value("ivan@example.com"));
        
        verify(userService).create(any(CreateUserRequest.class));
    }
    
    @Test
    @DisplayName("POST /users: невалидные поля → 400 с разбивкой ошибок по полям")
    void createWithInvalidFieldsShouldReturn400WithErrorsByFields() throws Exception {
        // given
        CreateUserRequest bad = new CreateUserRequest("", "not-an-email", -5);
        
        // when / then
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                // $.errors.<поле> — путь к детали ошибки в ErrorResponse
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.age").exists());
        
        // Сервис не должен даже вызываться: валидация отсекла запрос раньше
        verifyNoInteractions(userService);
    }
    
    @Test
    @DisplayName("POST /users: тело не может быть разобрано в CreateUserRequest → 400")
    void createWithUnparseableBodyShouldReturn400WithReadableMessage() throws Exception {
        // JSON синтаксически ВАЛИДЕН (инспектор IDE молчит), но тип поля age —
        // строка вместо числа. Jackson бросит InvalidFormatException,
        // Spring обернет его в HttpMessageNotReadableException,
        // и наш GlobalExceptionHandler вернет 400 с понятным сообщением.
        // Текст-блок (Java 15+) избавляет от экранирования кавычек.
        String wrongTypeJson = """
            {"name": "Ivan", "email": "ivan@example.com", "age": "twenty-eight"}
            """;
        
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongTypeJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Тело запроса содержит некорректный JSON"));
    }
    
    // ==================== GET ====================
    
    @Test
    @DisplayName("GET /users/{id}: найден → 200 + DTO")
    void getByIdShouldReturn200WithUserDto() throws Exception {
       
        when(userService.getById(1L)).thenReturn(sampleDto());
        
        
        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                // LocalDateTime сериализуется в строку
                .andExpect(jsonPath("$.createdAt").value("2026-01-10T12:00:00"));
    }
    
    @Test
    @DisplayName("GET /users/{id}: не найден → 404 в формате ErrorResponse")
    void getByIdWithNonExistentIdShouldReturn404InErrorResponseFormat() throws Exception {
        // сервис бросает доменное исключение
        when(userService.getById(99L)).thenThrow(new UserNotFoundException(99L));
        
        // клиент должен увидеть 404 от GlobalExceptionHandler
        mockMvc.perform(get("/api/v1/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/users/99"));
    }
    
    @Test
    @DisplayName("GET /users/{id}: буквы вместо числа → 400")
    void getByIdWithNonNumericIdShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/users/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
    
    @Test
    @DisplayName("GET /users: список → 200 + JSON-массив")
    void findAllShouldReturn200WithUserDtoArray() throws Exception {
        when(userService.findAll()).thenReturn(List.of(
                sampleDto(),
                new UserDto(2L, "Petr", "petr@example.com", 30,
                        LocalDateTime.of(2026, 1, 11, 12, 0))
        ));
        
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))     // $ — корень JSON: массив
                .andExpect(jsonPath("$[0].id").value(1))  // [0] — первый элемент
                .andExpect(jsonPath("$[1].name").value("Petr"));
    }
    
    // ==================== PUT ====================
    
    @Test
    @DisplayName("PUT /users/{id}: успешное обновление → 200 + обновленный DTO")
    void updateShouldReturn200WithUpdatedDto() throws Exception {
        UserDto updated = new UserDto(1L, "Ivan P.", "ivan.p@example.com", 29,
                LocalDateTime.of(2026, 1, 10, 12, 0));
        when(userService.update(anyLong(), any(UpdateUserRequest.class))).thenReturn(updated);
        
        mockMvc.perform(put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateUserRequest("Ivan P.", "ivan.p@example.com", 29))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ivan P."))
                .andExpect(jsonPath("$.age").value(29));
    }
    
    // ==================== DELETE ====================
    
    @Test
    @DisplayName("DELETE /users/{id}: успех → 204 без тела")
    void deleteShouldReturn204AndCallServiceDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/users/1"))
                .andExpect(status().isNoContent());
        
        verify(userService).delete(1L);
    }
    
    @Test
    @DisplayName("DELETE /users/{id}: не найден → 404")
    void deleteWithNonExistentIdShouldReturn404() throws Exception {
        // doThrow — вариант stubbing для void-методов
        // (when(...).thenThrow(...) для void не подходит: нечего оборачивать)
        doThrow(new UserNotFoundException(99L)).when(userService).delete(99L);
        
        mockMvc.perform(delete("/api/v1/users/99"))
                .andExpect(status().isNotFound());
    }
}