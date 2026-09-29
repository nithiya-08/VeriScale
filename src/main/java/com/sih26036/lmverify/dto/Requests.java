package com.sih26036.lmverify.dto;

import com.sih26036.lmverify.entity.Instrument;
import com.sih26036.lmverify.entity.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** All request bodies accepted by the REST API. */
public final class Requests {
    private Requests() {
    }

    public record Register(
            @NotBlank String name,
            @NotBlank @Email String email,
            @Pattern(regexp = "^$|^[0-9+ -]{8,15}$", message = "invalid phone number") String phone,
            @NotBlank @Size(min = 8, message = "must be at least 8 characters") String password) {
    }

    public record Login(@NotBlank String email, @NotBlank String password) {
    }

    public record CreateBusiness(
            @NotBlank String name,
            String address,
            @NotNull Long jurisdictionId,
            Double lat,
            Double lng) {
    }

    public record CreateInstrument(
            @NotNull Long businessId,
            @NotNull Long typeId,
            String make,
            String model,
            @NotBlank String serialNo,
            @Positive Double capacityMax,
            @PositiveOrZero Double capacityMin,
            @Positive Double eValue,
            String modelApprovalNo,
            Instrument.InstallationType installationType) {
    }

    public record CreateApplication(@NotNull Long instrumentId) {
    }

    public record Reassign(@NotNull Long officerId, @NotNull LocalDate scheduledDate) {
    }

    /** One inspection completed on the phone (possibly offline) and uploaded later. */
    public record SyncInspection(
            @NotBlank String clientUuid,
            @NotNull Long assignmentId,
            @NotNull Instant startedAt,
            @NotNull Instant completedAt,
            Double gpsLat,
            Double gpsLng,
            @Size(max = 1000) String remarks,
            @NotEmpty @Valid List<Reading> observations,
            @Valid List<Photo> photos) {
    }

    public record Reading(@NotNull Double testLoad, @NotNull Double indicatedValue) {
    }

    /** Photo compressed on the phone, sent as a data URL ("data:image/jpeg;base64,..."). */
    public record Photo(@NotBlank String dataUrl, Instant capturedAt, Double lat, Double lng) {
    }

    public record SyncBatch(@NotEmpty @Valid List<SyncInspection> inspections) {
    }

    public record Revoke(@NotBlank String reason) {
    }

    public record CreateOfficer(
            @NotBlank String name,
            @NotBlank @Email String email,
            String phone,
            @NotBlank @Size(min = 8) String password,
            @NotNull User.Role role,
            @NotNull Long jurisdictionId,
            /** GATC only: centre name and authorised instrument type ids. */
            String gatcName,
            Set<Long> authorisedTypeIds) {
    }

    public record UpdateInstrumentType(
            @NotNull @Positive Integer validityMonths,
            @NotNull @PositiveOrZero Integer fee,
            Double mpePercent) {
    }

    public record UpdateFraudFlag(@NotBlank String status) {
    }
}
