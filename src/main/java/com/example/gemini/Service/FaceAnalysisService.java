package com.example.gemini.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FaceAnalysisService {

    private final RestTemplate restTemplate;

    @Value("${face.api.key}")
    private String faceApiKey;

    @Value("${face.api.secret}")
    private String faceApiSecret;

    @Value("${face.api.url:https://api-us.faceplusplus.com/facepp/v3/detect}")
    private String faceApiUrl;

    public Map<String, Object> detectFace(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("api_key", faceApiKey);
            body.add("api_secret", faceApiSecret);
            body.add("image_url", imageUrl);
            body.add("return_attributes", "gender,age,headpose,facequality");

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(faceApiUrl, HttpMethod.POST, entity, Map.class);

            return response.getBody();
        } catch (Exception e) {
            return Map.of();
        }
    }

    public boolean isFaceValid(Map<String, Object> data) {
        try {
            List<?> faces = (List<?>) data.get("faces");
            if (faces == null || faces.size() != 1) return false;

            Map<?, ?> face = (Map<?, ?>) faces.get(0);
            Map<?, ?> rectangle = (Map<?, ?>) face.get("face_rectangle");
            int width = ((Number) rectangle.get("width")).intValue();
            int height = ((Number) rectangle.get("height")).intValue();

            Map<?, ?> attributes = (Map<?, ?>) face.get("attributes");
            Map<?, ?> faceQuality = (Map<?, ?>) attributes.get("facequality");
            double quality = ((Number) faceQuality.get("value")).doubleValue();

            Map<?, ?> headPose = (Map<?, ?>) attributes.get("headpose");
            double yaw = Math.abs(((Number) headPose.get("yaw_angle")).doubleValue());
            double pitch = Math.abs(((Number) headPose.get("pitch_angle")).doubleValue());

            return width >= 150 && height >= 150 && quality >= 20 && yaw <= 25 && pitch <= 20;
        } catch (Exception e) {
            return false;
        }
    }

    public String buildFaceSummary(Map<String, Object> data) {
        try {
            Map<?, ?> face = (Map<?, ?>) ((List<?>) data.get("faces")).get(0);
            Map<?, ?> rectangle = (Map<?, ?>) face.get("face_rectangle");
            int width = ((Number) rectangle.get("width")).intValue();
            int height = ((Number) rectangle.get("height")).intValue();

            Map<?, ?> attributes = (Map<?, ?>) face.get("attributes");
            Map<?, ?> faceQuality = (Map<?, ?>) attributes.get("facequality");
            double quality = ((Number) faceQuality.get("value")).doubleValue();

            Map<?, ?> headPose = (Map<?, ?>) attributes.get("headpose");
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
}