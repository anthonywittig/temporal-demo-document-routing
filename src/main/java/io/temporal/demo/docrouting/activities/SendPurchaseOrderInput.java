package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/** Input to the {@code SendPurchaseOrder} Activity. */
public record SendPurchaseOrderInput(DocumentRequest doc) {}
