# user-service + spring + kafka

REST-сервис управления пользователями: CRUD API, PostgreSQL, публикация событий
жизненного цикла пользователя в Apache Kafka.

## Что должно быть запущено

| Программа | Назначение | Порты |
|---|---|---|
| Docker: Kafka | брокер событий (контейнер из `docker-compose.yml`) | 9092 |
| Docker: Mailpit | почтовая заглушка для notification-service | 1025 (SMTP), 8025 (UI) |
| PostgreSQL | база данных `user_service` | 5432 |

Требуемое ПО: JDK 17, Maven, Docker.

### Шаг 1. Инфраструктура Kafka + Mailpit

```bash
docker compose up -d
```

Файл `docker-compose.yml` лежит в корне репозитория.

### Шаг 2. PostgreSQL

Создать базу данных (учётные данные по умолчанию: `postgres` / `postgres`):

```sql
CREATE DATABASE user_service;
```

Либо поднять PostgreSQL контейнером:

```bash
docker run --name user-service-postgres -e POSTGRES_DB=user_service -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres:16
```

Параметры подключения изменяются в `src/main/resources/application.yml`
(`spring.datasource.*`). Таблица `users` создаётся автоматически при старте.

### Шаг 3. Запуск сервиса

```bash
mvn spring-boot:run
```

Сервис доступен на **http://localhost:8080**.

Для полноценной работы системы также запускается соседний микросервис
`notification-service` (см. его README) — он читает события Kafka и шлёт письма.

## API: какие запросы отправлять

Базовый URL: `http://localhost:8080`

| Метод | Путь | Тело запроса | Успех | Описание |
|---|---|---|---|---|
| POST | `/api/v1/users` | JSON пользователя | 201 + `Location` | создать |
| GET | `/api/v1/users/{id}` | — | 200 | получить по id |
| GET | `/api/v1/users` | — | 200 | список всех |
| PUT | `/api/v1/users/{id}` | JSON пользователя | 200 | полное обновление |
| DELETE | `/api/v1/users/{id}` | — | 204 | удалить |

### Создать пользователя

```bash
curl -i -X POST http://localhost:8080/api/v1/users -H "Content-Type: application/json" -d "{\"name\":\"Ivan Petrov\",\"email\":\"Ivan@Example.COM\",\"age\":28}"
```

Ответ `201 Created`, заголовок `Location: http://localhost:8080/api/v1/users/1`:

```json
{"id":1,"name":"Ivan Petrov","email":"ivan@example.com","age":28,"createdAt":"2026-01-10T12:00:00"}
```

### Получить пользователя / список

```bash
curl http://localhost:8080/api/v1/users/1
curl http://localhost:8080/api/v1/users
```

### Обновить пользователя

```bash
curl -i -X PUT http://localhost:8080/api/v1/users/1 -H "Content-Type: application/json" -d "{\"name\":\"Ivan P.\",\"email\":\"ivan.p@example.com\",\"age\":29}"
```

### Удалить пользователя

```bash
curl -i -X DELETE http://localhost:8080/api/v1/users/1
```

Ответ `204 No Content`; в Kafka уходит событие `user-deleted`.

### Ошибки API

| Статус | Причина |
|---|---|
| 400 | невалидные поля, битый JSON, нечисловой id |
| 404 | пользователь не найден |
| 409 | email уже занят |
| 500 | внутренняя ошибка |

Формат тела ошибки:

```json
{
  "timestamp": "2026-01-10T12:05:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Ошибка валидации входных данных",
  "path": "/api/v1/users",
  "errors": { "email": "Некорректный формат email", "age": "Возраст не может быть отрицательным" }
}
```

## События Kafka

При создании и удалении пользователя (после коммита транзакции) публикуется
сообщение с ключом = email:

- топик `user-created`
- топик `user-deleted`

```json
{"userId":1,"email":"ivan@example.com","occurredAt":"2026-01-10T12:00:00Z"}
```

Потребитель — микросервис `notification-service`.

## Тесты

```bash
mvn test
```

Требуется запущенный Docker: интеграционные тесты поднимают PostgreSQL и Kafka
через Testcontainers.
