package com.tradecore.learning;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class LearningProfileService {
    private final LearningProfileRepository profiles;

    public LearningProfileService(LearningProfileRepository profiles) { this.profiles = profiles; }

    public List<LearningProfileResponse> list() {
        return profiles.findNseProfiles().stream().map(LearningProfileResponse::from).toList();
    }

    public LearningProfileResponse get(String symbol) {
        return profiles.findNseProfileBySymbol(symbol.trim().toUpperCase(Locale.ROOT))
                .map(LearningProfileResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learning profile not found"));
    }
}
