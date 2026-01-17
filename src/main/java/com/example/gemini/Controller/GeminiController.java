package com.example.gemini.Controller;

import com.example.gemini.Service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/gemini")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GeminiController {

    private static final int MAX_SUGGESTIONS = 2;
    private final GeminiService geminiService;

    private final String FACE_API_KEY = "36BjfIi76odmGeaScwMGl3GIzcU5BbzT";
    private final String FACE_API_SECRET = "MrbFywcX2R8gSpecLyU8fttoiVRD9n3Q";
    private final String FACE_API_URL = "https://api-us.faceplusplus.com/facepp/v3/detect";

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
                .filter(s -> s.getGenderCategory().equalsIgnoreCase(request.getGender()))
                .collect(Collectors.toList());
        if (filteredHairstyles.isEmpty()) {
            return Map.of("error", "No hairstyles available for selected gender");
        }

        // ---------- STEP 2: Call Face++ API ONCE ----------
        Map<String, Object> faceData = callFacePlusPlus(request.getImageUrl());
        if (!isFaceValid(faceData)) {
            return Map.of("error", "Uploaded image does not contain a usable human face");
        }

        // ---------- STEP 3: Build Face Summary ----------
        String faceSummary = buildFaceSummary(faceData);

        // ---------- STEP 4: Build Gemini prompt ----------
        String prompt = buildPrompt(faceSummary,
                filteredHairstyles.stream().map(ServiceDTO::getName).toList(),
                request.getGender()
        );

        // ---------- STEP 5: Call Gemini ----------
        String geminiResponse = geminiService.askGemini(prompt);

        System.out.println("===== GEMINI RAW RESPONSE START =====");
        System.out.println(geminiResponse);
        System.out.println("===== GEMINI RAW RESPONSE END =====");

        if (geminiResponse == null || geminiResponse.isBlank()) {
            return Map.of("error", "Empty AI response");
        }

        // ---------- STEP 6: Parse Gemini response ----------
        List<Map<String, String>> suggestions = new ArrayList<>();
        String[] lines = geminiResponse.split("\n");

        for (String line : lines) {
            if (suggestions.size() >= MAX_SUGGESTIONS) break;
            int idx = line.lastIndexOf(":");
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

    // ---------- Face++ API call ----------
    private Map<String, Object> callFacePlusPlus(String imageUrl) {
        try {
            RestTemplate rest = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = "api_key=" + FACE_API_KEY +
                    "&api_secret=" + FACE_API_SECRET +
                    "&image_url=" + imageUrl +
                    "&return_attributes=gender,age,headpose,facequality";

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = rest.exchange(FACE_API_URL, HttpMethod.POST, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(); // empty map on failure
        }
    }

    // ---------- Validate face ----------
    private boolean isFaceValid(Map<String, Object> data) {
        try {
            List faces = (List) data.get("faces");
            if (faces == null || faces.size() != 1) return false;

            Map face = (Map) faces.get(0);
            Map rectangle = (Map) face.get("face_rectangle");
            int width = ((Number) rectangle.get("width")).intValue();
            int height = ((Number) rectangle.get("height")).intValue();

            Map attributes = (Map) face.get("attributes");
            Map faceQuality = (Map) attributes.get("facequality");
            double quality = ((Number) faceQuality.get("value")).doubleValue();

            Map headPose = (Map) attributes.get("headpose");
            double yaw = Math.abs(((Number) headPose.get("yaw_angle")).doubleValue());
            double pitch = Math.abs(((Number) headPose.get("pitch_angle")).doubleValue());

            return width >= 150 && height >= 150 && quality >= 20 && yaw <= 25 && pitch <= 20;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Build Face Summary ----------
    private String buildFaceSummary(Map<String, Object> data) {
        try {
            Map face = (Map) ((List) data.get("faces")).get(0);
            Map rectangle = (Map) face.get("face_rectangle");
            int width = ((Number) rectangle.get("width")).intValue();
            int height = ((Number) rectangle.get("height")).intValue();

            Map attributes = (Map) face.get("attributes");
            Map faceQuality = (Map) attributes.get("facequality");
            double quality = ((Number) faceQuality.get("value")).doubleValue();

            Map headPose = (Map) attributes.get("headpose");
            double yaw = ((Number) headPose.get("yaw_angle")).doubleValue();
            double pitch = ((Number) headPose.get("pitch_angle")).doubleValue();

            return """
Face detected: YES
Face size: %dx%d pixels
Face quality score: %.2f
Head pose: yaw %.2f°, pitch %.2f°
""".formatted(width, height, quality, yaw, pitch);
        } catch (Exception e) {
            return "Face detected: YES";
        }
    }

    // ---------- Gemini prompt builder ----------
    private String buildPrompt(String faceSummary, List<String> services, String gender) {
        return """
You are a STRICT rule-based AI.

Face analysis (from vision system):
%s

This analysis is VERIFIED and TRUSTED.

Provided Gender (user-selected): %s

Allowed hairstyles:
%s

RULES (NO EXCEPTIONS):
- Do NOT question the face analysis.
- Suggest ONLY 1 or 2 hairstyles.
- Use ONLY names from the allowed list.
- Copy names EXACTLY from the list.
- Do NOT invent names.
- Base decision ONLY on face summary and gender.
- If no suitable hairstyle exists, reply ONLY:
NO_MATCH_FOUND

FORMAT (STRICT):
Hairstyle Name: short reason
""".formatted(faceSummary, gender, String.join(", ", services));
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
