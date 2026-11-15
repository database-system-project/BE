package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminConsultationFeeService {
    private final Database db;
    private final Clock clock;

    public AdminConsultationFeeService(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** 유형별로 effective_from이 지금 이하인 행 중 가장 최근 행을 돌려준다. 미래 행은 시작 전까지 대체하지 않는다. */
    @Transactional(readOnly = true)
    public Map<String, Object> current(HospitalPrincipal user) {
        user.require(Role.ADMIN);
        Instant now = clock.instant();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (AdminConsultationFeeController.FeeType type : AdminConsultationFeeController.FeeType.values()) {
            var fee = db.optional("""
                    SELECT fee_id, fee_type, fee_amount, effective_from FROM consultation_fee
                     WHERE fee_type = :type AND effective_from <= :now
                     ORDER BY effective_from DESC, fee_id DESC LIMIT 1
                    """, Values.map("type", type.name(), "now", now));
            if (fee.isPresent()) { items.add(view(fee.get())); } else { missing.add(type.name()); }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(409, "FEE_NOT_CONFIGURED", "해당 진료 유형에 적용할 진찰료가 설정되어 있지 않습니다.",
                    Values.map("feeTypes", missing));
        }
        return Map.of("items", items);
    }

    /** 수납과 같은 유형별 advisory lock(8001) 아래에서 새 행만 INSERT한다. 기존 행은 수정하지 않는다. */
    @Transactional
    public Map<String, Object> add(HospitalPrincipal user, AdminConsultationFeeController.FeeRequest request) {
        user.require(Role.ADMIN);
        int year = request.effectiveFrom().getYear();
        if (year < 1970 || year > 9999) { throw ApiException.badRequest(); }
        String type = request.feeType().name();
        // PostgreSQL TIMESTAMPTZ는 마이크로초 정밀도라 같은 기준으로 잘라서 중복을 비교한다.
        Instant effectiveFrom = request.effectiveFrom().toInstant().truncatedTo(ChronoUnit.MICROS);

        db.lockFee(type);
        if (db.count("SELECT count(*) FROM consultation_fee WHERE fee_type = :type AND effective_from = :at",
                Values.map("type", type, "at", effectiveFrom)) > 0) {
            throw ApiException.conflict("DUPLICATE_FEE_EFFECTIVE_FROM", "해당 진찰료 유형·적용 시작일의 요금이 이미 있습니다.");
        }
        long feeId = db.insert("""
                INSERT INTO consultation_fee(fee_type, fee_amount, effective_from, set_by_account_id)
                VALUES (:type, :amount, :at, :account) RETURNING fee_id
                """, Values.map("type", type, "amount", request.amount(), "at", effectiveFrom, "account", user.accountId()));
        return view(db.one("SELECT fee_id, fee_type, fee_amount, effective_from FROM consultation_fee WHERE fee_id = :id",
                Map.of("id", feeId)));
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Values.map("feeId", Values.id(row, "fee_id"), "feeType", row.get("fee_type"),
                "amount", row.get("fee_amount"), "currency", "KRW", "effectiveFrom", Values.time(row, "effective_from"));
    }
}
