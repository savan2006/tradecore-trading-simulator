package com.tradecore.journal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@Tag(name = "Journal")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/journal")
public class TradeJournalController {
    private final TradeJournalService service;

    public TradeJournalController(TradeJournalService service) { this.service = service; }

    @Operation(summary = "Create", responses = {@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeJournalResponse create(Authentication authentication, @Valid @RequestBody TradeJournalRequest request) {
        return service.create(authentication.getName(), request);
    }

    @Operation(summary = "List", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping
    public TradeJournalPageResponse list(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(authentication.getName(), page, size);
    }

    @Operation(summary = "Get", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found")})

    @GetMapping("/{id}")
    public TradeJournalResponse get(Authentication authentication, @PathVariable UUID id) {
        return service.get(authentication.getName(), id);
    }

    @Operation(summary = "Update", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PutMapping("/{id}")
    public TradeJournalResponse update(Authentication authentication, @PathVariable UUID id,
            @Valid @RequestBody TradeJournalUpdateRequest request) {
        return service.update(authentication.getName(), id, request);
    }

    @Operation(summary = "Delete", responses = {@ApiResponse(responseCode = "204", description = "Deleted"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable UUID id) {
        service.delete(authentication.getName(), id);
    }
}
