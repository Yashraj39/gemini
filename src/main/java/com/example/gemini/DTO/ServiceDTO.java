package com.example.gemini.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ServiceDTO {
    private String id;
    private String name;
    private String genderCategory;
    private String description;
    private String imageUrl;
    private Integer price;
    private Integer time;
}