package com.erp.mdm.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MDM 变更事件发布注册器（add-mdm-version-sync）：
 * 以 JDK 动态代理包装全部 MDM ServiceImpl（零 AspectJ 依赖），
 * create/update/disable/archive/merge 前缀方法成功返回后广播事件+写发件箱。
 * 事件广播失败不阻断主流程（MdmEventBus 内部已隔离）。
 */
@Slf4j
@Component
public class MdmEventPublisherRegistrar implements BeanPostProcessor {

    private final MdmEventBus eventBus;
    /** 简类名 → bizType（只登记 13 类版本中心覆盖的类型） */
    private static final Map<String, String> BIZ = new ConcurrentHashMap<>();

    static {
        BIZ.put("MdmItemServiceImpl", "ITEM");
        BIZ.put("MdmCustomerServiceImpl", "CUSTOMER_GROUP");
        BIZ.put("MdmSupplierServiceImpl", "SUPPLIER");
        BIZ.put("MdmExchangeRateServiceImpl", "EXCHANGE_RATE");
        BIZ.put("MdmTaxCodeServiceImpl", "TAX_CODE");
        BIZ.put("MdmTaxPolicyServiceImpl", "TAX_POLICY");
        BIZ.put("MdmOrgUnitServiceImpl", "ORG_UNIT");
        BIZ.put("MdmCostCenterServiceImpl", "COST_CENTER");
        BIZ.put("MdmProfitCenterServiceImpl", "PROFIT_CENTER");
        BIZ.put("MdmLegalEntityServiceImpl", "LEGAL_ENTITY");
        BIZ.put("MdmItemCategoryServiceImpl", "ITEM_CATEGORY");
        BIZ.put("MdmPriceAgreementServiceImpl", "PRICE_AGREEMENT");
        BIZ.put("MdmCustomerViewServiceImpl", "CUSTOMER_VIEW");
    }

    public MdmEventPublisherRegistrar(@Lazy MdmEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        String bizType = BIZ.get(bean.getClass().getSimpleName());
        if (bizType == null) {
            return bean;
        }
        Class<?> iface = firstInterface(bean.getClass());
        if (iface == null) {
            return bean;
        }
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            Object ret;
            try {
                ret = method.invoke(bean, args);
            } catch (InvocationTargetException ite) {
                throw ite.getTargetException(); // 业务异常原样抛出，不发事件
            }
            try {
                String op = opOf(method.getName());
                if (op != null) {
                    publishSafe(bizType, op, ret, args);
                }
            } catch (Exception ex) {
                log.warn("mdm event publish failed (non-blocking): {}", ex.getMessage());
            }
            return ret;
        });
    }

    private void publishSafe(String bizType, String op, Object ret, Object[] args) {
        String entityId = entityIdOf(ret, args);
        if (entityId == null || entityId.isBlank()) {
            return;
        }
        if ("MERGED".equals(op) && !("SUPPLIER".equals(bizType) || "CUSTOMER_GROUP".equals(bizType))) {
            return; // MERGED 仅供应商/客户合并
        }
        eventBus.publish("MDM." + bizType + "." + op, bizType, entityId, 1, List.of(), false);
    }

    private String opOf(String method) {
        String m = method.toLowerCase();
        if (m.startsWith("create")) {
            return "CREATED";
        }
        if (m.startsWith("update")) {
            return "UPDATED";
        }
        if (m.startsWith("disable") || m.startsWith("archive")) {
            return "DISABLED";
        }
        if (m.startsWith("merge") && !m.startsWith("mergecandidates")) {
            return "MERGED";
        }
        return null;
    }

    private String entityIdOf(Object ret, Object[] args) {
        if (ret != null) {
            String id = idOf(ret);
            if (id != null) {
                return id;
            }
            if (ret instanceof Map<?, ?> m && m.get("id") != null) {
                return String.valueOf(m.get("id"));
            }
        }
        if (args != null) {
            for (Object a : args) {
                String id = idOf(a);
                if (id != null) {
                    return id;
                }
            }
            // disable(id, reason) / archive(id, reason)：首个 String 参数即 id
            for (Object a : args) {
                if (a instanceof String s && s.length() >= 16 && !s.contains(" ")) {
                    return s;
                }
            }
        }
        return null;
    }

    private String idOf(Object o) {
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean
                || o instanceof List || o instanceof Map) {
            return null;
        }
        try {
            for (java.beans.PropertyDescriptor pd
                    : java.beans.Introspector.getBeanInfo(o.getClass()).getPropertyDescriptors()) {
                if ("id".equals(pd.getName()) && pd.getReadMethod() != null) {
                    Object v = pd.getReadMethod().invoke(o);
                    return v == null ? null : String.valueOf(v);
                }
            }
        } catch (Exception ignored) {
            // 非 bean 对象跳过
        }
        return null;
    }

    private Class<?> firstInterface(Class<?> clazz) {
        Class<?>[] ifaces = clazz.getInterfaces();
        for (Class<?> i : ifaces) {
            if (i.getName().contains("Service") && !i.getName().contains("Support")) {
                return i;
            }
        }
        return ifaces.length > 0 ? ifaces[0] : null;
    }
}
