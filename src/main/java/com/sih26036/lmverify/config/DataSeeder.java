package com.sih26036.lmverify.config;

import com.sih26036.lmverify.entity.*;
import com.sih26036.lmverify.repository.*;
import com.sih26036.lmverify.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Demo data for Tamil Nadu, loaded only into an empty database. Covers every status so the demo
 * works immediately: two inspections scheduled today, valid / due-soon / expired / revoked
 * certificates, a failed inspection, an unassigned application and a fraud flag.
 *
 * Validity months, fees and error percentages below are DEMO VALUES - VERIFY against the Gazette
 * before quoting them. The admin can change them from the dashboard.
 *
 * All demo accounts use the password in DEMO_PASSWORD (see README).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    static final String DEMO_PASSWORD = "Demo@123";
    private static final String STATE = "Tamil Nadu";

    private final UserRepository userRepository;
    private final JurisdictionRepository jurisdictionRepository;
    private final InstrumentTypeRepository typeRepository;
    private final BusinessRepository businessRepository;
    private final InstrumentRepository instrumentRepository;
    private final GatcRepository gatcRepository;
    private final ApplicationRepository applicationRepository;
    private final AssignmentRepository assignmentRepository;
    private final InspectionRepository inspectionRepository;
    private final AllocationService allocationService;
    private final CertificateService certificateService;
    private final WorkflowService workflowService;
    private final FraudService fraudService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        log.info("Empty database - loading Tamil Nadu demo data");

        Jurisdiction chennai = district("Chennai");
        Jurisdiction coimbatore = district("Coimbatore");
        Jurisdiction madurai = district("Madurai");
        district("Tiruchirappalli");
        district("Salem");

        InstrumentType scale = type("Electronic weighing scale (Class III)", "Non-automatic weighing instrument", "III",
                InstrumentType.ErrorModel.OIML_R76, null, "kg", 12, 300);
        InstrumentType balance = type("Precision balance (Class II)", "Non-automatic weighing instrument", "II",
                InstrumentType.ErrorModel.OIML_R76, null, "g", 12, 500);
        InstrumentType counter = type("Counter machine", "Non-automatic weighing instrument", "IIII",
                InstrumentType.ErrorModel.OIML_R76, null, "kg", 24, 150);
        type("Beam scale", "Non-automatic weighing instrument", "IIII",
                InstrumentType.ErrorModel.OIML_R76, null, "kg", 24, 150);
        InstrumentType fuel = type("Fuel dispenser (petrol/diesel)", "Measuring instrument", null,
                InstrumentType.ErrorModel.PERCENT, 0.5, "L", 24, 1000);
        type("Commercial weight", "Weight", null, InstrumentType.ErrorModel.PERCENT, 0.05, "g", 24, 50);
        type("Capacity measure", "Measure", null, InstrumentType.ErrorModel.PERCENT, 0.5, "L", 24, 100);

        User admin = user("Controller of Legal Metrology", "admin@lm.demo", User.Role.STATE_ADMIN, null);
        User lmoChennai = user("R. Karthik", "lmo.chennai@lm.demo", User.Role.LMO, chennai);
        user("S. Priya", "lmo2.chennai@lm.demo", User.Role.LMO, chennai);
        User lmoMadurai = user("M. Senthil", "lmo.madurai@lm.demo", User.Role.LMO, madurai);
        user("A. Deepa", "lmo.coimbatore@lm.demo", User.Role.LMO, coimbatore);
        User gatcUser = user("Chennai Precision Test Centre", "gatc.chennai@lm.demo", User.Role.GATC, chennai);
        gatcRepository.save(Gatc.builder().user(gatcUser).name("Chennai Precision Test Centre")
                .jurisdiction(chennai).authorisedTypes(Set.of(scale, counter)).build());

        User owner = user("Murugan K", "owner@lm.demo", User.Role.OWNER, null);
        User owner2 = user("Lakshmi R", "owner2@lm.demo", User.Role.OWNER, null);

        Business stores = business(owner, "Sri Murugan Stores", "12 Usman Road, T. Nagar", chennai, 13.0418, 80.2341);
        Business fuels = business(owner, "Balaji Fuels", "Anna Salai, Teynampet", chennai, 13.0450, 80.2505);
        Business jewellers = business(owner2, "Meenakshi Jewellers", "South Masi Street", madurai, 9.9170, 78.1190);
        Business mart = business(owner2, "Kovai Fresh Mart", "Gandhipuram", coimbatore, 11.0168, 76.9558);

        Instant now = Instant.now();

        // 1-2. Scheduled for today with the Chennai LMO: the offline inspection demo.
        Application a1 = submit(instrument(stores, scale, "ES-CH-1001", "Essae", "DS-852", 30.0, 0.1, 0.005));
        allocationService.autoAssign(a1, null);
        Application a2 = submit(instrument(fuels, fuel, "FD-CH-2001", "Tokheim", "Quantium 510", 9999.0, 2.0, null));
        allocationService.manualAssign(a2, lmoChennai, LocalDate.now(CertificateService.ZONE), admin);

        // 3. Certified ~11 months ago: expires in about 20 days -> DUE_SOON + reminder.
        Instrument i3 = instrument(stores, scale, "ES-CH-1002", "Essae", "DS-852", 30.0, 0.1, 0.005);
        historical(i3, lmoChennai, now.minus(345, ChronoUnit.DAYS), true, stores.getLat(), stores.getLng(), false);

        // 4. Valid, issued 2 months ago.
        Instrument i4 = instrument(fuels, fuel, "FD-CH-2002", "Tokheim", "Quantium 510", 9999.0, 2.0, null);
        historical(i4, lmoChennai, now.minus(60, ChronoUnit.DAYS), true, fuels.getLat(), fuels.getLng(), false);

        // 5. Expired 5 days ago (24-month validity).
        Instrument i5 = instrument(stores, counter, "CM-CH-3001", "Avery", "Counter 20", 20.0, 0.5, 0.01);
        historical(i5, lmoChennai, now.minus(735, ChronoUnit.DAYS), true, stores.getLat(), stores.getLng(), false);

        // 6. Revoked: seal found broken.
        Instrument i6 = instrument(jewellers, balance, "PB-MD-4001", "Shimadzu", "ATY224", 220.0, 0.02, 0.001);
        Certificate revoked = historical(i6, lmoMadurai, now.minus(30, ChronoUnit.DAYS), true,
                jewellers.getLat(), jewellers.getLng(), false);
        certificateService.revoke(revoked.getId(), "Seal found broken during surprise check", admin);

        // 7. Failed inspection: readings outside the permissible error.
        Instrument i7 = instrument(jewellers, balance, "PB-MD-4002", "Contech", "CA-223", 220.0, 0.02, 0.001);
        historical(i7, lmoMadurai, now.minus(3, ChronoUnit.DAYS), false, jewellers.getLat(), jewellers.getLng(), false);

        // 8. Valid certificate whose inspection GPS was ~4 km away -> fraud flag F1 for review.
        Instrument i8 = instrument(fuels, fuel, "FD-CH-2003", "Gilbarco", "Encore 700", 9999.0, 2.0, null);
        historical(i8, lmoChennai, now.minus(10, ChronoUnit.DAYS), true, 13.0827, 80.2707, true);

        // 9. Submitted but not yet assigned: the admin can demo "Auto-assign".
        submit(instrument(mart, scale, "ES-CB-5001", "Atco", "PC-30", 30.0, 0.1, 0.005));

        log.info("Demo data loaded. Log in with any @lm.demo account, password {}", DEMO_PASSWORD);
    }

    private Jurisdiction district(String name) {
        return jurisdictionRepository.save(Jurisdiction.builder().state(STATE).district(name).build());
    }

    private InstrumentType type(String name, String category, String cls, InstrumentType.ErrorModel model,
                                Double mpe, String unit, int validity, int fee) {
        return typeRepository.save(InstrumentType.builder().name(name).category(category).accuracyClass(cls)
                .errorModel(model).mpePercent(mpe).unit(unit).validityMonths(validity).fee(fee).build());
    }

    private User user(String name, String email, User.Role role, Jurisdiction j) {
        return userRepository.save(User.builder().name(name).email(email).phone("9000000000")
                .passwordHash(passwordEncoder.encode(DEMO_PASSWORD)).role(role).jurisdiction(j)
                .state(role == User.Role.OWNER ? null : STATE).build());
    }

    private Business business(User owner, String name, String address, Jurisdiction j, double lat, double lng) {
        return businessRepository.save(Business.builder().owner(owner).name(name).address(address)
                .jurisdiction(j).lat(lat).lng(lng).build());
    }

    private Instrument instrument(Business b, InstrumentType t, String serial, String make, String model,
                                  Double max, Double min, Double e) {
        return instrumentRepository.save(Instrument.builder().business(b).type(t).serialNo(serial).make(make)
                .model(model).capacityMax(max).capacityMin(min).eValue(e)
                .modelApprovalNo("IND/09/" + serial.substring(0, 2) + "/101")
                .installationType(t.getErrorModel() == InstrumentType.ErrorModel.PERCENT
                        ? Instrument.InstallationType.FIXED : Instrument.InstallationType.PORTABLE)
                .build());
    }

    private Application submit(Instrument i) {
        return applicationRepository.save(Application.builder().instrument(i)
                .type(Application.Type.NEW).status(Application.Status.SUBMITTED).build());
    }

    /** Creates a past application -> assignment -> inspection (-> certificate) chain dated at {@code at}. */
    private Certificate historical(Instrument i, User officer, Instant at, boolean pass,
                                   double gpsLat, double gpsLng, boolean runFraudRules) {
        Application app = applicationRepository.save(Application.builder().instrument(i)
                .type(Application.Type.NEW).status(Application.Status.SUBMITTED)
                .submittedAt(at.minus(2, ChronoUnit.DAYS)).build());
        Assignment asg = assignmentRepository.save(Assignment.builder().application(app).officer(officer)
                .scheduledDate(at.atZone(CertificateService.ZONE).toLocalDate())
                .assignedBy(Assignment.AssignedBy.AUTO).createdAt(at.minus(1, ChronoUnit.DAYS)).build());
        workflowService.transition(app, Application.Status.ASSIGNED, null);
        workflowService.transition(app, Application.Status.SCHEDULED, null);

        Inspection insp = Inspection.builder().clientUuid(UUID.randomUUID().toString()).assignment(asg)
                .officer(officer).startedAt(at).completedAt(at.plus(25, ChronoUnit.MINUTES))
                .gpsLat(gpsLat).gpsLng(gpsLng).syncedAt(at.plus(2, ChronoUnit.HOURS))
                .remarks(pass ? "All readings within limits. Seal applied." : "Readings drift at higher loads.")
                .build();
        boolean allWithin = true;
        for (double[] r : readings(i, pass)) {
            double perm = ErrorCalculator.permissibleError(i, r[0]);
            double err = ErrorCalculator.error(r[0], r[1]);
            boolean ok = ErrorCalculator.withinLimit(err, perm);
            allWithin &= ok;
            insp.getObservations().add(Observation.builder().inspection(insp).testLoad(r[0]).indicatedValue(r[1])
                    .error(err).permissibleError(perm).withinLimit(ok).build());
        }
        insp.setResult(allWithin ? Inspection.Result.PASS : Inspection.Result.FAIL);
        insp = inspectionRepository.save(insp);
        workflowService.transition(app, Application.Status.INSPECTED, officer);
        if (runFraudRules) {
            fraudService.evaluate(insp, List.of());
        }
        if (insp.getResult() == Inspection.Result.FAIL) {
            workflowService.transition(app, Application.Status.FAILED, officer);
            return null;
        }
        workflowService.transition(app, Application.Status.PASSED, officer);
        return certificateService.issue(app, insp, officer, at.plus(2, ChronoUnit.HOURS));
    }

    /** Test loads at roughly min, 25%, 50% and max capacity. Failing sets drift beyond 2x the limit. */
    private static List<double[]> readings(Instrument i, boolean pass) {
        if (i.getType().getErrorModel() == InstrumentType.ErrorModel.PERCENT) {
            return List.of(new double[]{5, 5.01}, new double[]{20, 20.04});
        }
        double max = i.getCapacityMax();
        double e = i.getEValue();
        double[] loads = {i.getCapacityMin() == null ? 20 * e : i.getCapacityMin(), max / 4, max / 2, max};
        return java.util.Arrays.stream(loads).mapToObj(load -> {
            double indicated = pass ? load + e * 0.4 : load + ErrorCalculator.permissibleError(i, load) * 2 + e;
            return new double[]{load, Math.round(indicated * 1_000_000.0) / 1_000_000.0};
        }).toList();
    }
}
