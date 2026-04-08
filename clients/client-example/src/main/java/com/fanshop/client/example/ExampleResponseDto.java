package com.fanshop.client.example;

import com.fanshop.client.example.model.ExampleClientResult;

record ExampleResponseDto(String exampleResponseValue) {
    ExampleClientResult toResult() {
        return new ExampleClientResult(exampleResponseValue);
    }
}
