package com.tradecore.identity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:tradecore-registration-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "tradecore.security.user=registration-static-test",
        "tradecore.security.password=registration-static-password"
})
@AutoConfigureMockMvc
@AutoConfigureTestRestTemplate
class RegistrationAndAccountApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRegistrationService registrationService;

    @MockitoSpyBean
    private TradingAccountRepository accountRepository;

    @Test
    void registrationCreatesHashedUserSingleActiveAccountAndMatchingInitialLedgerCredit() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register(email, "correct-horse-8", "Learner One")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.availableBalance").value(100000.0))
                .andExpect(jsonPath("$.reservedBalance").value(0.0))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(response.path("userId").asText());
        UUID accountId = UUID.fromString(response.path("accountId").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trading_account WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId))
                .isNotEqualTo("correct-horse-8").startsWith("$2");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM trading_account WHERE id = ?", String.class, accountId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT available_balance FROM trading_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("100000.0000");
        assertThat(jdbcTemplate.queryForObject("SELECT reserved_balance FROM trading_account WHERE id = ?", java.math.BigDecimal.class, accountId))
                .isEqualByComparingTo("0.0000");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE account_id = ? AND entry_type = 'INITIAL_DEPOSIT' AND amount = 100000", Integer.class, accountId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_limit WHERE account_id = ?", Integer.class, accountId)).isZero();

        mockMvc.perform(get("/api/v1/account/me").header("Authorization", basic(email, "correct-horse-8")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.availableBalance").value(100000.0));
    }

    @Test
    void duplicateAndInvalidRegistrationsAreRejectedAndCannotCreateAnotherAccount() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-8", "Learner Two").andExpect(status().isCreated());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> duplicateResponse = restTemplate.postForEntity("/api/v1/auth/register",
                new HttpEntity<>(new RegistrationRequest(email.toUpperCase(java.util.Locale.ROOT), "correct-horse-9", "Duplicate"), headers),
                String.class);
        assertThat(duplicateResponse.getStatusCode().value()).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trading_account a JOIN app_user u ON a.user_id=u.id WHERE u.email = ?", Integer.class, email)).isEqualTo(1);

        ResponseEntity<String> invalidResponse = restTemplate.postForEntity("/api/v1/auth/register",
                new HttpEntity<>(new RegistrationRequest("bad-email", "short", ""), headers), String.class);
        assertThat(invalidResponse.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void accountMeRequiresAuthenticationAndReturnsOnlyCurrentIdentityAccount() throws Exception {
        mockMvc.perform(get("/api/v1/account/me")).andExpect(status().isUnauthorized());
        String first = uniqueEmail();
        String second = uniqueEmail();
        MvcResult firstResult = register(first, "first-password-8", "First").andReturn();
        MvcResult secondResult = register(second, "second-password-8", "Second").andReturn();
        String firstAccount = objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("accountId").asText();
        String secondAccount = objectMapper.readTree(secondResult.getResponse().getContentAsString()).path("accountId").asText();
        assertThat(firstAccount).isNotEqualTo(secondAccount);

        mockMvc.perform(get("/api/v1/account/me").header("Authorization", basic(first, "first-password-8")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(firstAccount));
        mockMvc.perform(get("/api/v1/account/me").header("Authorization", basic(second, "second-password-8")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(secondAccount));
        mockMvc.perform(get("/api/v1/account/" + secondAccount).header("Authorization", basic(first, "first-password-8")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/account/me").header("Authorization", basic(first, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failedAccountCreationRollsBackUserRegistration() throws Exception {
        String email = uniqueEmail();
        doThrow(new IllegalStateException("simulated account persistence failure"))
                .when(accountRepository).saveAndFlush(any(TradingAccount.class));

        assertThatThrownBy(() -> registrationService.register(
                new RegistrationRequest(email, "rollback-password-8", "Rollback Test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated account persistence failure");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trading_account a JOIN app_user u ON a.user_id=u.id WHERE u.email = ?", Integer.class, email)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String password, String displayName) throws Exception {
        String body = objectMapper.writeValueAsString(new RegistrationRequest(email, password, displayName));
        return mockMvc.perform(post("/api/v1/auth/register").contentType("application/json").content(body));
    }

    private static String uniqueEmail() { return "phase4a-" + UUID.randomUUID() + "@example.invalid"; }

    private static String basic(String email, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
