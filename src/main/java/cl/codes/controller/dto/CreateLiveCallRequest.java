package cl.codes.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

public record CreateLiveCallRequest(
        @Size(max = 20000)
        String transcription,
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        @JsonProperty("latitudOperador")
        Double operatorLatitude,
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        @JsonProperty("longitudOperador")
        Double operatorLongitude
) {}
