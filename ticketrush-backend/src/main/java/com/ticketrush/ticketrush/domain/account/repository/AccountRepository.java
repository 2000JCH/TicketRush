package com.ticketrush.ticketrush.domain.account.repository;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 승인 대기 목록. 먼저 가입한 사람이 먼저 보이도록 정렬한다. */
    List<Account> findAllByRoleAndStatusOrderByCreatedAtAsc(Role role, AccountStatus status);

    /**
     * 관리자 회원 목록 — role/status/email 부분일치로 필터. 계정 수가 많아 페이지네이션 필수
     * (정렬은 Pageable로 전달, 보통 id 내림차순 = 최근 가입 순).
     */
    @Query(value = "SELECT a FROM Account a WHERE "
            + "(:role IS NULL OR a.role = :role) AND "
            + "(:status IS NULL OR a.status = :status) AND "
            + "(:email IS NULL OR LOWER(a.email) LIKE LOWER(CONCAT('%', :email, '%')))",
            countQuery = "SELECT COUNT(a) FROM Account a WHERE "
            + "(:role IS NULL OR a.role = :role) AND "
            + "(:status IS NULL OR a.status = :status) AND "
            + "(:email IS NULL OR LOWER(a.email) LIKE LOWER(CONCAT('%', :email, '%')))")
    Page<Account> search(
            @Param("role") Role role,
            @Param("status") AccountStatus status,
            @Param("email") String email,
            Pageable pageable);
}
