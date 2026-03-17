package com.example.gemini.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

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

    public Map<String, Object> detectFace(MultipartFile file) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ByteArrayResource imageResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() == null ? "image.jpg" : file.getOriginalFilename();
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("api_key", faceApiKey);
            body.add("api_secret", faceApiSecret);
            body.add("image_file", imageResource);
            body.add("return_attributes", "gender,age,headpose,facequality");

            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(faceApiUrl, HttpMethod.POST, entity, Map.class);

            return response.getBody() == null ? Map.of() : response.getBody();
        } catch (Exception e) {
            e.printStackTrace();
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
            double roll = Math.abs(((Number) headPose.get("roll_angle")).doubleValue());

            return width >= 120 && height >= 120 && quality >= 18 && yaw <= 30 && pitch <= 25 && roll <= 20;
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
            double roll = ((Number) headPose.get("roll_angle")).doubleValue();

            String detectedGender = "unknown";
            if (attributes.get("gender") instanceof Map<?, ?> genderMap && genderMap.get("value") != null) {
                detectedGender = String.valueOf(genderMap.get("value"));
            }

            Integer age = null;
            if (attributes.get("age") instanceof Map<?, ?> ageMap && ageMap.get("value") != null) {
                age = ((Number) ageMap.get("value")).intValue();
            }

            double ratio = height == 0 ? 1.0 : (double) width / (double) height;
            String faceBalance;
            if (ratio < 0.78) {
                faceBalance = "longer than wide";
            } else if (ratio > 0.92) {
                faceBalance = "wider / rounder balance";
            } else {
                faceBalance = "balanced oval-like proportion";
            }

            return """
                    Face detected: YES
                    Detected face count: 1
                    Face size: %dx%d pixels
                    Face quality score: %.2f
                    Head pose: yaw %.2f°, pitch %.2f°, roll %.2f°
                    Estimated face proportion: %s
                    Detected gender from face API: %s
                    Estimated age: %s
                    Recommendation goal: suggest hairstyles that suit visible face balance, forehead/jaw impression, and overall look
                    """.formatted(
                    width,
                    height,
                    quality,
                    yaw,
                    pitch,
                    roll,
                    faceBalance,
                    detectedGender,
                    age == null ? "unknown" : age
            );
        } catch (Exception e) {
            return "Face detected: YES";
        }
    }
}