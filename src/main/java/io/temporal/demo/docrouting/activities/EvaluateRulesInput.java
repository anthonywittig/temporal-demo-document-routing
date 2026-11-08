package io.temporal.demo.docrouting.activities;

import io.temporal.demo.docrouting.model.DocumentRequest;

/**
 * Input to the {@code EvaluateRules} Activity.
 *
 * @param docType the type {@code Classify} returned
 * @param costApproved a reviewer has approved the cost; used only if the current rules still put
 *     the cost above the human-review threshold
 */
public record EvaluateRulesInput(DocumentRequest doc, String docType, boolean costApproved) {}
