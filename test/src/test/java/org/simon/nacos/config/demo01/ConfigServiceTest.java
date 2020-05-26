package org.simon.nacos.config.demo01;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.NacosConfigService;
import com.alibaba.nacos.client.config.listener.impl.PropertiesListener;

import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

/**
 * @author Admin
 * @Copyright © 2020 tiger Inc. All rights reserved.
 * @create 2020-05-25 17:22
 */
public class ConfigServiceTest {

    private ConfigService configService;
    private Executor executor;

    @Before
    public void init() throws NacosException {
        StandardEnvironment environment = new StandardEnvironment();

        Properties props = buildNacosProperties(environment,
            "127.0.0.1:8848,192.168.1.1:9999",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null);

        //利用反射创建NacosConfigService对象
        configService = NacosFactory.createConfigService(props);
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("nacos-listener-%d").build();
        executor = Executors.newFixedThreadPool(5, threadFactory);
    }

    /**
     * 测试从Nacos拉取配置内容
     * @throws NacosException
     */
    @Test
    public void getConfigTest() throws Exception {
        String content = configService.getConfig("other", "DEFAULT_GROUP", 5000L);
        System.out.println("content:" + content);
    }

    /**
     * 测试Nacos配置变更监听
     * @throws NacosException
     * @throws InterruptedException
     */
    @Test
    public void addListenerTest() throws NacosException, InterruptedException {
        configService.addListener("other", "DEFAULT_GROUP", new PropertiesListener() {
            @Override
            public void innerReceive(Properties properties) {
                System.out.println(properties);
            }
        });


        configService.addListener("other", "DEFAULT_GROUP", new Listener() {
            @Override
            public Executor getExecutor() {
                return executor;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                System.out.println(Thread.currentThread().getName()+", 接收到配置变更："+configInfo);
            }
        });

        configService.addListener("boot-job", "DEFAULT_GROUP", new Listener() {
            @Override
            public Executor getExecutor() {
                return executor;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                System.out.println(Thread.currentThread().getName()+", 接收到配置变更："+configInfo);
            }
        });


        TimeUnit.SECONDS.sleep(1000);
    }


    /**
     *
     * @param environment
     * @param serverAddr
     * @param namespaceId
     * @param endpoint
     * @param secretKey
     * @param accessKey
     * @param ramRoleName
     * @param configLongPollTimeout
     * @param configRetryTimeout
     * @param maxRetry
     * @param enableRemoteSyncConfig 开启注册监听器预加载配置服务（除非特殊业务需求，否则不推荐打开该参数）
     * @param username
     * @param password
     * @return
     */
    public static Properties buildNacosProperties(Environment environment,
                                                  String serverAddr, String namespaceId, String endpoint, String secretKey,
                                                  String accessKey, String ramRoleName, String configLongPollTimeout,
                                                  String configRetryTimeout, String maxRetry, boolean enableRemoteSyncConfig,
                                                  String username, String password) {
        Properties properties = new Properties();
        if (StringUtils.isNotEmpty(serverAddr)) {
            properties.put(PropertyKeyConst.SERVER_ADDR,
                environment.resolvePlaceholders(serverAddr));
        }
        if (StringUtils.isNotEmpty(namespaceId)) {
            properties.put(PropertyKeyConst.NAMESPACE,
                environment.resolvePlaceholders(namespaceId));
        }
        if (StringUtils.isNotEmpty(endpoint)) {
            properties.put(PropertyKeyConst.ENDPOINT,
                environment.resolvePlaceholders(endpoint));
        }
        if (StringUtils.isNotEmpty(secretKey)) {
            properties.put(PropertyKeyConst.SECRET_KEY,
                environment.resolvePlaceholders(secretKey));
        }
        if (StringUtils.isNotEmpty(accessKey)) {
            properties.put(PropertyKeyConst.ACCESS_KEY,
                environment.resolvePlaceholders(accessKey));
        }
        if (StringUtils.isNoneEmpty(ramRoleName)) {
            properties.put(PropertyKeyConst.RAM_ROLE_NAME,
                environment.resolvePlaceholders(ramRoleName));
        }
        if (StringUtils.isNotEmpty(configLongPollTimeout)) {
            properties.put(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT,
                environment.resolvePlaceholders(configLongPollTimeout));
        }
        if (StringUtils.isNotEmpty(configRetryTimeout)) {
            properties.put(PropertyKeyConst.CONFIG_RETRY_TIME,
                environment.resolvePlaceholders(configRetryTimeout));
        }
        if (StringUtils.isNotEmpty(maxRetry)) {
            properties.put(PropertyKeyConst.MAX_RETRY,
                environment.resolvePlaceholders(maxRetry));
        }
        if (StringUtils.isNotBlank(username)) {
            properties.put(PropertyKeyConst.USERNAME,
                environment.resolvePlaceholders(username));
        }
        if (StringUtils.isNotBlank(password)) {
            properties.put(PropertyKeyConst.PASSWORD,
                environment.resolvePlaceholders(password));
        }
        properties.put(PropertyKeyConst.ENABLE_REMOTE_SYNC_CONFIG,
            String.valueOf(enableRemoteSyncConfig));
        return properties;
    }


    @Test
    public void t1(){
        System.out.println(Constants.WORD_SEPARATOR);
    }


}
