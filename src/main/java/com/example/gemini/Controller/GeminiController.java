package com.example.gemini.Controller;

import com.example.gemini.Service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/gemini")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GeminiController {

    private static final int MAX_SUGGESTIONS = 2;

    private final GeminiService geminiService;

    @PostMapping("/suggest-with-images")
    public Map<String, Object> suggestHairstylesWithImages(@RequestBody RequestBodyDTO request) {

        // ---------- STEP 0: Basic validation ----------
        if (request.getImageUrl() == null || request.getImageUrl().isBlank()) {
            return Map.of("error", "Image URL missing");
        }

        if (request.getHairstyles() == null || request.getHairstyles().isEmpty()) {
            return Map.of("error", "No salon hairstyles provided");
        }

        if (request.getGender() == null || request.getGender().isBlank()) {
            return Map.of("error", "Gender not provided");
        }

        // ---------- STEP 1: Filter hairstyles by gender ----------
        List<ServiceDTO> filteredHairstyles = request.getHairstyles().stream()
                .filter(s -> {
                    if ("men".equalsIgnoreCase(request.getGender()))
                        return "men".equalsIgnoreCase(s.getGenderCategory());
                    if ("women".equalsIgnoreCase(request.getGender()))
                        return "women".equalsIgnoreCase(s.getGenderCategory());
                    if ("kid".equalsIgnoreCase(request.getGender()))
                        return "kid".equalsIgnoreCase(s.getGenderCategory());
                    return false;
                })
                .collect(Collectors.toList());

        if (filteredHairstyles.isEmpty()) {
            return Map.of("error", "No hairstyles available for selected gender");
        }

        // ---------- STEP 2: Build STRICT prompt ----------
        String prompt = buildPrompt(
                request.getImageUrl(),
                filteredHairstyles.stream().map(ServiceDTO::getName).toList(),
                request.getGender()
        );

        // ---------- STEP 3: Call Gemini ----------
        String geminiResponse = geminiService.askGemini(prompt);

        if (geminiResponse == null || geminiResponse.isBlank()) {
            return Map.of("error", "Empty AI response");
        }

        String response = geminiResponse.trim();

        // ---------- STEP 4: Handle special AI replies ----------
        if (response.equalsIgnoreCase("NO_MATCH_FOUND")) {
            return Map.of("error", "No suitable hairstyle found");
        }

        if (response.equalsIgnoreCase("GENDER_MISMATCH")) {
            return Map.of("error", "Uploaded image gender does not match selected gender");
        }

        // ---------- STEP 5: Parse AI response SAFELY ----------
        List<Map<String, String>> suggestions = new ArrayList<>();

        String[] lines = response.split("\n");

        for (String line : lines) {

            if (suggestions.size() >= MAX_SUGGESTIONS) break;

            int idx = line.indexOf(":");
            if (idx <= 0) continue;

            String aiName = line.substring(0, idx).trim().toLowerCase();
            String description = line.substring(idx + 1).trim();

            Optional<ServiceDTO> serviceOpt = filteredHairstyles.stream()
                    .filter(s -> {
                        String salonName = s.getName().toLowerCase();
                        return aiName.equals(salonName)
                                || aiName.contains(salonName)
                                || salonName.contains(aiName);
                    })
                    .findFirst();

            if (serviceOpt.isEmpty()) continue;

            ServiceDTO service = serviceOpt.get();

            // Prevent duplicates
            boolean alreadyAdded = suggestions.stream()
                    .anyMatch(m -> m.get("name").equalsIgnoreCase(service.getName()));

            if (alreadyAdded) continue;

            suggestions.add(Map.of(
                    "name", service.getName(),
                    "description", description,
                    "imageUrl", service.getImageUrl()
            ));
        }

        if (suggestions.isEmpty()) {
            return Map.of("error", "No matching hairstyle found");
        }

        return Map.of("geminiResponse", suggestions);
    }

    // ---------- Prompt builder ----------
    private String buildPrompt(String imageUrl, List<String> services, String gender) {
        return """
You are a STRICT rule-based AI.

RULES (NO EXCEPTIONS):
- Take your time and analyze the image's person's gender accurately
- Suggest ONLY 1 or 2 hairstyles.
- Use ONLY names from the allowed list.
- Copy names EXACTLY from the list.
- Do NOT invent names.
- If no match exists, reply ONLY:
NO_MATCH_FOUND
- If image gender does not match provided gender, reply ONLY:
GENDER_MISMATCH

Gender: %s

Allowed hairstyles:
%s

User image:
%s

FORMAT (STRICT):
Hairstyle Name: short reason
""".formatted(
                gender,
                String.join(", ", services),
                imageUrl
        );
    }

    // ---------- DTOs ----------
    public static class RequestBodyDTO {
        private String imageUrl;
        private List<ServiceDTO> hairstyles;
        private String gender;

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public List<ServiceDTO> getHairstyles() { return hairstyles; }
        public void setHairstyles(List<ServiceDTO> hairstyles) { this.hairstyles = hairstyles; }

        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
    }

    public static class ServiceDTO {
        private String id;
        private String name;
        private String genderCategory;
        private String description;
        private String imageUrl;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getGenderCategory() { return genderCategory; }
        public void setGenderCategory(String genderCategory) { this.genderCategory = genderCategory; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    }
}
