/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.client.config.impl;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.config.ConfigType;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.common.GroupKey;
import com.alibaba.nacos.client.config.filter.impl.ConfigFilterChainManager;
import com.alibaba.nacos.client.config.http.HttpAgent;
import com.alibaba.nacos.client.config.impl.HttpSimpleClient.HttpResult;
import com.alibaba.nacos.client.config.utils.ContentUtils;
import com.alibaba.nacos.client.config.utils.MD5;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.utils.LogUtils;
import com.alibaba.nacos.client.utils.ParamUtil;
import com.alibaba.nacos.client.utils.TenantUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.alibaba.nacos.api.common.Constants.LINE_SEPARATOR;
import static com.alibaba.nacos.api.common.Constants.WORD_SEPARATOR;
import static com.alibaba.nacos.api.common.Constants.CONFIG_TYPE;

/**
 * Longpolling
 *
 * @author Nacos
 */
public class ClientWorker {

    private static final Logger LOGGER = LogUtils.logger(ClientWorker.class);

    public void addListeners(String dataId, String group, List<? extends Listener> listeners) {
        group = null2defaultGroup(group);
        CacheData cache = addCacheDataIfAbsent(dataId, group);
        for (Listener listener : listeners) {
            cache.addListener(listener);
        }
    }

    public void removeListener(String dataId, String group, Listener listener) {
        group = null2defaultGroup(group);
        CacheData cache = getCache(dataId, group);
        if (null != cache) {
            cache.removeListener(listener);
            if (cache.getListeners().isEmpty()) {
                removeCache(dataId, group);
            }
        }
    }

    public void addTenantListeners(String dataId, String group, List<? extends Listener> listeners) throws NacosException {
        group = null2defaultGroup(group);
        String tenant = agent.getTenant();
        CacheData cache = addCacheDataIfAbsent(dataId, group, tenant);
        for (Listener listener : listeners) {
            cache.addListener(listener);
        }
    }

    public void addTenantListenersWithContent(String dataId, String group, String content, List<? extends Listener> listeners) throws NacosException {
        group = null2defaultGroup(group);
        String tenant = agent.getTenant();
        CacheData cache = addCacheDataIfAbsent(dataId, group, tenant);
        cache.setContent(content);
        for (Listener listener : listeners) {
            cache.addListener(listener);
        }
    }

    public void removeTenantListener(String dataId, String group, Listener listener) {
        group = null2defaultGroup(group);
        String tenant = agent.getTenant();
        CacheData cache = getCache(dataId, group, tenant);
        if (null != cache) {
            cache.removeListener(listener);
            if (cache.getListeners().isEmpty()) {
                removeCache(dataId, group, tenant);
            }
        }
    }

