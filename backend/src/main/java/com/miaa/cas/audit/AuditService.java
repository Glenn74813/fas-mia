package com.miaa.cas.audit;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records business and administrative audit events inside the caller's transaction, so an audit
 * row exists if and only if the audited change commits.
 */
@Service
public class AuditService {

    private final EventStore store;

    public AuditService(EventStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String resourceType, Object resourceId, Map<String, Object> details) {
        record(Actor.current(), action, resourceType, resourceId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, String resourceType, Object resourceId, Map<String, Object> details) {
        store.insert(EventStore.Table.AUDIT, actor, action, resourceType,
                resourceId == null ? null : resourceId.toString(), "SUCCESS", null, details);
    }
}
