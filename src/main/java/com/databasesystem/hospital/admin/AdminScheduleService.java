package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Dates;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminScheduleService {
    private static final String SELECT = """
            SELECT s.schedule_id, s.doctor_id, d.doctor_name, s.schedule_date, s.is_closed
              FROM doctor_schedule s JOIN doctor d ON d.doctor_id = s.doctor_id
            """;
    private final Database db;
    private final Clock clock;

    public AdminScheduleService(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** 의사 일정을 등록한다. 운영시간은 DB 기본값(09:00~17:00, 점심 12:30~13:30)이 고정으로 적용된다. */
    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, AdminScheduleController.CreateRequest request) {
        user.require(Role.ADMIN);
        // 같은 의사의 일정을 동시에 등록해도 중복 검사가 정확하도록 의사 행을 잠근다.
        db.lockDoctor(request.doctorId());
        if (db.count("SELECT count(*) FROM doctor_schedule WHERE doctor_id = :doctor AND schedule_date = :date",
                Values.map("doctor", request.doctorId(), "date", request.date())) > 0) {
            throw ApiException.conflict("DUPLICATE_SCHEDULE", "해당 의사의 같은 날짜 일정이 이미 있습니다.");
        }
        long id = db.insert("""
                INSERT INTO doctor_schedule(doctor_id, schedule_date, is_closed) VALUES (:doctor, :date, :closed)
                RETURNING schedule_id
                """, Values.map("doctor", request.doctorId(), "date", request.date(), "closed", request.closed()));
        return view(db.one(SELECT + " WHERE s.schedule_id = :id", Map.of("id", id)));
    }

    /** 기간을 모두 생략하면 오늘(한국 시간) 일정을 조회한다. 시작일만 주면 그 날짜 이후 전체다. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, Map<String, String> query) {
        user.require(Role.ADMIN);
        Long doctorId = Query.id(query, "doctorId");
        LocalDate from = Query.date(query, "from");
        LocalDate to = Query.date(query, "to");
        Query.range(from, to);
        if (from == null && to == null) {
            from = clock.instant().atZone(Values.SEOUL).toLocalDate();
            to = from;
        }
        StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (doctorId != null) { sql.append(" AND s.doctor_id = :doctor"); parameters.put("doctor", doctorId); }
        if (from != null) { sql.append(" AND s.schedule_date >= :from"); parameters.put("from", from); }
        if (to != null) { sql.append(" AND s.schedule_date <= :to"); parameters.put("to", to); }
        sql.append(" ORDER BY s.schedule_date, s.doctor_id, s.schedule_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream().map(this::view).toList();
        return Map.of("items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long scheduleId) {
        user.require(Role.ADMIN);
        positive(scheduleId);
        return view(db.one(SELECT + " WHERE s.schedule_id = :id", Map.of("id", scheduleId)));
    }

    /** 의사·날짜·휴진 여부를 보낸 것만 수정한다. 예약이 연결된 일정은 의사·날짜 변경과 휴진 전환을 막는다. */
    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long scheduleId, JsonNode body) {
        user.require(Role.ADMIN);
        positive(scheduleId);
        PatchBody patch = new PatchBody(body, Set.of("doctorId", "date", "closed"));
        Long requestedDoctor = patch.id("doctorId");
        String requestedDateText = patch.text("date");
        LocalDate requestedDate = requestedDateText == null ? null : Dates.parse(requestedDateText);
        Boolean requestedClosed = patch.bool("closed");

        // 예약 생성·변경과 같은 일정 행 잠금 규칙을 사용한다.
        Map<String, Object> current = db.one("""
                SELECT doctor_id, schedule_date, is_closed FROM doctor_schedule WHERE schedule_id = :id FOR UPDATE
                """, Map.of("id", scheduleId));
        long doctorId = requestedDoctor == null ? Values.id(current, "doctor_id") : requestedDoctor;
        LocalDate date = requestedDate == null ? Values.date(current, "schedule_date") : requestedDate;
        boolean wasClosed = Boolean.TRUE.equals(current.get("is_closed"));
        boolean closed = requestedClosed == null ? wasClosed : requestedClosed;

        boolean moved = doctorId != Values.id(current, "doctor_id") || !date.equals(Values.date(current, "schedule_date"));
        if (moved) {
            db.lockDoctor(doctorId); // 없는 의사면 404, 같은 의사·날짜 일정의 동시 변경도 직렬화한다.
        }
        boolean closing = closed && !wasClosed;
        if ((moved || closing)
                && db.count("SELECT count(*) FROM reservation WHERE schedule_id = :id", Map.of("id", scheduleId)) > 0) {
            throw ApiException.conflict("SCHEDULE_HAS_APPOINTMENTS", "예약이 연결된 일정은 해당 변경을 할 수 없습니다.");
        }
        if (moved && db.count("""
                SELECT count(*) FROM doctor_schedule
                 WHERE doctor_id = :doctor AND schedule_date = :date AND schedule_id <> :id
                """, Values.map("doctor", doctorId, "date", date, "id", scheduleId)) > 0) {
            throw ApiException.conflict("DUPLICATE_SCHEDULE", "변경한 의사·날짜의 일정이 이미 있습니다.");
        }
        db.update("""
                UPDATE doctor_schedule SET doctor_id = :doctor, schedule_date = :date, is_closed = :closed
                 WHERE schedule_id = :id
                """, Values.map("doctor", doctorId, "date", date, "closed", closed, "id", scheduleId));
        return view(db.one(SELECT + " WHERE s.schedule_id = :id", Map.of("id", scheduleId)));
    }

    /** 취소된 예약까지 포함해 연결 예약이 하나도 없는 일정만 삭제한다. */
    @Transactional
    public void delete(HospitalPrincipal user, long scheduleId) {
        user.require(Role.ADMIN);
        positive(scheduleId);
        db.one("SELECT schedule_id FROM doctor_schedule WHERE schedule_id = :id FOR UPDATE", Map.of("id", scheduleId));
        if (db.count("SELECT count(*) FROM reservation WHERE schedule_id = :id", Map.of("id", scheduleId)) > 0) {
            throw ApiException.conflict("SCHEDULE_HAS_APPOINTMENTS", "예약이 연결된 일정은 삭제할 수 없습니다.");
        }
        db.update("DELETE FROM doctor_schedule WHERE schedule_id = :id", Map.of("id", scheduleId));
    }

    private static void positive(long id) {
        if (id <= 0) { throw ApiException.badRequest(); }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Values.map("scheduleId", Values.id(row, "schedule_id"), "doctorId", Values.id(row, "doctor_id"),
                "doctorName", row.get("doctor_name"), "date", Values.date(row, "schedule_date"),
                "receptionStart", "09:00", "closingTime", "17:00", "lunchStart", "12:30", "lunchEnd", "13:30",
                "closed", Boolean.TRUE.equals(row.get("is_closed")));
    }
}
