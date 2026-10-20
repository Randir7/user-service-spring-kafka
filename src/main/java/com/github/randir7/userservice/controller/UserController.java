package com.github.randir7.userservice.controller;

import com.github.randir7.userservice.dto.CreateUserRequest;
import com.github.randir7.userservice.dto.UpdateUserRequest;
import com.github.randir7.userservice.dto.UserDto;
import com.github.randir7.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST-контроллер: HTTP-адаптер приложения.
 *
 * <p><b>@RestController = @Controller + @ResponseBody.</b>
 * @ResponseBody говорит Spring: результат каждого метода НЕ нужно
 * искать как view/шаблон — его надо СЕРИАЛИЗОВАТЬ в тело ответа.
 * Сериализацией занимается Jackson (уже внутри spring-boot-starter-web):
 * record UserDto автоматически превратится в JSON.
 *
 * <p><b>@RequestMapping("/api/v1/users")</b> — базовый путь для всех
 * методов класса.
 *
 * <p><b>ПРАВИЛО ТОНКОГО КОНТРОЛЛЕРА:</b> здесь НЕТ бизнес-логики.
 * Контроллер только: (1) распаковывает HTTP-запрос в объекты,
 * (2) вызывает сервис, (3) упаковывает результат в HTTP-ответ.
 * Вся логика — в UserService. Это делает сервис тестируемым без HTTP.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    
    private final UserService userService;
    
    // Инъекция через конструктор
    public UserController(UserService userService) {
        this.userService = userService;
    }
    
    /**
     * POST /api/v1/users — создание пользователя.
     *
     * <p><b>@RequestBody</b>: Jackson читает тело запроса и строит
     * CreateUserRequest (поля JSON → компоненты record по именам).
     *
     * <p><b>@Valid</b>: включает Bean Validation ПЕРЕД входом в метод.
     * Если @NotBlank/@Email/@Min из DTO нарушены, метод вообще не вызовется —
     * Spring бросит MethodArgumentNotValidException и вернет 400 Bad Request.
     *
     * <p><b>ResponseEntity<UserDto></b> — обертка, позволяющая управлять
     * статусом, заголовками и телом ответа одновременно.
     */
    @PostMapping
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest request) {
        UserDto created = userService.create(request);
        
        // Собираем Location: http://localhost:8080/api/v1/users/{id}
        // ServletUriComponentsBuilder берет схему/хост/порт ИЗ ТЕКУЩЕГО запроса,
        // поэтому ничего не хардкодится: сменится порт — URL останется верным.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")                       // добавляет /{id} к текущему пути
                .buildAndExpand(created.id())        // подставляет значение id
                .toUri();
        
        // 201 Created + заголовок Location + тело с созданным ресурсом
        return ResponseEntity.created(location).body(created);
    }
    
    /**
     * GET /api/v1/users/{id} — получить одного пользователя.
     *
     * <p><b>@PathVariable</b>: значение из фигурных скобок пути ("{id}")
     * привязывается к параметру метода. Spring сам сконвертирует строку
     * "1" в Long. Если придут буквы ("abc") — будет 400 Bad Request
     * (MethodArgumentTypeMismatchException), метод не вызовется.
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getById(id));
    }
    
    /**
     * GET /api/v1/users — список всех пользователей.
     */
    @GetMapping
    public ResponseEntity<List<UserDto>> findAll() {
        return ResponseEntity.ok(userService.findAll());
    }
    
    /**
     * PUT /api/v1/users/{id} — полное обновление.
     *
     * <p>Возвращается код 200 + обновленное тело: клиенту удобно сразу увидеть
     * итоговое состояние
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> update(@PathVariable Long id,
                                          @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }
    
    /**
     * DELETE /api/v1/users/{id} — удаление.
     *
     * <p>204 No Content: операция успешна, но возвращать нечего.
     * ResponseEntity<Void> + .noContent() выражают это на уровне типов.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}