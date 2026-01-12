package com.example.gemini.DTO;

import lombok.Data;

import java.util.List;

@Data
public class GeminiRequest {
    private String imageUrl;
    private String gender;
    private List<String> services;
}
