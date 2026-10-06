package com.tradecore.order;

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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderPlacementResponse placeOrder(Authentication authentication,
            @Valid @RequestBody OrderPlacementRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return orderPlacementService.placeOrder(authentication.getName(), request, idempotencyKey);
    }

    @PostMapping("/preview")
    public OrderPreviewResponse previewOrder(Authentication authentication,
            @Valid @RequestBody OrderPlacementRequest request) {
        return orderPlacementService.previewOrder(authentication.getName(), request);
    }

    @PutMapping("/{orderId}")
    public OrderHistoryResponse modifyOrder(Authentication authentication, @PathVariable UUID orderId,
            @Valid @RequestBody OrderModificationRequest request) {
        return orderPlacementService.modifyPendingOrder(authentication.getName(), orderId, request);
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public OrderCancellationResponse cancelOrder(Authentication authentication, @PathVariable UUID orderId) {
        return orderCancellationService.cancel(authentication.getName(), orderId);
    }
}
