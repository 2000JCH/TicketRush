package com.ticketrush.ticketrush.domain.account.dto;

import com.ticketrush.ticketrush.domain.account.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** role은 BUYER 또는 ORGANIZER만 허용된다(ADMIN은 셀프 가입 대상이 아님). */
public record SignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자를 넘을 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
        String password,

        @NotNull(message = "역할은 필수입니다.")
        Role role
) {
}
