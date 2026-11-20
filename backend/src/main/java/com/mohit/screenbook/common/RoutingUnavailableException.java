package com.mohit.screenbook.common;

import org.springframework.http.HttpStatus;

public class RoutingUnavailableException extends ApiException {

    public RoutingUnavailableException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, Problems.ROUTING_UNAVAILABLE,
                "Routing unavailable", detail);
    }
}