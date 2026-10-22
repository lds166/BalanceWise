package com.balancewise.account.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ArchiveRequest {
    @NotNull
    private Boolean isArchived;
}
