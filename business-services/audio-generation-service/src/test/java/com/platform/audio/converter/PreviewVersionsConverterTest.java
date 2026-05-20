package com.platform.audio.converter;

import com.platform.audio.dto.response.PreviewVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewVersionsConverterTest {

    private final PreviewVersionsConverter converter = new PreviewVersionsConverter();

    @Test
    @DisplayName("convertToDatabaseColumn - null input - returns null")
    void convertToDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    @DisplayName("convertToDatabaseColumn - valid list - returns JSON string")
    void convertToDatabaseColumn_validList_returnsJson() {
        List<PreviewVersion> versions = List.of(
            PreviewVersion.builder().version("voice_only").url("http://minio/v1.mp3").build(),
            PreviewVersion.builder().version("music_voice").url("http://minio/v2.mp3").build());

        String json = converter.convertToDatabaseColumn(versions);

        assertThat(json).isNotNull();
        assertThat(json).contains("voice_only");
        assertThat(json).contains("music_voice");
        assertThat(json).startsWith("[");
    }

    @Test
    @DisplayName("convertToEntityAttribute - null input - returns null")
    void convertToEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("convertToEntityAttribute - blank string - returns null")
    void convertToEntityAttribute_blank_returnsNull() {
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    @DisplayName("convertToEntityAttribute - valid JSON - returns list")
    void convertToEntityAttribute_validJson_returnsList() {
        String json = "[{\"version\":\"voice_only\",\"url\":\"http://minio/v1.mp3\"},{\"version\":\"music_voice\",\"url\":\"http://minio/v2.mp3\"}]";

        List<PreviewVersion> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getVersion()).isEqualTo("voice_only");
        assertThat(result.get(1).getUrl()).isEqualTo("http://minio/v2.mp3");
    }

    @Test
    @DisplayName("convertToEntityAttribute - invalid JSON - returns null")
    void convertToEntityAttribute_invalidJson_returnsNull() {
        assertThat(converter.convertToEntityAttribute("{not-valid-json")).isNull();
    }

    @Test
    @DisplayName("roundtrip - serialize then deserialize - preserves data")
    void roundtrip_serializeThenDeserialize_preservesData() {
        List<PreviewVersion> original = List.of(
            PreviewVersion.builder().version("music_only").url("http://minio/music.mp3").build());

        String json = converter.convertToDatabaseColumn(original);
        List<PreviewVersion> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getVersion()).isEqualTo("music_only");
        assertThat(restored.get(0).getUrl()).isEqualTo("http://minio/music.mp3");
    }
}
