package com.example.gemini.Controller;

import com.example.gemini.DTO.AiSuggestionItem;
import com.example.gemini.DTO.ServiceDTO;
import com.example.gemini.Service.FaceAnalysisService;
import com.example.gemini.Service.GeminiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/gemini")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GeminiController {

    private static final int MAX_SUGGESTIONS = 2;

    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
    );

    private final GeminiService geminiService;
    private final FaceAnalysisService faceAnalysisService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/suggest-with-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> suggestHairstylesWithImages(
            @RequestPart("file") MultipartFile file,
            @RequestPart("gender") String gender,
            @RequestPart("hairstyles") String hairstylesJson
    ) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Please upload your photo"));
            }

            String mimeType = resolveMimeType(file);
            if (!SUPPORTED_IMAGE_TYPES.contains(mimeType)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unsupported image format. Please upload JPG, PNG, WEBP, HEIC, or HEIF image."
                ));
            }

            if (gender == null || gender.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Please select gender"));
            }

            List<ServiceDTO> hairstyles = objectMapper.readValue(
                    hairstylesJson,
                    new TypeReference<List<ServiceDTO>>() {}
            );

            if (hairstyles == null || hairstyles.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No salon hairstyles provided"));
            }

            List<ServiceDTO> filteredHairstyles = hairstyles.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> s.getGenderCategory() != null
                            && s.getGenderCategory().equalsIgnoreCase(gender))
                    .filter(s -> s.getName() != null && !s.getName().isBlank())
                    .toList();

            if (filteredHairstyles.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No hairstyles available for selected gender"));
            }

            Map<String, Object> faceData = faceAnalysisService.detectFace(file);

            if (!faceAnalysisService.isFaceValid(faceData)) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Please upload a clear front-facing photo with one visible face")
                );
            }

            String faceSummary = faceAnalysisService.buildFaceSummary(faceData);

            List<String> allowedNames = filteredHairstyles.stream()
                    .map(ServiceDTO::getName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            List<AiSuggestionItem> aiItems = geminiService.suggestHairstylesFromImage(
                    file,
                    gender,
                    allowedNames,
                    faceSummary
            );

            Map<String, ServiceDTO> serviceMap = filteredHairstyles.stream()
                    .collect(Collectors.toMap(
                            s -> normalize(s.getName()),
                            Function.identity(),
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            List<Map<String, Object>> suggestions = new ArrayList<>();

            for (AiSuggestionItem item : aiItems) {
                if (item == null || item.getName() == null || item.getName().isBlank()) continue;

                ServiceDTO matched = serviceMap.get(normalize(item.getName()));
                if (matched == null) continue;

                boolean alreadyAdded = suggestions.stream()
                        .anyMatch(m -> String.valueOf(m.get("name")).equalsIgnoreCase(matched.getName()));
                if (alreadyAdded) continue;

                suggestions.add(buildSuggestionMap(
                        matched,
                        item.getReason() == null || item.getReason().isBlank()
                                ? defaultReason(matched.getName())
                                : item.getReason(),
                        item.getScore() == null ? 85 : item.getScore()
                ));

                if (suggestions.size() >= MAX_SUGGESTIONS) break;
            }

            String source = "ai";

            if (suggestions.isEmpty()) {
                List<AiSuggestionItem> fallbackItems = geminiService.buildFallbackSuggestions(
                        filteredHairstyles,
                        gender,
                        faceSummary,
                        MAX_SUGGESTIONS
                );

                for (AiSuggestionItem item : fallbackItems) {
                    ServiceDTO matched = serviceMap.get(normalize(item.getName()));
                    if (matched == null) continue;

                    suggestions.add(buildSuggestionMap(
                            matched,
                            item.getReason(),
                            item.getScore() == null ? 80 : item.getScore()
                    ));
                }

                source = "fallback";
            }

            if (suggestions.isEmpty()) {
                for (ServiceDTO service : filteredHairstyles.stream().limit(MAX_SUGGESTIONS).toList()) {
                    suggestions.add(buildSuggestionMap(
                            service,
                            "This style looks like a suitable option based on your uploaded photo.",
                            75
                    ));
                }
                source = "fallback";
            }

            return ResponseEntity.ok(Map.of(
                    "geminiResponse", suggestions,
                    "source", source
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of(
                            "error", "AI suggestion failed",
                            "message", e.getMessage() == null ? "Unknown backend error" : e.getMessage()
                    )
            );
        }
    }

    private Map<String, Object> buildSuggestionMap(ServiceDTO matched, String reason, Integer score) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", matched.getName());
        map.put("description", reason);
        map.put("imageUrl", matched.getImageUrl() == null ? "" : matched.getImageUrl());
        map.put("score", score);
        return map;
    }

    private String resolveMimeType(MultipartFile file) {
        String mimeType = file.getContentType();

        if (mimeType != null && !mimeType.isBlank()) {
            return mimeType.toLowerCase();
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            return "image/jpeg";
        }

        String lower = fileName.toLowerCase();

        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";

        return "application/octet-stream";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String defaultReason(String name) {
        return name + " looks suitable for your face and overall appearance.";
    }
}