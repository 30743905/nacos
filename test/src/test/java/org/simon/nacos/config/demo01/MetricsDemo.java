package org.simon.nacos.config.demo01;

import org.junit.Test;

import java.util.stream.LongStream;

import io.prometheus.client.Counter;

/**
 * @author Administrator
 * @Copyright © 2020 tiger Inc. All rights reserved.
 * @create 2020-05-26 0:06
 */
public class MetricsDemo {

    private Counter requestCounter = Counter.build()
        .name("io_namespace_http_requests_total").labelNames("path", "method")
        .help("Total requests.").register();


    @Test
    public void test01(){

        String[] urls = new String[]{"url1", "url2", "url3"};
        String[] methods = new String[]{"m1", "m2"};
        //String[] methods = new String[]{"m1", "m2"};

        LongStream.rangeClosed(1, 10).forEach(x -> {
            requestCounter.labels(urls[(int) (x%urls.length)], methods[(int) (x%methods.length)]).inc();
        });

        System.out.println(requestCounter);

    }

}
