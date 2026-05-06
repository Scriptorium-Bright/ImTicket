package org.example.ticket.util.tracing;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> workerContext = MDC.getCopyOfContextMap();

            try {
                applyContext(submitterContext);
                runnable.run();
            } finally {
                applyContext(workerContext);
            }
        };
    }

    private void applyContext(Map<String, String> contextMap) {
        if (contextMap == null || contextMap.isEmpty()) {
            MDC.clear();
            return;
        }

        MDC.setContextMap(contextMap);
    }
}
