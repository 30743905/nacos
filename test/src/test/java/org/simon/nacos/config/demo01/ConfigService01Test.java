package org.simon.nacos.config.demo01;


import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;

import org.junit.Test;

import java.util.Properties;

/**
 * @author Admin
 * @Copyright © 2020 tiger Inc. All rights reserved.
 * @create 2020-05-25 17:22
 */
public class ConfigService01Test {


    /**
     * 测试从 Nacos Server 拉取配置内容
     * @throws NacosException
     */
    @Test
    public void getConfigTest() throws Exception {

        //1、配置server-addr
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:8848,192.168.1.1:9999");

        //2、创建ConfigService对象
        ConfigService configService = NacosFactory.createConfigService(properties);

        //3、通过ConfigService对Nacos Server上的配置进行操作，比如拉取配置、修改配置等
        String content = configService.getConfig("other", "DEFAULT_GROUP", 5000L);
        System.out.println("content:" + content);
        configService.publishConfig("other", "DEFAULT_GROUP", "===修改后内容===");
    }

}
