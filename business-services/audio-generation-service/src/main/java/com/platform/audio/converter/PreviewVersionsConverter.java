package com.platform.audio.converter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audio.dto.response.PreviewVersion;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class PreviewVersionsConverter implements AttributeConverter<List<PreviewVersion>, String> {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<PreviewVersion> attribute) {
        if (attribute == null) return null;
        try { return mapper.writeValueAsString(attribute); }
        catch (Exception e) { return null; }
    }

    @Override
    public List<PreviewVersion> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try { return mapper.readValue(dbData, new TypeReference<>() {}); }
        catch (Exception e) { return null; }
    }
}
