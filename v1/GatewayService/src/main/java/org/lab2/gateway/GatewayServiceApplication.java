package org.lab2.gateway;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.security.Security;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableRabbit
public class GatewayServiceApplication {

    public static void main(String[] args) {
        // Отключаем кэширование DNS на уровне JVM: после перезапуска контейнера downstream-сервис
        // должен резолвиться заново, а не отдавать протухший/негативно закэшированный адрес.
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");

        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
