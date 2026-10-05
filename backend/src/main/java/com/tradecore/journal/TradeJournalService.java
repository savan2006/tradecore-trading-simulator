package com.tradecore.journal;

import com.tradecore.execution.ExecutionRepository;
import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import com.tradecore.order.TradingOrder;
import com.tradecore.order.TradingOrderRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TradeJournalService {
    private static final int MAX_PAGE = 100_000;
    private static final int MAX_SIZE = 100;
    private final TradeJournalRepository journals;
    private final TradingOrderRepository orders;
    private final ExecutionRepository executions;
    private final UserRepository users;

    public TradeJournalService(TradeJournalRepository journals, TradingOrderRepository orders,
            ExecutionRepository executions, UserRepository users) {
        this.journals = journals;
        this.orders = orders;
        this.executions = executions;
        this.users = users;
    }

    @Transactional
    public TradeJournalResponse create(String authenticatedEmail, TradeJournalRequest request) {
        validate(request.thesis(), request.strategyTag(), request.wentWell(), request.wentWrong(),
                request.lessonLearned(), request.rating());
        if (request.orderId() == null) throw badRequest("orderId is required");
        String email = normalizeEmail(authenticatedEmail);
        TradingOrder order = orders.findByIdAndAccount_User_Email(request.orderId(), email)
                .orElseThrow(() -> notFound("Completed trade was not found"));
        if (!"FILLED".equals(order.getStatus()) || executions.countByOrder_Id(order.getId()) < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Journal entries can only be created for completed trades");
        }
        if (journals.existsByOrder_Id(order.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A journal entry already exists for this trade");
        }
        User user = users.findByEmail(email).orElseThrow(() -> notFound("User was not found"));
        Instant now = Instant.now();
        try {
            TradeJournalEntry saved = journals.saveAndFlush(new TradeJournalEntry(user, order,
                    cleanRequired(request.thesis()), cleanOptional(request.strategyTag()),
                    cleanOptional(request.wentWell()), cleanOptional(request.wentWrong()),
                    cleanOptional(request.lessonLearned()), request.rating(), now));
            return TradeJournalResponse.from(saved);
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A journal entry already exists for this trade", duplicate);
        }
    }

    @Transactional(readOnly = true)
    public TradeJournalPageResponse list(String authenticatedEmail, int page, int size) {
        validatePage(page, size);
        var result = journals.findByUser_Email(normalizeEmail(authenticatedEmail),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new TradeJournalPageResponse(result.getContent().stream().map(TradeJournalResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional(readOnly = true)
    public TradeJournalResponse get(String authenticatedEmail, UUID id) {
        return journals.findByIdAndUser_Email(id, normalizeEmail(authenticatedEmail))
                .map(TradeJournalResponse::from)
                .orElseThrow(() -> notFound("Journal entry was not found"));
    }

    @Transactional
    public TradeJournalResponse update(String authenticatedEmail, UUID id, TradeJournalUpdateRequest request) {
        validate(request.thesis(), request.strategyTag(), request.wentWell(), request.wentWrong(),
                request.lessonLearned(), request.rating());
        TradeJournalEntry entry = journals.findByIdAndUser_Email(id, normalizeEmail(authenticatedEmail))
                .orElseThrow(() -> notFound("Journal entry was not found"));
        entry.update(cleanRequired(request.thesis()), cleanOptional(request.strategyTag()),
                cleanOptional(request.wentWell()), cleanOptional(request.wentWrong()),
                cleanOptional(request.lessonLearned()), request.rating(), Instant.now());
        return TradeJournalResponse.from(entry);
    }

    @Transactional
    public void delete(String authenticatedEmail, UUID id) {
        TradeJournalEntry entry = journals.findByIdAndUser_Email(id, normalizeEmail(authenticatedEmail))
                .orElseThrow(() -> notFound("Journal entry was not found"));
        journals.delete(entry);
    }

    private static void validate(String thesis, String strategyTag, String wentWell, String wentWrong,
            String lessonLearned, Short rating) {
        if (thesis == null || thesis.isBlank()) throw badRequest("thesis is required");
        if (thesis.length() > 2000) throw badRequest("thesis must be at most 2000 characters");
        if (strategyTag != null && strategyTag.length() > 80) throw badRequest("strategyTag must be at most 80 characters");
        if (wentWell != null && wentWell.length() > 2000) throw badRequest("wentWell must be at most 2000 characters");
        if (wentWrong != null && wentWrong.length() > 2000) throw badRequest("wentWrong must be at most 2000 characters");
        if (lessonLearned != null && lessonLearned.length() > 2000) throw badRequest("lessonLearned must be at most 2000 characters");
        if (rating != null && (rating < 1 || rating > 5)) throw badRequest("rating must be between 1 and 5");
    }
    private static String cleanRequired(String value) { return value.trim(); }
    private static String cleanOptional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private static void validatePage(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw badRequest("Invalid pagination parameters");
        }
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
