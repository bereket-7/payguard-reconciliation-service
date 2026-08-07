package com.payguard.reconciliation.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

@Component
public class AvroJsonConverter {

    private final ObjectMapper objectMapper;

    public AvroJsonConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(SpecificRecord record) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        record.getSchema().getFields().forEach(field -> payload.put(field.name(), record.get(field.name())));
        return objectMapper.writeValueAsString(payload);
    }
}
