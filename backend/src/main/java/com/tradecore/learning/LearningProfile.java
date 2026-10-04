package com.tradecore.learning;

import com.tradecore.market.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "learning_profile", uniqueConstraints =
        @UniqueConstraint(name = "uq_learning_profile_instrument", columnNames = "instrument_id"))
public class LearningProfile {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false, unique = true)
    private Instrument instrument;

    @Column(nullable = false, length = 100) private String sector;
    @Column(name = "business_type", nullable = false, length = 120) private String businessType;
    @Column(name = "business_description", nullable = false, columnDefinition = "TEXT") private String businessDescription;
    @Column(name = "major_business_factors", nullable = false, columnDefinition = "TEXT") private String majorBusinessFactors;
    @Column(name = "common_price_drivers", nullable = false, columnDefinition = "TEXT") private String commonPriceDrivers;
    @Column(name = "important_risks", nullable = false, columnDefinition = "TEXT") private String importantRisks;
    @Column(name = "educational_observations", nullable = false, columnDefinition = "TEXT") private String educationalObservations;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected LearningProfile() { }

    public UUID getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public String getSector() { return sector; }
    public String getBusinessType() { return businessType; }
    public String getBusinessDescription() { return businessDescription; }
    public String getMajorBusinessFactors() { return majorBusinessFactors; }
    public String getCommonPriceDrivers() { return commonPriceDrivers; }
    public String getImportantRisks() { return importantRisks; }
    public String getEducationalObservations() { return educationalObservations; }
    public Instant getUpdatedAt() { return updatedAt; }
}
