package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.ClinicalRepository;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ReservationService {
    private final Database db;
    private final Clock clock;

    public ReservationService(Database db, Clock clock) { this.db = db; this.clock = clock; }

    @Transactional
    public Map<String, Object> create(HospitalPrincipal principal, ReservationController.Slot request) {
        long patient = principal.patient();
        lockSchedule(request.scheduleId());
        validateSlot(request);
        long id = insert(patient, request, null);
        return detail(principal, id);
    }

    public Map<String, Object> detail(HospitalPrincipal principal, long id) {
        return view(owned(principal.patient(), id));
    }

    public Map<String, Object> list(HospitalPrincipal principal, Map<String, String> query) {
        long patient = principal.patient();
        String period = Query.choice(query, "period", "UPCOMING", "PAST");
        String status = Query.choice(query, "status", "RESERVED", "CHECKED_IN", "CANCELLED");
        LocalDate from = Query.date(query, "from");
        LocalDate to = Query.date(query, "to");
        Query.range(from, to);
        StringBuilder sql = new StringBuilder(ClinicalRepository.RESERVATIONS + " WHERE r.patient_id = :patient");
        Map<String, Object> parameters = Values.map("patient", patient, "now", clock.instant());
        if (period != null) {
            sql.append("UPCOMING".equals(period) ? " AND (r.reservation_status = 'RESERVED' AND r.planned_reception_at >= :now)"
                    : " AND NOT (r.reservation_status = 'RESERVED' AND r.planned_reception_at >= :now)");
        }
        if (status != null) { sql.append(" AND r.reservation_status = :status"); parameters.put("status", status); }
        if (from != null) {
            sql.append(" AND r.planned_reception_at >= :from");
            parameters.put("from", from.atStartOfDay(Values.SEOUL).toInstant());
        }
        if (to != null) {
            sql.append(" AND r.planned_reception_at < :to");
            parameters.put("to", to.plusDays(1).atStartOfDay(Values.SEOUL).toInstant());
        }
        return Map.of("items", db.rows(sql + " ORDER BY r.planned_reception_at DESC, r.reservation_id DESC", parameters)
                .stream().map(this::view).toList());
    }

    @Transactional
    public Map<String, Object> reschedule(HospitalPrincipal principal, long id, ReservationController.Slot request) {
        long patient = principal.patient();
        var previous = owned(patient, id);
        List.of(Values.id(previous, "schedule_id"), request.scheduleId()).stream().distinct().sorted().forEach(this::lockSchedule);
        lockReservation(id);
        previous = owned(patient, id);
        modifiable(previous);
        if (Values.id(previous, "schedule_id") == request.scheduleId()
                && Values.instant(previous, "planned_reception_at").equals(request.startsAt().toInstant())) {
            throw ApiException.badRequest();
        }
        validateSlot(request);
        db.update("""
                UPDATE reservation SET reservation_status = 'CANCELLED', reservation_cancelled_at = :at
                 WHERE reservation_id = :id
                """, Values.map("id", id, "at", clock.instant()));
        long created = insert(patient, request, id);
        return Values.map("previousAppointmentId", id, "newAppointment", detail(principal, created));
    }

    @Transactional
    public Map<String, Object> cancel(HospitalPrincipal principal, long id) {
        long patient = principal.patient();
        var row = owned(patient, id);
        lockSchedule(Values.id(row, "schedule_id"));
        lockReservation(id);
        row = owned(patient, id);
        modifiable(row);
        Instant at = clock.instant();
        db.update("""
                UPDATE reservation SET reservation_status = 'CANCELLED', reservation_cancelled_at = :at
                 WHERE reservation_id = :id
                """, Values.map("id", id, "at", at));
        return Values.map("appointmentId", id, "status", "CANCELLED", "cancelledAt", at.atZone(Values.SEOUL).toOffsetDateTime());
    }

    private Map<String, Object> owned(long patient, long id) {
        return db.one(ClinicalRepository.RESERVATIONS + " WHERE r.reservation_id = :id AND r.patient_id = :patient",
                Map.of("id", id, "patient", patient));
    }

    private void modifiable(Map<String, Object> row) {
        if (!"RESERVED".equals(row.get("reservation_status"))
                || Values.instant(row, "planned_reception_at").isBefore(clock.instant())) {
            throw ApiException.conflict("INVALID_APPOINTMENT_STATE", "현재 예약은 변경하거나 취소할 수 없습니다.");
        }
    }

    private void lockSchedule(long id) {
        db.one("SELECT schedule_id FROM doctor_schedule WHERE schedule_id = :id FOR UPDATE", Map.of("id", id));
    }

    private void lockReservation(long id) {
        db.one("SELECT reservation_id FROM reservation WHERE reservation_id = :id FOR UPDATE", Map.of("id", id));
    }

    private void validateSlot(ReservationController.Slot request) {
        var schedule = db.one("""
                SELECT ds.*, d.doctor_active, dep.department_active, a.account_status, s.staff_active
                  FROM doctor_schedule ds JOIN doctor d ON d.doctor_id = ds.doctor_id
                  JOIN department dep ON dep.department_id = d.department_id
                  JOIN user_account a ON a.account_id = d.account_id JOIN staff s ON s.account_id = a.account_id
                 WHERE ds.schedule_id = :id
                """, Map.of("id", request.scheduleId()));
        var local = request.startsAt().atZoneSameInstant(Values.SEOUL);
        LocalTime time = local.toLocalTime();
        boolean enabled = Boolean.FALSE.equals(schedule.get("is_closed")) && Boolean.TRUE.equals(schedule.get("doctor_active"))
                && Boolean.TRUE.equals(schedule.get("department_active")) && Boolean.TRUE.equals(schedule.get("staff_active"))
                && "ACTIVE".equals(schedule.get("account_status"));
        boolean validTime = !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(16, 0))
                && (time.isBefore(LocalTime.of(12, 30)) || !time.isBefore(LocalTime.of(13, 30)))
                && time.getSecond() == 0 && time.getNano() == 0 && time.getMinute() % 30 == 0;
        if (!enabled || !validTime || !Values.date(schedule, "schedule_date").equals(local.toLocalDate())
                || request.startsAt().toInstant().isBefore(clock.instant())) {
            throw ApiException.conflict("SLOT_UNAVAILABLE", "선택한 시간은 예약할 수 없습니다.");
        }
    }

    private long insert(long patient, ReservationController.Slot request, Long previous) {
        return db.insert("""
                INSERT INTO reservation(patient_id, schedule_id, previous_reservation_id, planned_reception_at, reserved_at)
                VALUES (:patient, :schedule, :previous, :at, :now) RETURNING reservation_id
                """, Values.map("patient", patient, "schedule", request.scheduleId(), "previous", previous,
                "at", request.startsAt().toInstant(), "now", clock.instant()));
    }

    private Map<String, Object> view(Map<String, Object> row) {
        long id = Values.id(row, "reservation_id");
        boolean change = "RESERVED".equals(row.get("reservation_status"))
                && !Values.instant(row, "planned_reception_at").isBefore(clock.instant());
        return Values.map("appointmentId", id, "appointmentCode", "R%06d".formatted(id),
                "patientId", Values.id(row, "patient_id"), "departmentId", Values.id(row, "department_id"),
                "departmentName", row.get("department_name"), "doctorId", Values.id(row, "doctor_id"),
                "doctorName", row.get("doctor_name"), "scheduleId", Values.id(row, "schedule_id"),
                "startsAt", Values.time(row, "planned_reception_at"), "status", row.get("reservation_status"),
                "canReschedule", change, "canCancel", change);
    }
}
