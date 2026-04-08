package com.fanshop.api.v1;

import com.fanshop.example.ExampleService;
import com.fanshop.support.response.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
public class ExampleController {

    private final ExampleService exampleService;

    public ExampleController(ExampleService exampleService) {
        this.exampleService = exampleService;
    }

    @GetMapping("/get/{exampleValue}")
    public ApiResponse<ExampleResponseDto> exampleGet(@PathVariable String exampleValue,
            @RequestParam String exampleParam) {
        String result = exampleService.processExample(exampleValue);
        return ApiResponse.success(
                new ExampleResponseDto(result, LocalDate.now(), LocalDateTime.now(), ExampleItemResponseDto.build()));
    }

    @PostMapping("/post")
    public ApiResponse<ExampleResponseDto> examplePost(@RequestBody ExampleRequestDto request) {
        String result = exampleService.processExample(request.data());
        return ApiResponse.success(
                new ExampleResponseDto(result, LocalDate.now(), LocalDateTime.now(), ExampleItemResponseDto.build()));
    }

}
