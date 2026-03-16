package com.example.gemini.DTO;

import lombok.Data;
import java.util.List;

@Data
public class AiSuggestRequest {
    private String imageUrl;
    private String gender;
    private List<ServiceDTO> hairstyles;
}