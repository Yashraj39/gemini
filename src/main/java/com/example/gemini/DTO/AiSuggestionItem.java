package com.example.gemini.DTO;

import lombok.Data;

@Data
public class AiSuggestionItem {
    private String name;
    private String reason;
    private Integer score;
}