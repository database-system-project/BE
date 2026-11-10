package com.databasesystem.hospital.support;

import com.databasesystem.hospital.auth.AccountRepository;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.auth.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(IntegrationSupport.FixedTime.class)
public abstract class IntegrationSupport {
    public static final String PASSWORD = "TestPass123!";
    public static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    public static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected PasswordEncoder passwords;
    @Autowired protected AccountRepository accounts;
    @Autowired protected TokenService tokens;

    @BeforeEach
    void clearData() {
        jdbc.execute("TRUNCATE user_account, department, medicine RESTART IDENTITY CASCADE");
    }

    protected Actor patient() { return patient("테스트환자", LocalDate.of(2018, 3, 22), "01012345678"); }

    protected Actor patient(String name, LocalDate birthDate, String phone) {
        String login = "patient_" + UUID.randomUUID().toString().substring(0, 12);
        long account = account(login, Role.PATIENT);
        long patient = jdbc.queryForObject("""
                INSERT INTO patient(account_id, patient_name, birth_date, gender, phone, privacy_agreed_at)
                VALUES (?, ?, ?, 'M', ?, ?) RETURNING patient_id
                """, Long.class, account, name, birthDate, phone, time(NOW));
        return actor(account, patient, null, null, login);
    }

    protected Actor staff(Role role) {
        String login = role.name() + "_" + UUID.randomUUID().toString().substring(0, 12);
        long account = account(login, role);
        long staff = jdbc.queryForObject("""
                INSERT INTO staff(account_id, staff_name) VALUES (?, ?) RETURNING staff_id
                """, Long.class, account, "테스트직원");
        return actor(account, null, null, staff, login);
    }

    protected Doctor doctor() {
        Actor employee = staff(Role.DOCTOR);
        long department = jdbc.queryForObject("INSERT INTO department(department_name) VALUES (?) RETURNING department_id",
                Long.class, "내과" + UUID.randomUUID().toString().substring(0, 8));
        long doctor = jdbc.queryForObject("""
                INSERT INTO doctor(account_id, department_id, doctor_name) VALUES (?, ?, '테스트의사') RETURNING doctor_id
                """, Long.class, employee.accountId(), department);
        long schedule = jdbc.queryForObject("""
                INSERT INTO doctor_schedule(doctor_id, schedule_date) VALUES (?, ?) RETURNING schedule_id
                """, Long.class, doctor, TODAY);
        Actor actor = actor(employee.accountId(), null, doctor, employee.staffId(), employee.loginId());
        return new Doctor(doctor, schedule, department, actor);
    }

    protected long reservation(Actor patient, Doctor doctor, Instant startsAt) {
        return jdbc.queryForObject("""
                INSERT INTO reservation(patient_id, schedule_id, planned_reception_at)
                VALUES (?, ?, ?) RETURNING reservation_id
                """, Long.class, patient.patientId(), doctor.scheduleId(), time(startsAt));
    }

    protected long visit(Actor patient, Doctor doctor, String status) {
        Instant slot = NOW.plusSeconds(3600 + jdbc.queryForObject("SELECT count(*) FROM reservation", Long.class) * 1800);
        long reservation = reservation(patient, doctor, slot);
        jdbc.update("UPDATE reservation SET reservation_status = 'CHECKED_IN' WHERE reservation_id = ?", reservation);
        Actor receptionist = staff(Role.RECEPTION);
        return jdbc.queryForObject("""
                INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_staff_id, visit_status, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING visit_id
                """, Long.class, reservation, time(NOW.minusSeconds(180)), receptionist.staffId(), status,
                "WAITING".equals(status) ? null : time(NOW.minusSeconds(120)),
                "COMPLETED".equals(status) ? time(NOW.minusSeconds(60)) : null);
    }

    protected long record(long visitId) {
        return jdbc.queryForObject("""
                INSERT INTO medical_record(visit_id, symptoms, diagnosis, treatment_note, written_at, record_updated_at)
                VALUES (?, '시연 증상', '시연 진단', '시연 진료', ?, ?) RETURNING record_id
                """, Long.class, visitId, time(NOW), time(NOW));
    }

    protected long medication(String name) {
        return jdbc.queryForObject("""
                INSERT INTO medicine(item_seq, product_name, company_name) VALUES (?, ?, '시연제약') RETURNING medicine_id
                """, Long.class, UUID.randomUUID().toString().replace("-", "").substring(0, 20), name);
    }

    protected long fee(String type, int amount, Instant effective, Actor admin) {
        return jdbc.queryForObject("""
                INSERT INTO consultation_fee(fee_type, fee_amount, effective_from, set_by_staff_id)
                VALUES (?, ?, ?, ?) RETURNING fee_id
                """, Long.class, type, amount, time(effective), admin.staffId());
    }

    protected ResultActions getAs(String path, Actor actor) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + actor.token()));
    }

    protected ResultActions postAs(String path, Actor actor, Object body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + actor.token());
        if (body != null) { request.contentType("application/json").content(mapper.writeValueAsString(body)); }
        return mvc.perform(request);
    }

    protected OffsetDateTime time(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }

    private long account(String login, Role role) {
        return jdbc.queryForObject("""
                INSERT INTO user_account(login_id, password_hash, role) VALUES (?, ?, ?) RETURNING account_id
                """, Long.class, login, passwords.encode(PASSWORD), role.name());
    }

    private Actor actor(long account, Long patient, Long doctor, Long staff, String login) {
        String token = tokens.issue(accounts.active(account).orElseThrow());
        return new Actor(account, patient, doctor, staff, login, token);
    }

    public record Actor(long accountId, Long patientId, Long doctorId, Long staffId, String loginId, String token) { }
    public record Doctor(long doctorId, long scheduleId, long departmentId, Actor actor) { }

    @TestConfiguration(proxyBeanMethods = false)
    public static class FixedTime {
        @Bean
        @Primary
        Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
