package com.neuroph.train.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Type;

/**
 * Utilidad centralizada para serialización y deserialización JSON con Gson.
 */
public final class JsonUtil {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeSpecialFloatingPointValues()
            .create();

    private static final Gson COMPACT_GSON = new GsonBuilder()
            .serializeSpecialFloatingPointValues()
            .create();

    private JsonUtil() {
    }

    public static String toJson(Object src) {
        if (src == null) return null;
        return COMPACT_GSON.toJson(src);
    }

    public static String toPrettyJson(Object src) {
        if (src == null) return null;
        return GSON.toJson(src);
    }

    public static <T> T fromJson(String json, Class<T> classOfT) {
        if (json == null || json.trim().isEmpty()) return null;
        return COMPACT_GSON.fromJson(json, classOfT);
    }

    public static <T> T fromJson(String json, Type typeOfT) {
        if (json == null || json.trim().isEmpty()) return null;
        return COMPACT_GSON.fromJson(json, typeOfT);
    }
}
