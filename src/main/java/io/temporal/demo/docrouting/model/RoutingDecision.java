package io.temporal.demo.docrouting.model;

/** The rules' answer for one evaluation, including which rules version produced it. */
public record RoutingDecision(Route path, String reason, int rulesVersion) {}
