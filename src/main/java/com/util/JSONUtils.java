package com.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class JSONUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static byte[] object2JSON(Object invoke) {

        try {
            return objectMapper.writeValueAsBytes(invoke);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String object2JSONString(Object invoke) {

        try {
            return objectMapper.writeValueAsString(invoke);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }


    public static <T> T str2Object(String str, Class<T> tClass) {
        try {
            return objectMapper.readValue(str, tClass);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            log.error("deserialization fail ,cause" + e);
            throw new RuntimeException(e);
        }
    }


}
