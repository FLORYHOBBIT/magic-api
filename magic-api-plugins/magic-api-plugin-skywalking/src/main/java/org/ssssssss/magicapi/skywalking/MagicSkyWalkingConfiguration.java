package org.ssssssss.magicapi.skywalking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SkyWalking 集成自动配置
 *
 * @author magic-api
 */
@Configuration
@ConditionalOnClass(name = "org.apache.skywalking.apm.toolkit.trace.Tracer")
public class MagicSkyWalkingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SkyWalkingInterceptor skyWalkingInterceptor() {
        return new SkyWalkingInterceptor();
    }
}
