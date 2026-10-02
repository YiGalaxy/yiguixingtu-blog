package com.yigalaxy.yiguixingtu;

import com.yigalaxy.yiguixingtu.auth.dto.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterRequestValidationTest {

    @Test
    @DisplayName("注册用户名和昵称长度与数据库 varchar(64) 一致")
    void usernameAndNicknameMustFitDatabaseColumns() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            RegisterRequest request = new RegisterRequest();

            request.setUsername("u".repeat(64));
            request.setNickname("n".repeat(64));
            assertTrue(validator.validateProperty(request, "username").isEmpty());
            assertTrue(validator.validateProperty(request, "nickname").isEmpty());

            request.setUsername("u".repeat(65));
            request.setNickname("n".repeat(65));
            assertEquals(1, validator.validateProperty(request, "username").size());
            assertEquals(1, validator.validateProperty(request, "nickname").size());
        }
    }
}
