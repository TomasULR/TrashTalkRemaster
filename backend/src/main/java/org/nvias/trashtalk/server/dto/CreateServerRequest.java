package org.nvias.trashtalk.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServerRequest(
        @NotBlank @Size(min = 1, max = 100) String name
) {}
