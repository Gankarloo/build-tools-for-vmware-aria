package com.vmware.pscoe.iac.artifact.rest.helpers;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonHelper {

    private static final Logger logger = LoggerFactory.getLogger(JsonHelper.class);

    public static String getPrettyJson(String json) {
        try{
            Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
            return gson.toJson(JsonParser.parseString(json));
        }catch(JsonSyntaxException e) {
            logger.error("Unable to parse Json[" + json + "]", e);
            return json;
        }
    }

    public static String toJson(Map<String, Object> map) {
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        return gson.toJson(map);
    }

    public static String toSortedJson(Map<String, Object> map) {
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        return gson.toJson(toTreeMap(map));
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<String, Object> toTreeMap(Map<String, Object> obj) {
        TreeMap<String, Object> result = new TreeMap<String, Object>();
        obj.forEach((key, value) -> {
            result.put(key, value instanceof Map ? JsonHelper.toTreeMap((Map<String, Object>) value) : value);
        });
        return result;
    }

    public static <U> U getAs(JsonObject el, String property, Function<JsonElement, U> mapper) {
        return Optional.ofNullable(el.get(property)).map(mapper).orElse(null);
    }

    public static <U> U getDefAs(JsonObject el, String property, Function<JsonElement, U> mapper, U def) {
        return Optional.ofNullable(el.get(property)).map(mapper).orElse(def);
    }


}