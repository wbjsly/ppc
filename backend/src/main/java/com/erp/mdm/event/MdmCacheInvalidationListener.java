package com.erp.mdm.event;

import com.erp.mdm.cache.MdmReadCache;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 缓存失效监听（最高优先级，先于业务监听器执行）：
 * 点查按 entityId 精准失效 + 该类列表/树整类失效。
 */
@Component
public class MdmCacheInvalidationListener {

    private final MdmReadCache cache;

    public MdmCacheInvalidationListener(MdmReadCache cache) {
        this.cache = cache;
    }

    @Order(0)
    @EventListener
    public void onMdmChanged(MdmChangedEvent e) {
        cache.invalidate(e.getBizType(), e.getEntityId());
    }
}
