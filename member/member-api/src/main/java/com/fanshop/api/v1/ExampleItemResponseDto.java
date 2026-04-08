package com.fanshop.api.v1;

import java.util.List;

public record ExampleItemResponseDto(String key) {

    public static List<ExampleItemResponseDto> build() {
        return List.of(new ExampleItemResponseDto("1"), new ExampleItemResponseDto("2"));
    }

}
