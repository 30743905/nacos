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

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.config.ConfigChangeEvent;
import com.alibaba.nacos.api.config.listener.AbstractSharedListener;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.filter.impl.ConfigFilterChainManager;
import com.alibaba.nacos.client.config.filter.impl.ConfigResponse;
import com.alibaba.nacos.client.config.listener.impl.AbstractConfigChangeListener;
import com.alibaba.nacos.client.config.utils.MD5;
import com.alibaba.nacos.client.utils.LogUtils;
import com.alibaba.nacos.client.utils.TenantUtil;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Listner Management
 *
 * @author Nacos
 */
public class CacheData {

    private static final Logger LOGGER = LogUtils.logger(CacheData.class);

    public boolean isInitializing() {
        return isInitializing;
    }

    public void setInitializing(boolean isInitializing) {
        this.isInitializing = isInitializing;
    }

    public String getMd5() {
        return md5;
    }

    public String getTenant() {
        return tenant;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        this.md5 = getMd5String(this.content);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Add listener
     * if CacheData already set new content, Listener should init lastCallMd5 by CacheData.md5
     *
     * @param listener listener
     */
    public void addListener(Listener listener) {
        if (null == listener) {
            throw new IllegalArgumentException("listener is null");
        }
        ManagerListenerWrap wrap = (listener instanceof AbstractConfigChangeListener) ?
            new ManagerListenerWrap(listener, md5, content) : new ManagerListenerWrap(listener, md5);

        if (listeners.addIfAbsent(wrap)) {
            LOGGER.info("[{}] [add-listener] ok, tenant={}, dataId={}, group={}, cnt={}", name, tenant, dataId, group,
                listeners.size());
        }
    }

    public void removeListener(Listener listener) {
        if (null == listener) {
            throw new IllegalArgumentException("listener is null");
        }
        ManagerListenerWrap wrap = new ManagerListenerWrap(listener);
        if (listeners.remove(wrap)) {
            LOGGER.info("[{}] [remove-listener] ok, dataId={}, group={}, cnt={}", name, dataId, group, listeners.size());
        }
    }

    /**
     * 返回监听器列表上的迭代器，只读。保证不返回NULL。
     */
    public List<Listener> getListeners() {
        List<Listener> result = new ArrayList<Listener>();
        for (ManagerListenerWrap wrap : listeners) {
            result.add(wrap.listener);
        }
        return result;
    }

    public long getLocalConfigInfoVersion() {
        return localConfigLastModified;
    }

    public void setLocalConfigInfoVersion(long localConfigLastModified) {
        this.localConfigLastModified = localConfigLastModified;
    }

    public boolean isUseLocalConfigInfo() {
        return isUseLocalConfig;
    }

    public void setUseLocalConfigInfo(boolean useLocalConfigInfo) {
        this.isUseLocalConfig = useLocalConfigInfo;
        if (!useLocalConfigInfo) {
            localConfigLastModified = -1;
        }
    }

    public int getTaskId() {
        return taskId;
    }

    public void setTaskId(int taskId) {
        this.taskId = taskId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((dataId == null) ? 0 : dataId.hashCode());
        result = prime * result + ((group == null) ? 0 : group.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (null == obj || obj.getClass() != getClass()) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        CacheData other = (CacheData) obj;
        return dataId.equals(other.dataId) && group.equals(other.group);
    }

    @Override
    public String toString() {
        return "CacheData [" + dataId + ", " + group + "]";
    }

    /**
     * 遍历所有的监听器，然后看配置是否变更，有变更会触发监听通知listener
     */
    void checkListenerMd5() {
        for (ManagerListenerWrap wrap : listeners) {
            if (!md5.equals(wrap.lastCallMd5)) {//md5判断
                safeNotifyListener(dataId, group, content, type, md5, wrap);
            }
        }
    }

    /**
     * 创建了一个任务，封装好信息，调用监听器的receiveConfigInfo方法接受数据处理。然后修改内容和MD5。
     * 这里他设置了一下类加载器，包装和监听器的类加载器一样，可能跟SPI反射调用相关。
     *
     * @param dataId
     * @param group
     * @param content
     * @param type
     * @param md5
     * @param listenerWrap
     */
    private void safeNotifyListener(final String dataId, final String group, final String content, final String type,
                                    final String md5, final ManagerListenerWrap listenerWrap) {
        final Listener listener = listenerWrap.listener;

        Runnable job = new Runnable() {
            @Override
            public void run() {
                ClassLoader myClassLoader = Thread.currentThread().getContextClassLoader();
                ClassLoader appClassLoader = listener.getClass().getClassLoader();
                try {
                    if (listener instanceof AbstractSharedListener) {
                        AbstractSharedListener adapter = (AbstractSharedListener) listener;
                        adapter.fillContext(dataId, group);
                        LOGGER.info("[{}] [notify-context] dataId={}, group={}, md5={}", name, dataId, group, md5);
                    }
                    // 执行回调之前先将线程classloader设置为具体webapp的classloader，以免回调方法中调用spi接口是出现异常或错用（多应用部署才会有该问题）。
                    Thread.currentThread().setContextClassLoader(appClassLoader);

                    ConfigResponse cr = new ConfigResponse();
                    cr.setDataId(dataId);
                    cr.setGroup(group);
                    cr.setContent(content);
                    configFilterChainManager.doFilter(null, cr);
                    String contentTmp = cr.getContent();
                    listener.receiveConfigInfo(contentTmp);

                    // compare lastContent and content
                    if (listener instanceof AbstractConfigChangeListener) {
                        Map data = ConfigChangeHandler.getInstance().parseChangeData(listenerWrap.lastContent, content, type);
                        ConfigChangeEvent event = new ConfigChangeEvent(data);
                        ((AbstractConfigChangeListener)listener).receiveConfigChange(event);
                        listenerWrap.lastContent = content;
                    }

                    listenerWrap.lastCallMd5 = md5;
                    LOGGER.info("[{}] [notify-ok] dataId={}, group={}, md5={}, listener={} ", name, dataId, group, md5,
                        listener);
                } catch (NacosException de) {
                    LOGGER.error("[{}] [notify-error] dataId={}, group={}, md5={}, listener={} errCode={} errMsg={}", name,
                        dataId, group, md5, listener, de.getErrCode(), de.getErrMsg());
                } catch (Throwable t) {
                    LOGGER.error("[{}] [notify-error] dataId={}, group={}, md5={}, listener={} tx={}", name, dataId, group,
                        md5, listener, t.getCause());
                } finally {
                    Thread.currentThread().setContextClassLoader(myClassLoader);
                }
            }
        };

        final long startNotify = System.currentTimeMillis();
        try {
            if (null != listener.getExecutor()) {
                listener.getExecutor().execute(job);
            } else {
                job.run();
            }
        } catch (Throwable t) {
            LOGGER.error("[{}] [notify-error] dataId={}, group={}, md5={}, listener={} throwable={}", name, dataId, group,
                md5, listener, t.getCause());
        }
        final long finishNotify = System.currentTimeMillis();
        LOGGER.info("[{}] [notify-listener] time cost={}ms in ClientWorker, dataId={}, group={}, md5={}, listener={} ",
            name, (finishNotify - startNotify), dataId, group, md5, listener);
    }

    static public String getMd5String(String config) {
        return (null == config) ? Constants.NULL : MD5.getInstance().getMD5String(config);
    }

    /**
     * 从本地获取配置内容，优先使用failvoer配置，然后使用snapshot配置
     * @param name
     * @param dataId
     * @param group
     * @param tenant
     * @return
     */
    private String loadCacheContentFromDiskLocal(String name, String dataId, String group, String tenant) {
        /**
         * 优先级最高使用failvoer配置
         * failvoer是本地配置，比如：C:\Users\Admin\nacos\config\fixed-127.0.0.1_8848-192.168.1.1_9999_nacos\data\config-data\DEFAULT_GROUP\other
         */
        String content = LocalConfigInfoProcessor.getFailover(name, dataId, group, tenant);
        /**
         * getFailover()获取空，然后getSnapshot()获取本地配置快照
         * 比如：C:\Users\Admin\nacos\config\fixed-127.0.0.1_8848-192.168.1.1_9999_nacos\snapshot\DEFAULT_GROUP\other
         */
        content = (null != content) ? content
            : LocalConfigInfoProcessor.getSnapshot(name, dataId, group, tenant);
        return content;
    }

    public CacheData(ConfigFilterChainManager configFilterChainManager, String name, String dataId, String group) {
        if (null == dataId || null == group) {
            throw new IllegalArgumentException("dataId=" + dataId + ", group=" + group);
        }
        this.name = name;
        this.configFilterChainManager = configFilterChainManager;
        this.dataId = dataId;
        this.group = group;
        this.tenant = TenantUtil.getUserTenantForAcm();
        listeners = new CopyOnWriteArrayList<ManagerListenerWrap>();
        this.isInitializing = true;
        //从本地获取配置内容
        this.content = loadCacheContentFromDiskLocal(name, dataId, group, tenant);
        //根据配置内容计算md5
        this.md5 = getMd5String(content);
    }

    public CacheData(ConfigFilterChainManager configFilterChainManager, String name, String dataId, String group,
                     String tenant) {
        if (null == dataId || null == group) {
            throw new IllegalArgumentException("dataId=" + dataId + ", group=" + group);
        }
        this.name = name;
        this.configFilterChainManager = configFilterChainManager;
        this.dataId = dataId;
        this.group = group;
        this.tenant = tenant;
        listeners = new CopyOnWriteArrayList<ManagerListenerWrap>();
        this.isInitializing = true;
        this.content = loadCacheContentFromDiskLocal(name, dataId, group, tenant);
        this.md5 = getMd5String(content);
    }

    // ==================

    private final String name;
    private final ConfigFilterChainManager configFilterChainManager;
    public final String dataId;
    public final String group;
    public final String tenant;
    private final CopyOnWriteArrayList<ManagerListenerWrap> listeners;

    private volatile String md5;
    /**
     * whether use local config
     */
    private volatile boolean isUseLocalConfig = false;
    /**
     * last modify time
     */
    private volatile long localConfigLastModified;
    private volatile String content;
    private int taskId;
    private volatile boolean isInitializing = true;
    private String type;
}

class ManagerListenerWrap {
    final Listener listener;
    String lastCallMd5 = CacheData.getMd5String(null);
    String lastContent = null;

    ManagerListenerWrap(Listener listener) {
        this.listener = listener;
    }

    ManagerListenerWrap(Listener listener, String md5) {
        this.listener = listener;
        this.lastCallMd5 = md5;
    }

    ManagerListenerWrap(Listener listener, String md5, String lastContent) {
        this.listener = listener;
        this.lastCallMd5 = md5;
        this.lastContent = lastContent;
    }

    @Override
    public boolean equals(Object obj) {
        if (null == obj || obj.getClass() != getClass()) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        ManagerListenerWrap other = (ManagerListenerWrap) obj;
        return listener.equals(other.listener);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

}
