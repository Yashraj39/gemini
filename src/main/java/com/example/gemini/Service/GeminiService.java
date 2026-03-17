package com.example.gemini.Service;

import com.example.gemini.DTO.AiSuggestionItem;
import com.example.gemini.DTO.ServiceDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client client;
    private final ObjectMapper objectMapper;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String modelName;

    public List<AiSuggestionItem> suggestHairstylesFromImage(
            MultipartFile file,
            String gender,
            List<String> allowedNames,
            String faceSummary
    ) {
        List<AiSuggestionItem> result = tryStructuredSuggestion(file, gender, allowedNames, faceSummary);
        if (!result.isEmpty()) {
            return result;
        }

        result = trySimplifiedStructuredSuggestion(file, gender, allowedNames, faceSummary);
        if (!result.isEmpty()) {
            return result;
        }

        return List.of();
    }

    public List<AiSuggestionItem> buildFallbackSuggestions(
            List<ServiceDTO> services,
            String gender,
            String faceSummary,
            int limit
    ) {
        String summary = safe(faceSummary).toLowerCase();

        return services.stream()
                .filter(Objects::nonNull)
                .filter(s -> s.getName() != null && !s.getName().isBlank())
                .map(service -> {
                    int score = heuristicScore(service.getName(), gender, summary);

                    AiSuggestionItem item = new AiSuggestionItem();
                    item.setName(service.getName());
                    item.setScore(score);
                    item.setReason(buildFallbackReason(service.getName(), summary));
                    return item;
                })
                .sorted(Comparator.comparing(AiSuggestionItem::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AiSuggestionItem::getName))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<AiSuggestionItem> tryStructuredSuggestion(
            MultipartFile file,
            String gender,
            List<String> allowedNames,
            String faceSummary
    ) {
        try {
            byte[] imageBytes = file.getBytes();
            String mimeType = resolveMimeType(file);

            Schema itemSchema = Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(ImmutableMap.of(
                            "name", Schema.builder().type(Type.Known.STRING).build(),
                            "reason", Schema.builder().type(Type.Known.STRING).build(),
                            "score", Schema.builder().type(Type.Known.INTEGER).build()
                    ))
                    .required(List.of("name", "reason", "score"))
                    .build();

            Schema responseSchema = Schema.builder()
                    .type(Type.Known.ARRAY)
                    .items(itemSchema)
                    .build();

            String prompt = """
                    Analyze this uploaded face photo carefully and recommend the 2 best matching hairstyles.

                    User-selected gender: %s

                    Face analysis summary from external detector:
                    %s

                    Allowed hairstyles:
                    %s

                    Strict rules:
                    1. Choose only from the allowed hairstyles list.
                    2. Return exactly 2 items if possible, otherwise 1.
                    3. Use hairstyle names exactly as written in the allowed list.
                    4. Do not invent new styles.
                    5. First visually analyze visible face balance, forehead impression, jawline impression, face length-vs-width balance, and overall suitability.
                    6. Give natural salon-style reasons, not robotic reasons.
                    7. ALWAYS write in second-person tone (use "you" and "your").
                    8. NEVER use "he", "she", "his", "her", or third-person words.
                    9. Reason must explain why the style suits the user directly.
                    10. score must be an integer from 70 to 100.
                    11. Return only valid JSON.
                    """.formatted(gender, faceSummary, String.join(", ", allowedNames));

            Content systemInstruction = Content.fromParts(
                    Part.fromText("""
                            You are an expert salon hairstyle recommender.
                            Be strict, practical, and image-focused.
                            Return only valid JSON.
                            Never include markdown.
                            Never include extra text.
                            Never use a hairstyle outside the allowed list.
                            """)
            );

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .candidateCount(1)
                    .responseMimeType("application/json")
                    .responseSchema(responseSchema)
                    .systemInstruction(systemInstruction)
                    .build();

            Content content = Content.fromParts(
                    Part.fromBytes(imageBytes, mimeType),
                    Part.fromText(prompt)
            );

            GenerateContentResponse response =
                    client.models.generateContent(modelName, content, config);

            String json = response.text();

            if (json == null || json.isBlank()) {
                return List.of();
            }

            List<AiSuggestionItem> items =
                    objectMapper.readValue(json, new TypeReference<List<AiSuggestionItem>>() {});

            return cleanSuggestions(items, allowedNames);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<AiSuggestionItem> trySimplifiedStructuredSuggestion(
            MultipartFile file,
            String gender,
            List<String> allowedNames,
            String faceSummary
    ) {
        try {
            byte[] imageBytes = file.getBytes();
            String mimeType = resolveMimeType(file);

            Schema itemSchema = Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(ImmutableMap.of(
                            "name", Schema.builder().type(Type.Known.STRING).build(),
                            "reason", Schema.builder().type(Type.Known.STRING).build(),
                            "score", Schema.builder().type(Type.Known.INTEGER).build()
                    ))
                    .required(List.of("name", "reason", "score"))
                    .build();

            Schema responseSchema = Schema.builder()
                    .type(Type.Known.ARRAY)
                    .items(itemSchema)
                    .build();

            String prompt = """
                    Pick the best hairstyles for this face image.

                    Gender: %s
                    Face notes: %s
                    Allowed list: %s

                    Return JSON array only.
                    Each item must have: name, reason, score.
                    Choose only from allowed list.
                    Give 2 items if possible.
                    """.formatted(gender, faceSummary, String.join(", ", allowedNames));

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .candidateCount(1)
                    .responseMimeType("application/json")
                    .responseSchema(responseSchema)
                    .build();

            Content content = Content.fromParts(
                    Part.fromBytes(imageBytes, mimeType),
                    Part.fromText(prompt)
            );

            GenerateContentResponse response =
                    client.models.generateContent(modelName, content, config);

            String json = response.text();

            if (json == null || json.isBlank()) {
                return List.of();
            }

            List<AiSuggestionItem> items =
                    objectMapper.readValue(json, new TypeReference<List<AiSuggestionItem>>() {});

            return cleanSuggestions(items, allowedNames);
        } catch (Exception e) {
            return List.of();
        }
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

        return "image/jpeg";
    }

    private List<AiSuggestionItem> cleanSuggestions(List<AiSuggestionItem> items, List<String> allowedNames) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Map<String, String> allowedMap = allowedNames.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toMap(
                        this::normalize,
                        s -> s,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        List<AiSuggestionItem> cleaned = new ArrayList<>();

        for (AiSuggestionItem item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) continue;

            String matchedName = allowedMap.get(normalize(item.getName()));
            if (matchedName == null) continue;

            boolean exists = cleaned.stream()
                    .anyMatch(existing -> normalize(existing.getName()).equals(normalize(matchedName)));
            if (exists) continue;

            AiSuggestionItem cleanedItem = new AiSuggestionItem();
            cleanedItem.setName(matchedName);
            String reason = item.getReason();

            if (reason == null || reason.isBlank()) {
                reason = matchedName + " suits your face balance and overall look.";
            } else {
                reason = convertToSecondPerson(reason);
            }

            cleanedItem.setReason(reason);

            Integer score = item.getScore();
            if (score == null) {
                score = 85;
            } else {
                score = Math.max(70, Math.min(100, score));
            }
            cleanedItem.setScore(score);

            cleaned.add(cleanedItem);

            if (cleaned.size() >= 2) {
                break;
            }
        }

        return cleaned;
    }

    private String convertToSecondPerson(String text) {
        if (text == null) return "";

        String result = text;

        result = result.replaceAll("\\bhis\\b", "your");
        result = result.replaceAll("\\bher\\b", "your");
        result = result.replaceAll("\\bhe\\b", "you");
        result = result.replaceAll("\\bshe\\b", "you");
        result = result.replaceAll("\\bhim\\b", "you");

        // Optional: fix capitalization issues
        result = result.replaceAll("\\bHis\\b", "Your");
        result = result.replaceAll("\\bHer\\b", "Your");
        result = result.replaceAll("\\bHe\\b", "You");
        result = result.replaceAll("\\bShe\\b", "You");

        return result;
    }

    private int heuristicScore(String name, String gender, String faceSummary) {
        String n = safe(name).toLowerCase();
        int score = 72;

        if (faceSummary.contains("balanced oval-like proportion")) {
            score += containsAny(n, "layer", "bob", "lob", "fade", "quiff", "textured", "crop", "side part") ? 12 : 4;
        }

        if (faceSummary.contains("longer than wide")) {
            score += containsAny(n, "fringe", "bang", "bob", "lob", "crop", "textured", "curtain") ? 12 : 3;
        }

        if (faceSummary.contains("wider / rounder balance")) {
            score += containsAny(n, "layer", "long", "volume", "pompadour", "quiff", "side part", "undercut") ? 12 : 3;
        }

        if ("men".equalsIgnoreCase(gender)) {
            score += containsAny(n, "fade", "undercut", "crew", "quiff", "pompadour", "crop", "side part", "buzz") ? 10 : 2;
        } else if ("women".equalsIgnoreCase(gender)) {
            score += containsAny(n, "layer", "bob", "lob", "pixie", "bang", "waves", "u cut", "v cut", "feather") ? 10 : 2;
        } else if ("kid".equalsIgnoreCase(gender)) {
            score += containsAny(n, "classic", "simple", "trim", "short", "cute", "bob", "crop") ? 10 : 2;
        }

        if (containsAny(n, "bridal", "wedding", "chemical", "color", "spa")) {
            score -= 20;
        }

        return Math.max(70, Math.min(95, score));
    }

    private String buildFallbackReason(String name, String faceSummary) {
        String lower = safe(faceSummary).toLowerCase();

        if (lower.contains("balanced oval-like proportion")) {
            return name + " suits balanced facial proportions and should complement your overall look naturally.";
        }

        if (lower.contains("longer than wide")) {
            return name + " can balance a slightly longer face appearance and make the overall look more proportionate.";
        }

        if (lower.contains("wider / rounder balance")) {
            return name + " can add a more flattering frame and help give a sharper, more balanced overall look.";
        }

        return name + " looks suitable for your face and overall appearance.";
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}