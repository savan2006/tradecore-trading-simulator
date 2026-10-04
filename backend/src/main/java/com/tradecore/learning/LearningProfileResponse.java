package com.tradecore.learning;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record LearningProfileResponse(UUID instrumentId, String exchange, String symbol, String companyName,
        String sector, String businessType, String businessDescription, List<String> majorBusinessFactors,
        List<String> commonPriceDrivers, List<String> importantRisks, List<String> educationalObservations,
        Instant updatedAt) {
    static LearningProfileResponse from(LearningProfile profile) {
        var instrument = profile.getInstrument();
        return new LearningProfileResponse(instrument.getId(), instrument.getExchange(), instrument.getSymbol(),
                instrument.getCompanyName(), profile.getSector(), profile.getBusinessType(),
                profile.getBusinessDescription(), split(profile.getMajorBusinessFactors()),
                split(profile.getCommonPriceDrivers()), split(profile.getImportantRisks()),
                split(profile.getEducationalObservations()), profile.getUpdatedAt());
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split("\\|", -1)).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
