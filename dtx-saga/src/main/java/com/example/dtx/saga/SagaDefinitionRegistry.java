package com.example.dtx.saga;

import com.example.dtx.common.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds every known saga definition. Steps are injected in {@code @Order} so a
 * definition is just "all step beans, in order".
 */
@Component
public class SagaDefinitionRegistry {

    public static final String ORDER_SAGA = "orderSaga";

    private final Map<String, SagaDefinition> definitions = new LinkedHashMap<>();

    public SagaDefinitionRegistry(List<SagaStep> steps) {
        SagaDefinition orderSaga = new SagaDefinition(ORDER_SAGA, steps);
        definitions.put(orderSaga.name(), orderSaga);
    }

    public SagaDefinition get(String name) {
        SagaDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new BizException("SAGA_DEFINITION_UNKNOWN", "unknown saga definition " + name);
        }
        return definition;
    }
}
