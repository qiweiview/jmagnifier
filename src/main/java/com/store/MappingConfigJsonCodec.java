package com.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.Mapping;

public class MappingConfigJsonCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String toJson(Mapping mapping) {
        try {
            return OBJECT_MAPPER.writeValueAsString(mapping);
        } catch (Exception e) {
            throw new RuntimeException("serialize mapping config failed", e);
        }
    }

    public Mapping fromJson(String json) {
        try {
            Mapping mapping = OBJECT_MAPPER.readValue(json, Mapping.class);
            mapping.applyDefaults();
            return mapping;
        } catch (Exception e) {
            throw new RuntimeException("deserialize mapping config failed", e);
        }
    }
}
