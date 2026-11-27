package org.lab2.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.lab2.enums.PrivilegeStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrivilegeShortInfo(
        Integer balance,
        PrivilegeStatus status
) {
}
