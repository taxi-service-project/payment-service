package com.example.payment_service.config;

import com.example.payment_service.kafka.dto.TripCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class ReactiveKafkaConfig {

    @Bean
    public ReceiverOptions<String, String> tripMatchedReceiverOptions(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());

        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payment-service-reactive-group");

        return ReceiverOptions.<String, String>create(props)
                              .subscription(Collections.singleton("trip_events"));
    }

    @Bean
    public KafkaReceiver<String, String> tripMatchedKafkaReceiver(
            ReceiverOptions<String, String> tripMatchedReceiverOptions) {
        return KafkaReceiver.create(tripMatchedReceiverOptions);
    }

}