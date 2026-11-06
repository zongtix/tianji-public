package com.tianji.upgrade;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Probes only the task namespace, leaving public/dev configuration untouched. */
public class NacosClientProbe {
    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", "127.0.0.1:8848");
        properties.setProperty("namespace", "tianji-platform-upgrade-test");
        ConfigService config = NacosFactory.createConfigService(properties);
        NamingService naming = NacosFactory.createNamingService(properties);
        String run = UUID.randomUUID().toString();
        String dataId = "platform-upgrade-client-probe-" + run + ".yaml";
        String service = "platform-upgrade-client-probe-" + run;
        String group = "DEFAULT_GROUP";
        CountDownLatch updated = new CountDownLatch(1);
        Listener listener = new Listener() {
            public Executor getExecutor() { return null; }
            public void receiveConfigInfo(String value) {
                if ("probe: updated".equals(value)) updated.countDown();
            }
        };
        try {
            require(config.publishConfig(dataId, group, "probe: initial"), "publish config");
            boolean readable = false;
            // The server acknowledges persistence before its asynchronous config dump is visible.
            for (int attempt = 0; attempt < 20 && !readable; attempt++) {
                readable = "probe: initial".equals(config.getConfig(dataId, group, 5000));
                if (!readable) Thread.sleep(250);
            }
            require(readable, "read config");
            config.addListener(dataId, group, listener);
            require(config.publishConfig(dataId, group, "probe: updated"), "update config");
            require(updated.await(20, TimeUnit.SECONDS), "receive config push");
            naming.registerInstance(service, group, "127.0.0.1", 19001);
            boolean found = false;
            for (int attempt = 0; attempt < 20 && !found; attempt++) {
                found = naming.getAllInstances(service, group).stream()
                    .anyMatch(i -> "127.0.0.1".equals(i.getIp()) && i.getPort() == 19001);
                if (!found) Thread.sleep(250);
            }
            require(found, "register and discover isolated instance");
            System.out.println("PROBE PASS: Nacos client 3.0.3 config read/write/push and naming registration/discovery.");
        } finally {
            try { naming.deregisterInstance(service, group, "127.0.0.1", 19001); }
            finally {
                config.removeListener(dataId, group, listener);
                try { config.removeConfig(dataId, group); }
                finally { naming.shutDown(); config.shutDown(); }
            }
        }
    }

    private static void require(boolean value, String check) {
        if (!value) throw new IllegalStateException("PROBE FAIL: " + check);
        System.out.println("PASS " + check);
    }
}
