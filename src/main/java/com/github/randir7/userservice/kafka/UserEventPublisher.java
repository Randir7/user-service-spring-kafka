package com.github.randir7.userservice.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.randir7.userservice.event.UserCreatedEvent;
import com.github.randir7.userservice.event.UserDeletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Мост между доменными событиями приложения и Kafka.
 *
 * <p><b>Роль в архитектуре:</b> сервисный слой не знает о существовании Kafka.
 * Он публикует доменные события (UserCreatedEvent, UserDeletedEvent) через
 * стандартный механизм Spring — ApplicationEventPublisher. Данный компонент
 * подписан на эти события и преобразует их в JSON-сообщения Kafka.
 * Такое разделение оставляет бизнес-код независимым от транспорта.
 *
 * <p><b>Фаза AFTER_COMMIT:</b> методы-слушатели вызываются ТОЛЬКО после
 * успешного коммита транзакции, в которой опубликовано событие.
 * Если транзакция откатилась (например, нарушение уникальности email
 * на уровне БД), слушатель не вызывается вовсе — в Kafka не попадает
 * событие о данных, которых не существует. Это защита от ложных уведомлений.
 *
 * <p><b>Ограничение фазы:</b> при публикации события ВНЕ транзакции слушатель
 * с AFTER_COMMIT не срабатывает (fallbackExecution по умолчанию выключен).
 * Все публикующие методы UserService помечены @Transactional,
 * поэтому условие выполнено.
 *
 * <p><b>Ключ сообщения:</b> в качестве ключа Kafka передаётся email.
 * Брокер хеширует ключ для выбора партиции, поэтому все события одного
 * пользователя попадают в одну партицию. Внутри партиции сообщения
 * упорядочены по времени записи — порядок «создан → удалён» не перепутается
 * даже при нескольких партициях топика.
 */
@Slf4j
@Component
public class UserEventPublisher {
    
    /**
     * Продюсер Kafka, созданный автоконфигурацией spring-kafka
     * на основе свойств spring.kafka.* из application.yml
     * (bootstrap-servers и сериализаторы ключа/значения).
     * Дженерики <String, String>: ключ — email, значение — готовый JSON.
     */
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    /**
     * ObjectMapper, созданный автоконфигурацией Spring Boot.
     * Он учитывает свойства spring.jackson.*: в частности,
     * write-dates-as-timestamps: false, поэтому Instant сериализуется
     * в читаемую ISO-8601 строку, а не в массив чисел.
     */
    private final ObjectMapper objectMapper;
    
    /** Имена топиков берутся из конфигурации, а не из констант кода. */
    private final String userCreatedTopic;
    private final String userDeletedTopic;
    
    public UserEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Value("${app.kafka.topic-user-created}") String userCreatedTopic,
                              @Value("${app.kafka.topic-user-deleted}") String userDeletedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.userCreatedTopic = userCreatedTopic;
        this.userDeletedTopic = userDeletedTopic;
    }
    
    /**
     * Реакция на создание пользователя: отправка события в топик user-created.
     * Вызывается инфраструктурой Spring после коммита транзакции создания.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreatedEvent event) {
        send(userCreatedTopic, event.userId(), event.email());
    }
    
    /**
     * Реакция на удаление пользователя: отправка события в топик user-deleted.
     * Вызывается инфраструктурой Spring после коммита транзакции удаления.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        send(userDeletedTopic, event.userId(), event.email());
    }
    
    /**
     * Сериализация контракта в JSON и отправка сообщения в указанный топик.
     *
     * <p><b>Асинхронность:</b> send() не блокирует поток вызова: сообщение
     * ставится в буфер продюсера, а результат доставки приходит в callback
     * whenComplete на фоновом потоке продюсера. Успех и ошибка логируются.
     *
     * <p><b>Политика ошибок:</b> ошибка доставки НЕ пробрасывается наружу
     * намеренно. Пользователь уже сохранён в БД (событие обрабатывается
     * после коммита), и падение бизнес-операции из-за недоступности Kafka
     * было бы худшим исходом, чем пропущенное уведомление.
     *
     * <p><b>Направление развития:</b> гарантированную доставку в production
     * обеспечивает паттерн Transactional Outbox: событие сначала пишется
     * в таблицу БД в той же транзакции, затем отдельный процесс выгружает
     * его в Kafka. Для учебной задачи достаточно логирования ошибок.
     *
     * @param topic  имя топика назначения (user-created или user-deleted)
     * @param userId идентификатор пользователя из доменного события
     * @param email  email пользователя; используется и как ключ партиционирования,
     *               и как поле контракта для получателя уведомления
     */
    private void send(String topic, Long userId, String email) {
        UserEventMessage message = new UserEventMessage(userId, email, Instant.now());
        try {
            String json = objectMapper.writeValueAsString(message);
            
            // Третий аргумент send — значение сообщения (тело),
            // второй — ключ партиционирования (email).
            kafkaTemplate.send(topic, email, json)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Не удалось доставить событие в топик {}: {}",
                                    topic, error.getMessage());
                        } else {
                            log.info("Событие доставлено в топик {} (partition={}, offset={}): {}",
                                    topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    json);
                        }
                    });
        } catch (JsonProcessingException e) {
            // Сериализация record'а с простыми полями практически не может
            // завершиться ошибкой, но метод writeValueAsString объявляет
            // checked-исключение, и контракт требует его обработки.
            log.error("Не удалось сериализовать сообщение для топика {}", topic, e);
        }
    }
}