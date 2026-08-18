package com.ticketrush.ticketrush.domain.account.repository;

import com.ticketrush.ticketrush.domain.account.entity.Account;
import com.ticketrush.ticketrush.domain.account.entity.AccountStatus;
import com.ticketrush.ticketrush.domain.account.entity.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 승인 대기 목록. 먼저 가입한 사람이 먼저 보이도록 정렬한다. */
    List<Account> findAllByRoleAndStatusOrderByCreatedAtAsc(Role role, AccountStatus status);
}
