package com.balancewise.importbill.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI 专用的 RestClient.Builder：预置连接/读取超时。
 * 独立成类而非在 AiClassifier 构造器内 requestFactory(...)——后者会覆盖
 * 测试里 MockRestServiceServer 绑定的 mock 工厂。
 */
@Configuration
public class AiHttpConfig {

    @Bean
    public RestClient.Builder aiRestClientBuilder(AiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        return RestClient.builder().requestFactory(factory);
    }
}
