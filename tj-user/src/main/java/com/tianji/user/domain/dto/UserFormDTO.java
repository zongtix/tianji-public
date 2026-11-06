package com.tianji.user.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.api.dto.user.UserDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "修改用户信息的表单，带有密码")
public class UserFormDTO extends UserDTO {
    @Schema(description = "原始密码", example = "123321")
    @NotNull
    private String oldPassword;
    @Schema(description = "新密码", example = "123321")
    @NotNull
    private String password;
}
