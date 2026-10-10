package com.tradecore.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;

@Tag(name = "Orders")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/orders")
public class OrderPlacementController {
    private final OrderPlacementService orderPlacementService;
    private final OrderCancellationService orderCancellationService;

    public OrderPlacementController(OrderPlacementService orderPlacementService,
            OrderCancellationService orderCancellationService) {
        this.orderPlacementService = orderPlacementService;
        this.orderCancellationService = orderCancellationService;
    }

    @Operation(summary = "Place Order", responses = {@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state"), @ApiResponse(responseCode = "422", description = "The request cannot be processed under current validation rules"), @ApiResponse(responseCode = "429", description = "Request rate limit exceeded")})

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderPlacementResponse placeOrder(Authentication authentication,
            @Valid @RequestBody OrderPlacementRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return orderPlacementService.placeOrder(authentication.getName(), request, idempotencyKey);
    }

    @Operation(summary = "Preview Order", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state"), @ApiResponse(responseCode = "422", description = "The request cannot be processed under current validation rules")})

    @PostMapping("/preview")
    public OrderPreviewResponse previewOrder(Authentication authentication,
            @Valid @RequestBody OrderPlacementRequest request) {
        return orderPlacementService.previewOrder(authentication.getName(), request);
    }

    @Operation(summary = "Modify Order", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state"), @ApiResponse(responseCode = "422", description = "The request cannot be processed under current validation rules")})

    @PutMapping("/{orderId}")
    public OrderHistoryResponse modifyOrder(Authentication authentication, @PathVariable UUID orderId,
            @Valid @RequestBody OrderModificationRequest request) {
        return orderPlacementService.modifyPendingOrder(authentication.getName(), orderId, request);
    }

    @Operation(summary = "Cancel Order", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state"), @ApiResponse(responseCode = "429", description = "Request rate limit exceeded")})

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public OrderCancellationResponse cancelOrder(Authentication authentication, @PathVariable UUID orderId) {
        return orderCancellationService.cancel(authentication.getName(), orderId);
    }
}
