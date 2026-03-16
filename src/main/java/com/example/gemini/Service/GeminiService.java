package com.example.gemini.Service;

import com.example.gemini.DTO.AiSuggestionItem;
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
import org.springframework.stereotype.Service;

import java.net.URL;
import java.net.URLConnection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client client;
    private final ObjectMapper objectMapper;

    public List<AiSuggestionItem> suggestHairstylesFromImage(
            String imageUrl,
            String gender,
            List<String> allowedNames,
            String faceSummary
    ) throws Exception {

        byte[] imageBytes = new URL(imageUrl).openStream().readAllBytes();
        String mimeType = URLConnection.guessContentTypeFromName(imageUrl);
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "image/jpeg";
        }

        Schema itemSchema = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(ImmutableMap.of(
                        "name", Schema.builder().type(Type.Known.STRING).build(),
                        "reason", Schema.builder().type(Type.Known.STRING).build()
                ))
                .required(List.of("name", "reason"))
                .build();

        Schema responseSchema = Schema.builder()
                .type(Type.Known.ARRAY)
                .items(itemSchema)
                .build();

        String prompt = """
                Analyze the uploaded face image and recommend the 2 best matching hairstyles.

                User-selected gender: %s

                Face validation summary:
                %s

                Allowed hairstyles:
                %s

                Rules:
                1. Choose only from the allowed hairstyles list.
                2. Return exactly 2 items if possible, otherwise 1 item.
                3. Use the hairstyle name exactly as written in the allowed list.
                4. Do not invent any new hairstyle.
                5. Prefer recommendations based on visible face shape, hairline, forehead, jawline, and overall suitability from the image.
                6. Keep reason short and practical.
                """.formatted(gender, faceSummary, String.join(", ", allowedNames));

        Content systemInstruction = Content.fromParts(
                Part.fromText("""
                        You are a strict salon hairstyle recommender.
                        You must only return valid JSON matching the schema.
                        Never include markdown.
                        Never include extra text.
                        Never include hairstyles outside the allowed list.
                        """)
        );

        GenerateContentConfig config = GenerateContentConfig.builder()
                .candidateCount(1)
                .responseMimeType("application/json")
                .responseSchema(responseSchema)
                .systemInstruction(systemInstruction)
                .build();

        Content content = Content.fromParts(
                Part.fromText(prompt),
                Part.fromBytes(imageBytes, mimeType)
        );

        GenerateContentResponse response =
                client.models.generateContent("gemini-2.5-flash", content, config);

        String json = response.text();
        if (json == null || json.isBlank()) {
            throw new RuntimeException("Empty Gemini response");
        }

        return objectMapper.readValue(json, new TypeReference<List<AiSuggestionItem>>() {});
    }
}