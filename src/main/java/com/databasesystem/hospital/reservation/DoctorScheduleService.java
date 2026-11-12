package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class DoctorScheduleService {
    private final Database db;
    private final Clock clock;

    public DoctorScheduleService(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * 로그인한 의사 본인의 일정만 조회한다. 기간을 모두 생략하면 오늘(한국 시간),
     * 시작일만 주면 그 날짜 이후 전체, 종료일만 주면 그 날짜까지 전체다.
     */
    public Map<String, Object> list(HospitalPrincipal user, Map<String, String> query) {
        long doctorId = user.doctor();
        LocalDate from = Query.date(query, "from");
        LocalDate to = Query.date(query, "to");
        Query.range(from, to);
        if (from == null && to == null) {
            from = clock.instant().atZone(Values.SEOUL).toLocalDate();
            to = from;
        }
        StringBuilder sql = new StringBuilder("""
                SELECT s.schedule_id, s.doctor_id, d.doctor_name, s.schedule_date, s.is_closed
                  FROM doctor_schedule s JOIN doctor d ON d.doctor_id = s.doctor_id
                 WHERE s.doctor_id = :doctor
                """);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("doctor", doctorId);
        if (from != null) { sql.append(" AND s.schedule_date >= :from"); parameters.put("from", from); }
        if (to != null) { sql.append(" AND s.schedule_date <= :to"); parameters.put("to", to); }
        sql.append(" ORDER BY s.schedule_date, s.schedule_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream().map(this::view).toList();
        return Map.of("items", items);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Values.map("scheduleId", Values.id(row, "schedule_id"), "doctorId", Values.id(row, "doctor_id"),
                "doctorName", row.get("doctor_name"), "date", Values.date(row, "schedule_date"),
                "receptionStart", "09:00", "closingTime", "17:00", "lunchStart", "12:30", "lunchEnd", "13:30",
                "closed", Boolean.TRUE.equals(row.get("is_closed")));
    }
}
