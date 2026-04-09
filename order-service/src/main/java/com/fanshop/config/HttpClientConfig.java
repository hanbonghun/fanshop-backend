package com.fanshop.config;

import com.fanshop.client.ProductClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class HttpClientConfig {

    @Value("${product-service.url}")
    private String productServiceUrl;

    @Bean
    public ProductClient productClient() {
        RestClient restClient = RestClient.builder().baseUrl(productServiceUrl).build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
            .build();
        return factory.createClient(ProductClient.class);
    }

}
