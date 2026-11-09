package io.temporal.demo.docrouting.model;

/**
 * The rules' answer for one evaluation, including which rules version produced it.
 *
 * @param path where the request goes
 * @param humanReviewRequired a person must approve the cost before the request goes down {@code path}
 */
public record RoutingDecision(Route path, boolean humanReviewRequired, String reason, int rulesVersion) {}
