package com.capture;

public enum PayloadStoreType {

    NONE,

    PREVIEW_ONLY,

    FILE,

    FILE_DELETED;

    public static PayloadStoreType fromConfig(String value) {
        if (value == null || value.trim().length() == 0) {
            return PREVIEW_ONLY;
        }
        try {
            return PayloadStoreType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignore) {
            return PREVIEW_ONLY;
        }
    }
}
