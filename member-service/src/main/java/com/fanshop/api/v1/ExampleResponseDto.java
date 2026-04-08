package com.fanshop.api.v1;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ExampleResponseDto(String result, LocalDate date, LocalDateTime datetime,
        List<ExampleItemResponseDto> items) {

}
