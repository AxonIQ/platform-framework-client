/*
 * Copyright (c) 2022-2026. Axoniq B.V.
 *
 * Licensed under the Axoniq Terms of Service (the "Terms"). You may not
 * use this file except in compliance with the Terms, available at
 * https://www.axoniq.io/legal/terms-of-service
 */

package io.axoniq.platform.framework.workflow;

import io.axoniq.framework.workflow.dsl.api.PayloadProcessor;
import io.axoniq.framework.workflow.dsl.api.WorkflowStepResult;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.runtime.api.execution.context.WorkflowDefinition;

import java.time.Duration;

/**
 * Workflow definitions written in Java, as an application would. They are only read by the analysis, never run, so
 * the arguments the analysis does not look at are left null.
 */
public class JavaWorkflows {

    public void orderFulfilment(SimpleWorkflowContext ctx) {
        Boolean reserved = ctx.awaitExecute("reserveStock", Boolean.class, () -> true);
        if (!reserved) {
            ctx.awaitPublish("orderRejected", new Object());
            ctx.fail(new IllegalStateException("Out of stock"));
            return;
        }
        WorkflowStepResult payment = ctx.waitForEvent("paymentReceived", Object.class, null);
        WorkflowStepResult shipment = ctx.execute("prepareShipment", (PayloadProcessor) null);
        ctx.allMatch(WorkflowStepResult::success, payment, shipment);
        for (int attempt = 0; attempt < 3; attempt++) {
            ctx.sleep("waitForCourier", Duration.ofMinutes(5));
        }
        ctx.awaitPublish("orderShipped", new Object());
        sendInvoice(ctx);
    }

    private void sendInvoice(SimpleWorkflowContext ctx) {
        ctx.awaitExecute("sendInvoice", Boolean.class, () -> true);
    }

    public void paymentAcrossHelpers(SimpleWorkflowContext ctx) {
        WorkflowStepResult confirmation = registerPaymentWait(ctx);
        Boolean reserved = ctx.awaitExecute("reserve", Boolean.class, () -> true);
        if (!reserved) {
            ctx.fail(new IllegalStateException("Out of stock"));
            return;
        }
        awaitPayment(ctx, confirmation);
        ctx.awaitExecute("ship", Boolean.class, () -> true);
    }

    private WorkflowStepResult registerPaymentWait(SimpleWorkflowContext ctx) {
        return ctx.waitForEvent("paymentConfirmed", Object.class, null);
    }

    private void awaitPayment(SimpleWorkflowContext ctx, WorkflowStepResult confirmation) {
        WorkflowStepResult initiation = initiatePayment(ctx);
        var payment = ctx.allMatch(WorkflowStepResult::success, confirmation, initiation);
        payment.await();
        if (!payment.success()) {
            String failure = initiation.timeout() ? "recordTimeout" : "recordFailure";
            ctx.awaitPublish(failure, new Object());
            ctx.fail(new IllegalStateException("Payment failed"));
        }
    }

    private WorkflowStepResult initiatePayment(SimpleWorkflowContext ctx) {
        return ctx.execute("initiatePayment", (PayloadProcessor) null);
    }

    public void computedStepName(SimpleWorkflowContext ctx) {
        ctx.awaitExecute("step-" + System.nanoTime(), Boolean.class, () -> true);
    }

    public void earlyReturn(SimpleWorkflowContext ctx) {
        if (ctx.workflowPayload().isEmpty()) {
            return;
        }
        ctx.awaitExecute("onlyWithPayload", Boolean.class, () -> true);
    }

    public static WorkflowDefinition<SimpleWorkflowContext> lambdaDefinition() {
        return ctx -> {
            ctx.awaitExecute("first", Boolean.class, () -> true);
            ctx.awaitExecute("second", Boolean.class, () -> true);
        };
    }

    public static WorkflowDefinition<SimpleWorkflowContext> methodReferenceDefinition() {
        return new JavaWorkflows()::earlyReturn;
    }
}