    void removeCache(String dataId, String group) {
        String groupKey = GroupKey.getKey(dataId, group);
        synchronized (cacheMap) {
            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.remove(groupKey);
            cacheMap.set(copy);
        }
        LOGGER.info("[{}] [unsubscribe] {}", agent.getName(), groupKey);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());
    }

    void removeCache(String dataId, String group, String tenant) {
        String groupKey = GroupKey.getKeyTenant(dataId, group, tenant);
        synchronized (cacheMap) {
            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.remove(groupKey);
            cacheMap.set(copy);
        }
        LOGGER.info("[{}] [unsubscribe] {}", agent.getName(), groupKey);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());
    }

    public CacheData addCacheDataIfAbsent(String dataId, String group) {
        CacheData cache = getCache(dataId, group);
        if (null != cache) {
            return cache;
        }

        String key = GroupKey.getKey(dataId, group);
        cache = new CacheData(configFilterChainManager, agent.getName(), dataId, group);

        synchronized (cacheMap) {
            CacheData cacheFromMap = getCache(dataId, group);
            // multiple listeners on the same dataid+group and race condition,so double check again
            //other listener thread beat me to set to cacheMap
            if (null != cacheFromMap) {
                cache = cacheFromMap;
                //reset so that server not hang this check
                cache.setInitializing(true);
            } else {
                int taskId = cacheMap.get().size() / (int) ParamUtil.getPerTaskConfigSize();
                cache.setTaskId(taskId);
            }

            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.put(key, cache);
            cacheMap.set(copy);
        }

        LOGGER.info("[{}] [subscribe] {}", agent.getName(), key);

        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());

        return cache;
    }

    /**
     * 从cacheMap缓存中获取CacheData，缓存不存在时new创建并放入到cacheMap缓存中
     * @param dataId
     * @param group
     * @param tenant
     * @return
     * @throws NacosException
     */
    public CacheData addCacheDataIfAbsent(String dataId, String group, String tenant) throws NacosException {
        CacheData cache = getCache(dataId, group, tenant);
        if (null != cache) {
            return cache;
        }
        String key = GroupKey.getKeyTenant(dataId, group, tenant);
        synchronized (cacheMap) {
            CacheData cacheFromMap = getCache(dataId, group, tenant);
            // multiple listeners on the same dataid+group and race condition,so
            // double check again
            // other listener thread beat me to set to cacheMap
            if (null != cacheFromMap) {
                cache = cacheFromMap;
                // reset so that server not hang this check
                cache.setInitializing(true);
            } else {
                cache = new CacheData(configFilterChainManager, agent.getName(), dataId, group, tenant);
                // fix issue # 1317
                if (enableRemoteSyncConfig) {
                    String[] ct = getServerConfig(dataId, group, tenant, 3000L);
                    cache.setContent(ct[0]);
                }
            }

            Map<String, CacheData> copy = new HashMap<String, CacheData>(cacheMap.get());
            copy.put(key, cache);
            cacheMap.set(copy);
        }
        LOGGER.info("[{}] [subscribe] {}", agent.getName(), key);

        //指标统计
        MetricsMonitor.getListenConfigCountMonitor().set(cacheMap.get().size());

        return cache;
    }

    public CacheData getCache(String dataId, String group) {
        return getCache(dataId, group, TenantUtil.getUserTenantForAcm());
    }

    public CacheData getCache(String dataId, String group, String tenant) {
        if (null == dataId || null == group) {
            throw new IllegalArgumentException();
        }
        return cacheMap.get().get(GroupKey.getKeyTenant(dataId, group, tenant));
    }

    public String[] getServerConfig(String dataId, String group, String tenant, long readTimeout)
        throws NacosException {
        String[] ct = new String[2];
        if (StringUtils.isBlank(group)) {
            group = Constants.DEFAULT_GROUP;
        }

        HttpResult result = null;
        try {
            List<String> params = null;
            if (StringUtils.isBlank(tenant)) {
                params = new ArrayList<String>(Arrays.asList("dataId", dataId, "group", group));
            } else {
                params = new ArrayList<String>(Arrays.asList("dataId", dataId, "group", group, "tenant", tenant));
            }
            //利用ServerHttpAgent向nacos server发送get请求，path=/v1/cs/configs，params=dataId+group+tenant(namespace)
            result = agent.httpGet(Constants.CONFIG_CONTROLLER_PATH, null, params, agent.getEncode(), readTimeout);
        } catch (IOException e) {
            String message = String.format(
                "[%s] [sub-server] get server config exception, dataId=%s, group=%s, tenant=%s", agent.getName(),
                dataId, group, tenant);
            LOGGER.error(message, e);
            throw new NacosException(NacosException.SERVER_ERROR, e);
        }

        switch (result.code) {
            case HttpURLConnection.HTTP_OK:
                //更新本地对应的snapshot配置文件
                LocalConfigInfoProcessor.saveSnapshot(agent.getName(), dataId, group, tenant, result.content);
                ct[0] = result.content;
                //设置配置文件类型，如：properties、yaml等
                if (result.headers.containsKey(CONFIG_TYPE)) {
                    ct[1] = result.headers.get(CONFIG_TYPE).get(0);
                } else {
                    ct[1] = ConfigType.TEXT.getType();
                }
                return ct;
            case HttpURLConnection.HTTP_NOT_FOUND:
                LocalConfigInfoProcessor.saveSnapshot(agent.getName(), dataId, group, tenant, null);
                return ct;
            case HttpURLConnection.HTTP_CONFLICT: {
                LOGGER.error(
                    "[{}] [sub-server-error] get server config being modified concurrently, dataId={}, group={}, "
                        + "tenant={}", agent.getName(), dataId, group, tenant);
                throw new NacosException(NacosException.CONFLICT,
                    "data being modified, dataId=" + dataId + ",group=" + group + ",tenant=" + tenant);
            }
            case HttpURLConnection.HTTP_FORBIDDEN: {
                LOGGER.error("[{}] [sub-server-error] no right, dataId={}, group={}, tenant={}", agent.getName(), dataId,
                    group, tenant);
                throw new NacosException(result.code, result.content);
            }
            default: {
                LOGGER.error("[{}] [sub-server-error]  dataId={}, group={}, tenant={}, code={}", agent.getName(), dataId,
                    group, tenant, result.code);
                throw new NacosException(result.code,
                    "http error, code=" + result.code + ",dataId=" + dataId + ",group=" + group + ",tenant=" + tenant);
            }
        }
    }

    private void checkLocalConfig(CacheData cacheData) {
        final String dataId = cacheData.dataId;
        final String group = cacheData.group;
        final String tenant = cacheData.tenant;
        File path = LocalConfigInfoProcessor.getFailoverFile(agent.getName(), dataId, group, tenant);

        // 没有 -> 有 如果没有使用本地配置，但是文件存在，主动开启本地配置
        if (!cacheData.isUseLocalConfigInfo() && path.exists()) {
            String content = LocalConfigInfoProcessor.getFailover(agent.getName(), dataId, group, tenant);
            String md5 = MD5.getInstance().getMD5String(content);
            cacheData.setUseLocalConfigInfo(true);
            cacheData.setLocalConfigInfoVersion(path.lastModified());
            cacheData.setContent(content);

            LOGGER.warn("[{}] [failover-change] failover file created. dataId={}, group={}, tenant={}, md5={}, content={}",
                agent.getName(), dataId, group, tenant, md5, ContentUtils.truncateContent(content));
            return;
        }

        // 有 -> 没有。不通知业务监听器，从server拿到配置后通知。
        //开启了本地配置，但是文件不存在，则关闭本地配置
        if (cacheData.isUseLocalConfigInfo() && !path.exists()) {
            cacheData.setUseLocalConfigInfo(false);
            LOGGER.warn("[{}] [failover-change] failover file deleted. dataId={}, group={}, tenant={}", agent.getName(),
                dataId, group, tenant);
            return;
        }

        // 有变更
        /**
         * 使用本地配置且文件存在，文件被修改则重新加载本地配置文件到内存中
         */
        if (cacheData.isUseLocalConfigInfo() && path.exists()
            && cacheData.getLocalConfigInfoVersion() != path.lastModified()) {
            String content = LocalConfigInfoProcessor.getFailover(agent.getName(), dataId, group, tenant);
            String md5 = MD5.getInstance().getMD5String(content);
            cacheData.setUseLocalConfigInfo(true);
            cacheData.setLocalConfigInfoVersion(path.lastModified());
            cacheData.setContent(content);
            LOGGER.warn("[{}] [failover-change] failover file changed. dataId={}, group={}, tenant={}, md5={}, content={}",
                agent.getName(), dataId, group, tenant, md5, ContentUtils.truncateContent(content));
        }
    }

    private String null2defaultGroup(String group) {
        return (null == group) ? Constants.DEFAULT_GROUP : group.trim();
    }

    /**
     * 先得再说下这个，这个方法是每10毫秒执行一次，但是并不是会一直去执行新的LongPollingRunnable任务，
     * 是根据监听器的数量决定要不要再启动一个LongPollingRunnable，每个LongPollingRunnable默认可以负责3000个监听器的轮询。
     * 所以一般就只是开启了一个，因为是Math.ceil向上取整，最开始就会开启。
     */
    public void checkConfigInfo() {
        // 分任务
        int listenerSize = cacheMap.get().size();
        // 向上取整为批数  longingTaskCount表示需要LongPollingRunnable任务数量
        int longingTaskCount = (int) Math.ceil(listenerSize / ParamUtil.getPerTaskConfigSize());
        if (longingTaskCount > currentLongingTaskCount) {
            for (int i = (int) currentLongingTaskCount; i < longingTaskCount; i++) {
                // 要判断任务是否在执行 这块需要好好想想。 任务列表现在是无序的。变化过程可能有问题
                executorService.execute(new LongPollingRunnable(i));
            }
            currentLongingTaskCount = longingTaskCount;
        }
    }

    /**
     * 从Server获取值变化了的DataID列表。返回的对象里只有dataId和group是有效的。 保证不返回NULL。
     *
     * 这里就会根据isInitializingCacheList来设置一个标记，让服务器判断是否要挂起，请求的url是/v1/cs/configs/listener，
     * 这里超时增加了，默认变成了45秒，就是为了应对挂起和检查配置文件变更。这里根据情况还要设置服务器健康状态setHealthServer，然后拿到改变的配置文件结果解析后返回。
     */
    List<String> checkUpdateDataIds(List<CacheData> cacheDatas, List<String> inInitializingCacheList) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (CacheData cacheData : cacheDatas) {//把配置信息都连起来，一次请求
            if (!cacheData.isUseLocalConfigInfo()) {
                sb.append(cacheData.dataId).append(WORD_SEPARATOR);
                sb.append(cacheData.group).append(WORD_SEPARATOR);
                if (StringUtils.isBlank(cacheData.tenant)) {
                    sb.append(cacheData.getMd5()).append(LINE_SEPARATOR);
                } else {
                    sb.append(cacheData.getMd5()).append(WORD_SEPARATOR);
                    sb.append(cacheData.getTenant()).append(LINE_SEPARATOR);
                }
                if (cacheData.isInitializing()) {
                    // cacheData 首次出现在cacheMap中&首次check更新
                    inInitializingCacheList
                        .add(GroupKey.getKeyTenant(cacheData.dataId, cacheData.group, cacheData.tenant));
                }
            }
        }
        boolean isInitializingCacheList = !inInitializingCacheList.isEmpty();
        return checkUpdateConfigStr(sb.toString(), isInitializingCacheList);
    }

    /**
     * 从Server获取值变化了的DataID列表。返回的对象里只有dataId和group是有效的。 保证不返回NULL。
     */
    List<String> checkUpdateConfigStr(String probeUpdateString, boolean isInitializingCacheList) throws IOException {


        List<String> params = new ArrayList<String>(2);
        params.add(Constants.PROBE_MODIFY_REQUEST);
        params.add(probeUpdateString);

        List<String> headers = new ArrayList<String>(2);
        headers.add("Long-Pulling-Timeout");
        headers.add("" + timeout);

        // told server do not hang me up if new initializing cacheData added in
        if (isInitializingCacheList) {//是初始化的会设置一个请求头标记
            headers.add("Long-Pulling-Timeout-No-Hangup");
            headers.add("true");
        }

        if (StringUtils.isBlank(probeUpdateString)) {
            return Collections.emptyList();
        }

        try {
            // In order to prevent the server from handling the delay of the client's long task,
            // increase the client's read timeout to avoid this problem.
            // 增加超时时间，防止被挂起，只有初始化的时候isInitializingCacheList=true不会挂起，应该是服务器看了请求头Long-Pulling-Timeout-No-Hangup才不会挂起
            long readTimeoutMs = timeout + (long) Math.round(timeout >> 1);
            /**
             * 这里的调用就是所谓的长轮询
             * url:   http://ip:port/v1/cs/configs/listener
             * 超时时间是默认值30s
             */
            HttpResult result = agent.httpPost(Constants.CONFIG_CONTROLLER_PATH + "/listener", headers, params,
                agent.getEncode(), readTimeoutMs);

            if (HttpURLConnection.HTTP_OK == result.code) {
                setHealthServer(true);
                // 将返回的dataId清单进行解析并返回到上层
                return parseUpdateDataIdResponse(result.content);
            } else {
                // NacosConfigService.getServerStatus就是通过healthServer判断
                setHealthServer(false);
                LOGGER.error("[{}] [check-update] get changed dataId error, code: {}", agent.getName(), result.code);
            }
        } catch (IOException e) {
            setHealthServer(false);
            LOGGER.error("[" + agent.getName() + "] [check-update] get changed dataId exception", e);
            throw e;
        }
        return Collections.emptyList();
    }

    /**
     * 从HTTP响应拿到变化的groupKey。保证不返回NULL。
     */
    private List<String> parseUpdateDataIdResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return Collections.emptyList();
        }

        try {
            response = URLDecoder.decode(response, "UTF-8");
        } catch (Exception e) {
            LOGGER.error("[" + agent.getName() + "] [polling-resp] decode modifiedDataIdsString error", e);
        }

        List<String> updateList = new LinkedList<String>();

        for (String dataIdAndGroup : response.split(LINE_SEPARATOR)) {
            if (!StringUtils.isBlank(dataIdAndGroup)) {
                String[] keyArr = dataIdAndGroup.split(WORD_SEPARATOR);
                String dataId = keyArr[0];
                String group = keyArr[1];
                if (keyArr.length == 2) {
                    updateList.add(GroupKey.getKey(dataId, group));
                    LOGGER.info("[{}] [polling-resp] config changed. dataId={}, group={}", agent.getName(), dataId, group);
                } else if (keyArr.length == 3) {
                    String tenant = keyArr[2];
                    updateList.add(GroupKey.getKeyTenant(dataId, group, tenant));
                    LOGGER.info("[{}] [polling-resp] config changed. dataId={}, group={}, tenant={}", agent.getName(),
                        dataId, group, tenant);
                } else {
                    LOGGER.error("[{}] [polling-resp] invalid dataIdAndGroup error {}", agent.getName(), dataIdAndGroup);
                }
            }
        }
        return updateList;
    }

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    public ClientWorker(final HttpAgent agent, final ConfigFilterChainManager configFilterChainManager, final Properties properties) {
        this.agent = agent;
        this.configFilterChainManager = configFilterChainManager;

        // Initialize the timeout parameter

        init(properties);

        executor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("com.alibaba.nacos.client.Worker." + agent.getName());
                t.setDaemon(true);
                return t;
            }
        });

        executorService = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("com.alibaba.nacos.client.Worker.longPolling." + agent.getName());
                t.setDaemon(true);
                return t;
            }
        });

        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    checkConfigInfo();
                } catch (Throwable e) {
                    LOGGER.error("[" + agent.getName() + "] [sub-check] rotate check error", e);
                }
            }
        }, 1L, 10L, TimeUnit.MILLISECONDS);
    }

    private void init(Properties properties) {

        timeout = Math.max(NumberUtils.toInt(properties.getProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT),
            Constants.CONFIG_LONG_POLL_TIMEOUT), Constants.MIN_CONFIG_LONG_POLL_TIMEOUT);

        taskPenaltyTime = NumberUtils.toInt(properties.getProperty(PropertyKeyConst.CONFIG_RETRY_TIME), Constants.CONFIG_RETRY_TIME);

        enableRemoteSyncConfig = Boolean.parseBoolean(properties.getProperty(PropertyKeyConst.ENABLE_REMOTE_SYNC_CONFIG));
    }

    class LongPollingRunnable implements Runnable {
        private int taskId;

        public LongPollingRunnable(int taskId) {
            this.taskId = taskId;
        }

        /**
         * 1、首先会遍历所有的CacheData，找出是当前任务的加入到一个集合里；
         * 2、然后如果是本次任务的就先检查本地配置，如果有改变的话就要通知监听器listener；
         * 3、然后去请求服务器获取配置信息，如果是有在初始化的CacheData，那服务器就会立即返回，否则会被挂起，
         *      这个原因就是为了不进行频繁的空轮询，又能实现动态配置，只要在挂起的时间段内有改变，就可以立即响应给客户端。
         *      获取完之后再检查有没改变，有的话也要通知，然后继续调度当前任务。
         *
         */
        @Override
        public void run() {

            List<CacheData> cacheDatas = new ArrayList<CacheData>();
            //是否是在初始化的CacheData，会影响服务器是否挂起或者立即返回
            List<String> inInitializingCacheList = new ArrayList<String>();
            try {
                // check failover config
                for (CacheData cacheData : cacheMap.get().values()) {
                    //首先会遍历所有的CacheData，找出是当前任务的加入到一个集合里
                    if (cacheData.getTaskId() == taskId) {//属于当前长轮询任务的
                        cacheDatas.add(cacheData);
                        try {
                            // 检查内存中的缓存信息与本地配置中信息是否一致，并进行相应处理
                            checkLocalConfig(cacheData);
                            if (cacheData.isUseLocalConfigInfo()) {//使用本地配置
                                // 本地配置和缓存比对有变更会触发listener通知
                                cacheData.checkListenerMd5();
                            }
                        } catch (Exception e) {
                            LOGGER.error("get local config info error", e);
                        }
                    }
                }

                // check server config
                /**
                 * 检查nacos server变更的配置文件，返回值是变更文件列表
                 * 这个是把所有的CacheData的相关信息都连起来，一次性批量请求。但是其中有个比较重要的就是inInitializingCacheList，
                 * 这个表示里面是否有正在初始化的CacheData，如果有的话后面会设置一个标记，是的服务器不会挂起请求，会立即响应。
                 * 这里的响应只是告诉你哪些是有变化的，不会把内容给你，后面还得请求内容。
                 */
                List<String> changedGroupKeys = checkUpdateDataIds(cacheDatas, inInitializingCacheList);
                LOGGER.info("get changedGroupKeys:" + changedGroupKeys);

                for (String groupKey : changedGroupKeys) {
                    String[] key = GroupKey.parseKey(groupKey);
                    String dataId = key[0];
                    String group = key[1];
                    String tenant = null;
                    if (key.length == 3) {
                        tenant = key[2];
                    }
                    try {
                        //有更新的从nacos server上获取配置最新内容，超时3秒
                        String[] ct = getServerConfig(dataId, group, tenant, 3000L);
                        CacheData cache = cacheMap.get().get(GroupKey.getKeyTenant(dataId, group, tenant));
                        cache.setContent(ct[0]);//设置配置内容
                        if (null != ct[1]) {
                            cache.setType(ct[1]);//设置配置类型
                        }
                        LOGGER.info("[{}] [data-received] dataId={}, group={}, tenant={}, md5={}, content={}, type={}",
                            agent.getName(), dataId, group, tenant, cache.getMd5(),
                            ContentUtils.truncateContent(ct[0]), ct[1]);
                    } catch (NacosException ioe) {
                        String message = String.format(
                            "[%s] [get-update] get changed config exception. dataId=%s, group=%s, tenant=%s",
                            agent.getName(), dataId, group, tenant);
                        LOGGER.error(message, ioe);
                    }
                }
                for (CacheData cacheData : cacheDatas) {
                    //不是初始化中的或者初始化集合里存在的
                    if (!cacheData.isInitializing() || inInitializingCacheList
                        .contains(GroupKey.getKeyTenant(cacheData.dataId, cacheData.group, cacheData.tenant))) {
                        ////检查是否有变更，变更则触发listener回调
                        cacheData.checkListenerMd5();
                        //请求过了后就设置为不在初始化中，这样就会被挂起，如果服务器配置有更新，就会立即返回，这样就可以实现动态配置更新，又不会太多的空轮询消耗
                        cacheData.setInitializing(false);
                    }
                }
                inInitializingCacheList.clear();
                //继续调度当前任务
                executorService.execute(this);

            } catch (Throwable e) {

                // If the rotation training task is abnormal, the next execution time of the task will be punished
                LOGGER.error("longPolling error : ", e);
                executorService.schedule(this, taskPenaltyTime, TimeUnit.MILLISECONDS);
            }
        }
    }

    public boolean isHealthServer() {
        return isHealthServer;
    }

    private void setHealthServer(boolean isHealthServer) {
        this.isHealthServer = isHealthServer;
    }

    final ScheduledExecutorService executor;
    final ScheduledExecutorService executorService;

    /**
     * groupKey -> cacheData
     *
     * key = dataId+group+namespace，如：other+DEFAULT_GROUP[+namespace]
     *
     */
    private final AtomicReference<Map<String, CacheData>> cacheMap = new AtomicReference<Map<String, CacheData>>(
        new HashMap<String, CacheData>());

    private final HttpAgent agent;
    private final ConfigFilterChainManager configFilterChainManager;
    private boolean isHealthServer = true;
    private long timeout;
    private double currentLongingTaskCount = 0;
    private int taskPenaltyTime;
    private boolean enableRemoteSyncConfig = false;
}
