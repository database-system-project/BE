package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.AppointmentTimePolicy;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    /** 운영 09:00~17:00, 점심 12:30~13:30, 30분 단위. 마지막 예약 시작은 16:00이다. */
    private static final LocalTime FIRST_SLOT = LocalTime.of(9, 0);
    private static final LocalTime LAST_SLOT = LocalTime.of(16, 0);
    private static final LocalTime LUNCH_START = LocalTime.of(12, 30);
    private static final LocalTime LUNCH_END = LocalTime.of(13, 30);

    /** 예약 저장(validateSlot)과 같은 기준: 의사·진료과·계정이 모두 활성이어야 한다. */
    private static final String ACTIVE_DOCTOR = """
            FROM doctor d
            JOIN department dep ON dep.department_id = d.department_id
            JOIN user_account a ON a.account_id = d.account_id
            WHERE d.doctor_active AND dep.department_active AND a.account_status = 'ACTIVE' AND a.role = 'DOCTOR'
            """;
    private final Database db;
    private final Clock clock;
    private final AppointmentTimePolicy timePolicy;

    public CatalogService(Database db, Clock clock, AppointmentTimePolicy timePolicy) {
        this.db = db;
        this.clock = clock;
        this.timePolicy = timePolicy;
    }

    /** 활성 진료과만 이름 오름차순(동률은 ID 순)으로 반환한다. */
    public Map<String, Object> departments(HospitalPrincipal user) {
        user.patient();
        List<Map<String, Object>> items = db.rows("""
                SELECT department_id, department_name FROM department WHERE department_active
                 ORDER BY department_name, department_id
                """, Map.of()).stream()
                .map(row -> Values.map("departmentId", Values.id(row, "department_id"), "name", row.get("department_name")))
                .toList();
        return Map.of("items", items);
    }

    /** 없거나 비활성인 진료과는 404다. 활성 의사·계정만 이름 오름차순(동률은 ID 순)으로 반환한다. */
    public Map<String, Object> doctors(HospitalPrincipal user, long departmentId) {
        user.patient();
        positive(departmentId);
        db.one("SELECT department_id FROM department WHERE department_id = :id AND department_active",
                Map.of("id", departmentId));
        List<Map<String, Object>> items = db.rows("SELECT d.doctor_id, d.doctor_name, d.department_id "
                + ACTIVE_DOCTOR + " AND d.department_id = :department ORDER BY d.doctor_name, d.doctor_id",
                Map.of("department", departmentId)).stream()
                .map(row -> Values.map("doctorId", Values.id(row, "doctor_id"), "name", row.get("doctor_name"),
                        "departmentId", Values.id(row, "department_id")))
                .toList();
        return Map.of("items", items);
    }

    /**
     * 기간 안의 휴진이 아닌 일정마다 예약 가능한 시작 시각을 계산한다.
     * 지난 시각, 점심시간, 점유된(RESERVED·CHECKED_IN) 시각은 제외하고 취소된 예약의 시각은 다시 연다.
     * 조회 결과는 선점을 보장하지 않으며, 예약 저장 때 다시 검증한다.
     */
    public Map<String, Object> availability(HospitalPrincipal user, long doctorId, Map<String, String> query) {
        user.require(Role.PATIENT);
        positive(doctorId);
        LocalDate from = Query.date(query, "from");
        LocalDate to = Query.date(query, "to");
        if (from == null || to == null) { throw ApiException.badRequest(); }
        Query.range(from, to);
        db.one("SELECT d.doctor_id " + ACTIVE_DOCTOR + " AND d.doctor_id = :id", Map.of("id", doctorId));

        List<Map<String, Object>> schedules = db.rows("""
                SELECT schedule_id, schedule_date FROM doctor_schedule
                 WHERE doctor_id = :doctor AND NOT is_closed AND schedule_date >= :from AND schedule_date <= :to
                 ORDER BY schedule_date, schedule_id
                """, Values.map("doctor", doctorId, "from", from, "to", to));
        Map<Long, Set<Instant>> occupied = occupied(schedules);
        Instant now = timePolicy.cutoff();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> schedule : schedules) {
            long scheduleId = Values.id(schedule, "schedule_id");
            LocalDate date = Values.date(schedule, "schedule_date");
            List<String> slots = new ArrayList<>();
            for (LocalTime time = FIRST_SLOT; !time.isAfter(LAST_SLOT); time = time.plusMinutes(30)) {
                if (!time.isBefore(LUNCH_START) && time.isBefore(LUNCH_END)) { continue; }
                var start = date.atTime(time).atZone(Values.SEOUL);
                if (start.toInstant().isBefore(now) || occupied.get(scheduleId).contains(start.toInstant())) { continue; }
                slots.add(start.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
            if (!slots.isEmpty()) {
                items.add(Values.map("date", date, "scheduleId", scheduleId, "slots", slots));
            }
        }
        return Values.map("doctorId", doctorId, "items", items);
    }

    private Map<Long, Set<Instant>> occupied(List<Map<String, Object>> schedules) {
        Map<Long, Set<Instant>> result = new HashMap<>();
        for (Map<String, Object> schedule : schedules) { result.put(Values.id(schedule, "schedule_id"), new HashSet<>()); }
        if (schedules.isEmpty()) { return result; }
        List<Long> ids = schedules.stream().map(row -> Values.id(row, "schedule_id")).toList();
        for (Map<String, Object> row : db.rows("""
                SELECT schedule_id, planned_reception_at FROM reservation
                 WHERE schedule_id IN (:ids) AND reservation_status IN ('RESERVED', 'CHECKED_IN')
                """, Map.of("ids", ids))) {
            result.get(Values.id(row, "schedule_id")).add(Values.instant(row, "planned_reception_at"));
        }
        return result;
    }

    private static void positive(long id) {
        if (id <= 0) { throw ApiException.badRequest(); }
    }
}
