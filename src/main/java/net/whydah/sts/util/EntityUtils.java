package net.whydah.sts.util;

import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.MapType;

public class EntityUtils {

	static ObjectMapper objectMapper = new ObjectMapper().configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature())
            .findAndRegisterModules();;

	
	
	public static <T> T copy(T model, Class<T> tClass) throws Exception {
        final byte[] bytes = objectMapper.writeValueAsBytes(model);
        final T copy = objectMapper.readValue(bytes, tClass);
        return copy;
    }

	

	public static String object_mapToJsonString(Object val) {
		try {
			return objectMapper.writeValueAsString(val);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			if (val instanceof Collection<?>){
				return "[]";
			}

			if (val instanceof Map<?,?>){
				return "{}";
			}


		}
		return "{}";
	}

	public static <T> T mapFromJson(String json, Class<T> clazz) throws JsonMappingException, JsonProcessingException
    {
		return objectMapper.readValue(json, clazz);
	}
	
	public static <T> T mapFromJson(String json, JavaType type) throws JsonMappingException, JsonProcessingException
    {
		return objectMapper.readValue(json, type);
	}

	public static <T> T mapFromJson(String json, TypeReference<T> clazz)
			throws JsonProcessingException {
		if(json==null || json.isEmpty()) {
			return null;
		}
		return objectMapper.readValue(json, clazz);
	}





}
