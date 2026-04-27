package org.nvias.trashtalk.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 32)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Pouze písmena, číslice, _, ., -")
        String username,

        @NotBlank @Email @Size(max = 254)
        String email,

        @NotBlank @Size(min = 8, max = 128)
        String password
) {}
