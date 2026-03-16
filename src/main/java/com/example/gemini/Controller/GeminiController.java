package com.example.gemini.Controller;

import com.example.gemini.DTO.AiSuggestRequest;
import com.example.gemini.DTO.AiSuggestionItem;
import com.example.gemini.DTO.ServiceDTO;
import com.example.gemini.Service.FaceAnalysisService;
import com.example.gemini.Service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/gemini")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GeminiController {

    private static final int MAX_SUGGESTIONS = 2;

    private final GeminiService geminiService;
    private final FaceAnalysisService faceAnalysisService;

    @PostMapping("/suggest-with-images")
    public Map<String, Object> suggestHairstylesWithImages(@RequestBody AiSuggestRequest request) {
        try {
            if (request.getImageUrl() == null || request.getImageUrl().isBlank()) {
                return Map.of("error", "Image URL missing");
            }

            if (request.getHairstyles() == null || request.getHairstyles().isEmpty()) {
                return Map.of("error", "No salon hairstyles provided");
            }

            if (request.getGender() == null || request.getGender().isBlank()) {
                return Map.of("error", "Gender not provided");
            }

            List<ServiceDTO> filteredHairstyles = request.getHairstyles().stream()
                    .filter(s -> s.getGenderCategory() != null
                            && s.getGenderCategory().equalsIgnoreCase(request.getGender()))
                    .filter(s -> s.getName() != null && !s.getName().isBlank())
                    .toList();

            if (filteredHairstyles.isEmpty()) {
                return Map.of("error", "No hairstyles available for selected gender");
            }

            Map<String, Object> faceData = faceAnalysisService.detectFace(request.getImageUrl());
            if (!faceAnalysisService.isFaceValid(faceData)) {
                return Map.of("error", "Uploaded image does not contain a usable human face");
            }

            String faceSummary = faceAnalysisService.buildFaceSummary(faceData);

            List<String> allowedNames = filteredHairstyles.stream()
                    .map(ServiceDTO::getName)
                    .distinct()
                    .toList();

            List<AiSuggestionItem> aiItems = geminiService.suggestHairstylesFromImage(
                    request.getImageUrl(),
                    request.getGender(),
                    allowedNames,
                    faceSummary
            );

            if (aiItems == null || aiItems.isEmpty()) {
                return Map.of("error", "No matching hairstyle found");
            }

            Map<String, ServiceDTO> serviceMap = filteredHairstyles.stream()
                    .collect(Collectors.toMap(
                            s -> normalize(s.getName()),
                            Function.identity(),
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            List<Map<String, String>> suggestions = new ArrayList<>();

            for (AiSuggestionItem item : aiItems) {
                if (item == null || item.getName() == null || item.getName().isBlank()) continue;

                ServiceDTO matched = serviceMap.get(normalize(item.getName()));
                if (matched == null) continue;

                boolean alreadyAdded = suggestions.stream()
                        .anyMatch(m -> m.get("name").equalsIgnoreCase(matched.getName()));
                if (alreadyAdded) continue;

                suggestions.add(Map.of(
                        "name", matched.getName(),
                        "description", item.getReason() == null ? "" : item.getReason(),
                        "imageUrl", matched.getImageUrl() == null ? "" : matched.getImageUrl()
                ));

                if (suggestions.size() >= MAX_SUGGESTIONS) break;
            }

            if (suggestions.isEmpty()) {
                return Map.of("error", "No matching hairstyle found");
            }

            return Map.of("geminiResponse", suggestions);

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("error", "AI suggestion failed");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}