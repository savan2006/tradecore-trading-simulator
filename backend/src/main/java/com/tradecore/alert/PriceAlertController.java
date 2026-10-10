package com.tradecore.alert;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Alerts")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/alerts")
public class PriceAlertController {
    private final PriceAlertService service;
    public PriceAlertController(PriceAlertService service) { this.service = service; }

    @Operation(summary = "Create", responses = {@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PriceAlertResponse create(Authentication auth, @Valid @RequestBody PriceAlertRequest request) {
        return service.create(auth.getName(), request);
    }
    @Operation(summary = "List", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})
    @GetMapping
    public List<PriceAlertResponse> list(Authentication auth) { return service.list(auth.getName()); }
    @Operation(summary = "Delete", responses = {@ApiResponse(responseCode = "204", description = "Deleted"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id) { service.delete(auth.getName(), id); }
}
